package org.example.service;

import javafx.application.Platform;
import org.example.config.WorkspaceManager;
import org.example.util.DesktopLog;
import org.example.util.ShellIndicatorBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final Pattern SPRING_ERROR_PATTERN = Pattern.compile("^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+[+-][0-9]{2}:[0-9]{2})\\s+ERROR\\b");
    private static final Pattern JSON_TIME_PATTERN = Pattern.compile("\"time\":\"([^\"]+)\"");
    private static final Pattern JSON_LEVEL_PATTERN = Pattern.compile("\"level\":\"([^\"]+)\"");

    private static final Preferences PREFS = Preferences.userNodeForPackage(DiagnosticAutoScanner.class);
    private static final String PREF_KEY_LAST_INSPECTED = "dse.diagnostics.last_inspected_time";

    private static final AtomicInteger UNINSPECTED_COUNT = new AtomicInteger(0);
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

            if (total > lastAlertedCount) {
                lastAlertedCount = total;
                Platform.runLater(ShellIndicatorBus::publish);
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
                            Instant eventTime = Instant.parse(mTime.group(1));
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

                Matcher m = SPRING_ERROR_PATTERN.matcher(line);
                if (m.find()) {
                    try {
                        Instant time = Instant.parse(m.group(1));
                        if (time.isAfter(lastInspectedInstant)) {
                            count++;
                        }
                    } catch (Exception ignored) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
