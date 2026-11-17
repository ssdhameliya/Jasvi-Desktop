package org.example.service;

import org.example.api.storage.StorageApiClient;
import org.example.api.support.SupportApiClient;
import org.example.config.ConfigManager;
import org.example.config.WorkspaceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.zip.GZIPOutputStream;

/**
 * Local workstation operational cache cleaner for Shared Cloud Client mode.
 *
 * <p>Applies the exact same retention settings persisted on the ERP server to this PC's
 * local {@code SharedClient} operational folders ({@code Exports}, {@code Exports/Diagnostics},
 * {@code Reports}, {@code Imports/Results}, {@code Temp}, and rotated {@code Logs}).
 * Strictly NEVER touches {@code Config}, {@code Documents}, {@code Attachments}, {@code Backups},
 * {@code Database}, or active log files, and is a no-op in Local Standalone mode where the
 * server owns the workspace directly.</p>
 */
public final class LocalClientStorageCleaner {
    private static final String PREF_LAST_CLEANUP_MS = "sharedClient.storage.lastCleanupEpochMs";
    private static final long DAILY_INTERVAL_MS = Duration.ofHours(24).toMillis();

    private LocalClientStorageCleaner() {}

    public record LocalUsage(
            boolean sharedClient,
            long exportsBytes,
            long reportsBytes,
            long logsBytes,
            long tempBytes,
            long documentsCacheBytes,
            long totalOperationalBytes
    ) {}

    public record LocalCleanupResult(
            boolean executed,
            int filesDeleted,
            int filesCompressed,
            long bytesReclaimed,
            String summary
    ) {}

    public static LocalUsage measureLocalUsage() {
        if (!ConfigManager.isSharedClient() || !WorkspaceManager.isConfigured()) {
            return new LocalUsage(false, 0, 0, 0, 0, 0, 0);
        }
        Path root = WorkspaceManager.getWorkspaceRoot().toAbsolutePath().normalize();
        long exports = directorySize(root, root.resolve("Exports"));
        long reports = directorySize(root, root.resolve("Reports"));
        long logs = directorySize(root, root.resolve("Logs"));
        long temp = directorySize(root, root.resolve("Temp"));
        long docsCache = directorySize(root, root.resolve("Documents"));
        long operational = exports + reports + logs + temp;
        return new LocalUsage(true, exports, reports, logs, temp, docsCache, operational);
    }

    public static void runDailyMaintenanceIfDue() {
        if (!ConfigManager.isSharedClient() || !WorkspaceManager.isConfigured()) return;
        Preferences prefs = Preferences.userNodeForPackage(LocalClientStorageCleaner.class);
        long lastRun = prefs.getLong(PREF_LAST_CLEANUP_MS, 0L);
        long now = System.currentTimeMillis();
        if (now - lastRun < DAILY_INTERVAL_MS) return;

        StorageApiClient.Policy policy = loadPolicyFromSettings();
        LocalCleanupResult result = cleanup(policy, false);
        if (result.executed()) {
            prefs.putLong(PREF_LAST_CLEANUP_MS, now);
        }
    }

    public static LocalCleanupResult cleanup(StorageApiClient.Policy policy, boolean dryRun) {
        if (!ConfigManager.isSharedClient() || !WorkspaceManager.isConfigured()) {
            return new LocalCleanupResult(false, 0, 0, 0, "Skipped (Local Standalone mode uses server cleanup)");
        }
        StorageApiClient.Policy effective = policy != null ? policy : loadPolicyFromSettings();
        Path workspace = WorkspaceManager.getWorkspaceRoot().toAbsolutePath().normalize();
        Counter counter = new Counter();

        cleanupTree(workspace, workspace.resolve("Reports"), effective.reportRetentionDays(), effective.diagnosticRetentionDays(), dryRun, counter, false);
        cleanupTree(workspace, workspace.resolve("Exports"), effective.exportRetentionDays(), effective.diagnosticRetentionDays(), dryRun, counter, true);
        cleanupTree(workspace, workspace.resolve("Imports/Results"), effective.importResultRetentionDays(), effective.diagnosticRetentionDays(), dryRun, counter, false);
        cleanupTree(workspace, workspace.resolve("Temp"), effective.tempRetentionDays(), effective.diagnosticRetentionDays(), dryRun, counter, false);
        cleanupLogs(workspace, workspace.resolve("Logs"), effective.logRetentionDays(), effective.compressLogs(), dryRun, counter);

        if (!dryRun) {
            Preferences.userNodeForPackage(LocalClientStorageCleaner.class)
                    .putLong(PREF_LAST_CLEANUP_MS, System.currentTimeMillis());
        }
        String summary = "Local PC: deleted " + counter.deleted + " files; compressed " + counter.compressed
                + "; reclaimed " + counter.reclaimed + " bytes" + (dryRun ? " (preview)" : "");
        return new LocalCleanupResult(true, counter.deleted, counter.compressed, counter.reclaimed, summary);
    }

