package org.example.service;

import javafx.application.Platform;
import javafx.scene.Node;
import org.example.api.ApiSession;
import org.example.api.admin.AdminApiClient;
import org.example.config.WorkspaceManager;
import org.example.util.DesktopLog;
import org.example.util.ShellIndicatorBus;
import org.example.util.AppDialogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Background Diagnostic & Defect Auto-Scanner.
 *
 * <p>Proactively scans local desktop and server platform error logs on a background
 * 30-second cadence. Synchronizes uninspected critical defect counts to the top-bar
 * badge and surfaces real-time diagnostic alerts without blocking the JavaFX thread.</p>
 */
public final class DiagnosticAutoScanner {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticAutoScanner.class);
    private static final Pattern SPRING_BOOT_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+(\\w+)\\s+(?:\\d+\\s+---\\s+)?(?:\\[(.*?)\\]\\s+)?\\[(.*?)\\]\\s+([^:]+?)\\s+:\\s+(.*)$"
    );
    private static final Pattern LEGACY_SERVER_LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s+\\[(.*?)\\]\\s+(\\w+)\\s+([\\w\\.]+)\\s+-\\s+(.*)$"
    );
    private static final Pattern JSON_TIME_PATTERN = Pattern.compile("\"time\":\"([^\"]+)\"");
    private static final Pattern JSON_LEVEL_PATTERN = Pattern.compile("\"level\":\"([^\"]+)\"");

    private static final Preferences PREFS = Preferences.userNodeForPackage(DiagnosticAutoScanner.class);
    private static final String PREF_KEY_LAST_INSPECTED = "dse.diagnostics.last_inspected_time";

    private static final AtomicInteger UNINSPECTED_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean INITIAL_SCAN_DONE = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService scheduler;
    private static volatile Instant lastInspectedInstant = loadLastInspected();
    private static volatile int lastAlertedCount = 0;

    private DiagnosticAutoScanner() {}

    private static Instant loadLastInspected() {
        try {
            long epoch = PREFS.getLong(PREF_KEY_LAST_INSPECTED, 0L);
            if (epoch > 0) return Instant.ofEpochMilli(epoch);
        } catch (Exception ignored) {}
        return Instant.now().minusSeconds(3600); // Default to last 1 hour
    }

    public static synchronized void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DiagnosticAutoScanner-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(DiagnosticAutoScanner::scan, 5, 30, TimeUnit.SECONDS);
        log.info("DiagnosticAutoScanner: Autonomous error scanner started (30s cadence).");
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("DiagnosticAutoScanner: Autonomous error scanner stopped.");
        }
    }

    public static int getUninspectedCount() {
        int memoryCount = DesktopLog.getUninspectedErrorCount();
        int scannerCount = UNINSPECTED_COUNT.get();
        return Math.max(memoryCount, scannerCount);
    }

    public static void markAllInspected() {
        lastInspectedInstant = Instant.now();
        UNINSPECTED_COUNT.set(0);
        DesktopLog.clearUninspectedErrors();
        lastAlertedCount = 0;
        try {
            PREFS.putLong(PREF_KEY_LAST_INSPECTED, lastInspectedInstant.toEpochMilli());
        } catch (Exception ignored) {}
        Platform.runLater(ShellIndicatorBus::publish);
    }

    private static void scan() {
        try {
            int desktopErrors = scanDesktopLog();
            int serverErrors = scanServerLog();
            int total = desktopErrors + serverErrors;

            UNINSPECTED_COUNT.set(total);

            if (!INITIAL_SCAN_DONE.getAndSet(true)) {
                lastAlertedCount = total;
                Platform.runLater(ShellIndicatorBus::publish);
            } else if (total > lastAlertedCount) {
                int newErrors = total - lastAlertedCount;
                lastAlertedCount = total;
                Platform.runLater(() -> {
                    ShellIndicatorBus.publish();
                    AppDialogService.error((Node) null, "System Diagnostic Alert",
                            "Detected " + newErrors + " new system/server error(s). Click Diagnostics icon to inspect.");
                });
                try {
                    NotificationService.createNotification(
                            NotificationService.Category.SYSTEM,
                            "System Diagnostic Alert",
                            "Autonomous scanner detected " + newErrors + " new uninspected system/server error(s). Click the Diagnostics icon to inspect root cause.",
                            "ERROR",
                            null,
                            null
                    );
                } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            log.debug("DiagnosticAutoScanner: Scan tick skipped: {}", t.getMessage());
        }
    }

    private static int scanDesktopLog() {
        try {
            Path file = DesktopLog.path();
            if (file == null || !Files.exists(file)) {
                file = WorkspaceManager.isConfigured()
                        ? WorkspaceManager.getDesktopLogsFolder().resolve("desktop.log")
                        : Path.of(System.getProperty("user.home", "."), ".dse-erp", "Logs", "Desktop", "desktop.log");
            }
            if (!Files.exists(file)) return 0;

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int count = 0;
            int start = Math.max(0, lines.size() - 200);

            for (int i = lines.size() - 1; i >= start; i--) {
                String line = lines.get(i).trim();
                if (!line.contains("\"ERROR\"")) continue;

                Matcher mLevel = JSON_LEVEL_PATTERN.matcher(line);
                if (mLevel.find() && "ERROR".equalsIgnoreCase(mLevel.group(1))) {
                    Matcher mTime = JSON_TIME_PATTERN.matcher(line);
                    if (mTime.find()) {
                        try {
                            Instant eventTime = parseTimestamp(mTime.group(1));
                            if (eventTime.isAfter(lastInspectedInstant)) {
                                count++;
                            }
                        } catch (Exception ignored) {
                            count++;
                        }
                    } else {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int scanServerLog() {
        // 1. Live server diagnostic REST polling when authenticated
        if (ApiSession.isEstablished()) {
            try {
                AdminApiClient adminApi = new AdminApiClient();
                List<AdminApiClient.ServerLogEntry> remote = adminApi.serverDiagnostics(50);
                if (remote != null && !remote.isEmpty()) {
                    int count = 0;
                    for (AdminApiClient.ServerLogEntry entry : remote) {
                        if ("ERROR".equalsIgnoreCase(entry.level())) {
                            String sTime = entry.time();
                            if (sTime != null && !sTime.isBlank()) {
                                try {
                                    Instant t = parseTimestamp(sTime);
                                    if (t.isAfter(lastInspectedInstant)) count++;
                                } catch (Exception ignored) {
                                    count++;
                                }
                            } else {
                                count++;
                            }
                        }
                    }
                    return count;
                }
            } catch (Exception ignored) {
                // Fall back to local file
            }
        }

        // 2. Local file fallback
        try {
            Path serverLog = WorkspaceManager.isConfigured()
                    ? WorkspaceManager.getServerLogsFolder().resolve("dse-erp-server.log")
                    : Path.of("D:", "DSE Production", "Logs", "Server", "dse-erp-server.log");

            if (!Files.exists(serverLog)) return 0;

            List<String> lines = Files.readAllLines(serverLog, StandardCharsets.UTF_8);
            int count = 0;
            int start = Math.max(0, lines.size() - 300);

            for (int i = lines.size() - 1; i >= start; i--) {
                String line = lines.get(i).trim();
                if (!line.contains(" ERROR ")) continue;

                Matcher mSpring = SPRING_BOOT_LOG_PATTERN.matcher(line);
                Matcher mLegacy = !mSpring.matches() ? LEGACY_SERVER_LOG_PATTERN.matcher(line) : null;
                if (mSpring.matches() || (mLegacy != null && mLegacy.matches())) {
                    String sTime = mSpring.matches() ? mSpring.group(1) : mLegacy.group(1);
                    String sLevel = mSpring.matches() ? mSpring.group(2) : mLegacy.group(3);
                    if ("ERROR".equalsIgnoreCase(sLevel)) {
                        try {
                            Instant time = parseTimestamp(sTime);
                            if (time.isAfter(lastInspectedInstant)) {
                                count++;
                            }
                        } catch (Exception ignored) {
                            count++;
                        }
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(raw);
        } catch (Exception e1) {
            try {
                String iso = raw.trim().replace(' ', 'T');
                if (!iso.contains("+") && !iso.endsWith("Z") && !iso.matches(".*-\\d{2}:\\d{2}$")) {
                    return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant();
                }
                return Instant.parse(iso);
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }
}
