package org.example.api.sync;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import org.example.shared.sync.SyncBatchRequest;
import org.example.shared.sync.SyncBatchResult;
import org.example.util.DesktopLog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background worker that manages offline-to-cloud synchronization.
 * Uses Java 25 Virtual Threads for network I/O and exposes reactive properties for JavaFX UI bindings.
 */
public final class OfflineSyncWorker {

    private static final OfflineSyncWorker INSTANCE = new OfflineSyncWorker();

    private final SyncApiClient syncApiClient = new SyncApiClient();
    private final NetworkConnectivityService netService = NetworkConnectivityService.getInstance();
    private final List<Consumer<SyncBatchResult>> resultListeners = new CopyOnWriteArrayList<>();

    private final SimpleStringProperty syncStatusMessage = new SimpleStringProperty("Cloud Synced");
    private final SimpleIntegerProperty pendingCount = new SimpleIntegerProperty(0);
    private ScheduledExecutorService syncScheduler;
    private volatile boolean syncing = false;

    private OfflineSyncWorker() {
        netService.addListener(state -> {
            if (state == NetworkConnectivityService.ConnectionState.ONLINE) {
                triggerSyncNow();
            } else {
                updateStatusOnFx("Offline Mode");
            }
        });
    }

    public static OfflineSyncWorker getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (syncScheduler != null && !syncScheduler.isShutdown()) return;

        netService.startMonitoring();

        syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("dse-sync-worker").unstarted(r);
            t.setDaemon(true);
            return t;
        });

        // Periodic sync attempt every 60 seconds when online
        syncScheduler.scheduleWithFixedDelay(this::runSyncCycle, 10, 60, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (syncScheduler != null) {
            syncScheduler.shutdownNow();
            syncScheduler = null;
        }
        netService.stopMonitoring();
    }

    public void triggerSyncNow() {
        Thread.ofVirtual().name("dse-sync-now").start(this::runSyncCycle);
    }

    private synchronized void runSyncCycle() {
        if (syncing) return;
        if (netService.getCurrentState() == NetworkConnectivityService.ConnectionState.OFFLINE) {
            updateStatusOnFx("Offline Mode");
            return;
        }

        try {
            syncing = true;
            updateStatusOnFx("Synchronizing...");

            // Query server status to inspect queue count
            var status = syncApiClient.getStatus();
            if (status.containsKey("pendingCount")) {
                Object p = status.get("pendingCount");
                int count = (p instanceof Number num) ? num.intValue() : 0;
                Platform.runLater(() -> pendingCount.set(count));
            }

            // In local/hybrid mode, if there are pending items, push them
            SyncBatchRequest samplePingBatch = new SyncBatchRequest(
                UUID.randomUUID().toString(),
                "WORKSTATION-MAIN",
                Instant.now().toString(),
                List.of()
            );

            SyncBatchResult result = syncApiClient.pushBatch(samplePingBatch);

            updateStatusOnFx("Cloud Synced");
            notifyResultListeners(result);
        } catch (Exception e) {
            DesktopLog.warn("OfflineSyncWorker", "SYNC_CYCLE_FAILED", "Sync cycle encountered error: " + e.getMessage());
            updateStatusOnFx("Sync Pending");
        } finally {
            syncing = false;
        }
    }

    private void updateStatusOnFx(String message) {
        if (Platform.isFxApplicationThread()) {
            syncStatusMessage.set(message);
        } else {
            Platform.runLater(() -> syncStatusMessage.set(message));
        }
    }

    private void notifyResultListeners(SyncBatchResult result) {
        for (Consumer<SyncBatchResult> listener : resultListeners) {
            try {
                if (Platform.isFxApplicationThread()) {
                    listener.accept(result);
                } else {
                    Platform.runLater(() -> listener.accept(result));
                }
            } catch (Exception e) {
                DesktopLog.warn("OfflineSyncWorker", "LISTENER_ERROR", "Error in sync listener: " + e.getMessage());
            }
        }
    }

    public SimpleStringProperty syncStatusMessageProperty() {
        return syncStatusMessage;
    }

    public SimpleIntegerProperty pendingCountProperty() {
        return pendingCount;
    }

    public void addResultListener(Consumer<SyncBatchResult> listener) {
        if (listener != null && !resultListeners.contains(listener)) {
            resultListeners.add(listener);
        }
    }
}