    public static StorageApiClient.Policy loadPolicyFromSettings() {
        try {
            SupportApiClient support = new SupportApiClient();
            return new StorageApiClient.Policy(
                    boundedInt(support.setting("storage.logs.retentionDays", "30"), 30, 1, 3650),
                    boundedInt(support.setting("storage.reports.retentionDays", "365"), 365, 1, 3650),
                    boundedInt(support.setting("storage.exports.retentionDays", "90"), 90, 1, 3650),
                    boundedInt(support.setting("storage.diagnostics.retentionDays", "30"), 30, 1, 3650),
                    boundedInt(support.setting("storage.importResults.retentionDays", "90"), 90, 1, 3650),
                    boundedInt(support.setting("storage.temp.retentionDays", "7"), 7, 1, 365),
                    Boolean.parseBoolean(support.setting("storage.logs.compress", "true"))
            );
        } catch (Exception ignored) {
            return new StorageApiClient.Policy(30, 365, 90, 30, 90, 7, true);
        }
    }

    private static void cleanupTree(Path workspace, Path root, int days, int diagnosticDays, boolean dryRun, Counter counter, boolean diagnosticsAware) {
        if (!insideWorkspace(workspace, root) || !Files.isDirectory(root)) return;
        Path diagnosticsRoot = workspace.resolve("Exports/Diagnostics").normalize();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    int effectiveDays = diagnosticsAware && file.toAbsolutePath().normalize().startsWith(diagnosticsRoot)
                            ? diagnosticDays : days;
                    Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, effectiveDays)));
                    if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        delete(file, attrs.size(), dryRun, counter);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (!dryRun && !dir.equals(root)) {
                        try (var stream = Files.list(dir)) {
                            if (stream.findAny().isEmpty()) Files.deleteIfExists(dir);
                        } catch (IOException ignored) {}
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private static void cleanupLogs(Path workspace, Path root, int retentionDays, boolean compress, boolean dryRun, Counter counter) {
        if (!insideWorkspace(workspace, root) || !Files.isDirectory(root)) return;
        Instant deleteCutoff = Instant.now().minus(Duration.ofDays(Math.max(1, retentionDays)));
        Instant compressCutoff = Instant.now().minus(Duration.ofDays(1));
        List<Path> activeLogs = List.of(
                root.resolve("Desktop/desktop.log").toAbsolutePath().normalize(),
                root.resolve("Server/dse-erp-server.log").toAbsolutePath().normalize(),
                root.resolve("PostgreSQL/postgresql.log").toAbsolutePath().normalize()
        );
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    Path normalized = file.toAbsolutePath().normalize();
                    if (activeLogs.contains(normalized)) return FileVisitResult.CONTINUE;
                    Instant modified = attrs.lastModifiedTime().toInstant();
                    if (modified.isBefore(deleteCutoff)) {
                        delete(file, attrs.size(), dryRun, counter);
                    } else if (compress && modified.isBefore(compressCutoff) && !file.getFileName().toString().endsWith(".gz")) {
                        compress(file, dryRun, counter);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    private static void compress(Path file, boolean dryRun, Counter counter) throws IOException {
        long before = Files.size(file);
        if (dryRun) {
            counter.compressed++;
            return;
        }
        Path target = file.resolveSibling(file.getFileName() + ".gz");
        if (Files.exists(target)) return;
        try (InputStream in = Files.newInputStream(file);
             OutputStream raw = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
             GZIPOutputStream out = new GZIPOutputStream(raw)) {
            in.transferTo(out);
        }
        long after = Files.size(target);
        Files.deleteIfExists(file);
        counter.compressed++;
        counter.reclaimed += Math.max(0, before - after);
    }

    private static void delete(Path file, long bytes, boolean dryRun, Counter counter) throws IOException {
        counter.deleted++;
        counter.reclaimed += Math.max(0, bytes);
        if (!dryRun) Files.deleteIfExists(file);
    }

    private static long directorySize(Path workspace, Path root) {
        if (!insideWorkspace(workspace, root) || !Files.isDirectory(root)) return 0L;
        final long[] total = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
        return total[0];
    }

    private static boolean insideWorkspace(Path workspace, Path path) {
        return workspace != null && path != null && path.toAbsolutePath().normalize().startsWith(workspace);
    }

    private static int boundedInt(String value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class Counter {
        int deleted;
        int compressed;
        long reclaimed;
    }
}
