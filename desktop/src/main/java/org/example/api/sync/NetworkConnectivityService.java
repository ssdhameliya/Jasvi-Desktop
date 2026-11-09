package org.example.api.sync;

import javafx.application.Platform;
import org.example.util.DesktopLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service to observe network reachability to the central cloud ERP.
 * Non-blocking: probes run on virtual threads and dispatch state updates to the JavaFX Application Thread.
 */
public final class NetworkConnectivityService {

    public enum ConnectionState {
        ONLINE,
        OFFLINE,
        CHECKING
    }

    private static final NetworkConnectivityService INSTANCE = new NetworkConnectivityService();

    private final SyncApiClient apiClient = new SyncApiClient();
    private final List<Consumer<ConnectionState>> listeners = new CopyOnWriteArrayList<>();
    private volatile ConnectionState currentState = ConnectionState.CHECKING;
    private ScheduledExecutorService scheduler;

    private NetworkConnectivityService() {}

    public static NetworkConnectivityService getInstance() {
        return INSTANCE;
    }

    public synchronized void startMonitoring() {
        if (scheduler != null && !scheduler.isShutdown()) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("dse-net-monitor").unstarted(r);
            t.setDaemon(true);
            return t;
        });

        // Probe every 20 seconds
        scheduler.scheduleWithFixedDelay(this::probeConnection, 2, 20, TimeUnit.SECONDS);
    }

    public synchronized void stopMonitoring() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public void checkNowAsync() {
        Thread.ofVirtual().name("dse-net-probe-now").start(this::probeConnection);
    }

    private void probeConnection() {
        try {
            boolean reachable = apiClient.isCloudReachable();
            ConnectionState newState = reachable ? ConnectionState.ONLINE : ConnectionState.OFFLINE;
            if (newState != currentState) {
                currentState = newState;
                notifyListeners(newState);
            }
        } catch (Exception e) {
            DesktopLog.warn("NetworkConnectivityService", "PROBE_FAILED", "Probe error: " + e.getMessage());
            if (currentState != ConnectionState.OFFLINE) {
                currentState = ConnectionState.OFFLINE;
                notifyListeners(ConnectionState.OFFLINE);
            }
        }
    }

    private void notifyListeners(ConnectionState state) {
        for (Consumer<ConnectionState> listener : listeners) {
            try {
                if (Platform.isFxApplicationThread()) {
                    listener.accept(state);
                } else {
                    Platform.runLater(() -> listener.accept(state));
                }
            } catch (Exception e) {
                DesktopLog.warn("NetworkConnectivityService", "LISTENER_ERROR", "Error notifying listener: " + e.getMessage());
            }
        }
    }

    public ConnectionState getCurrentState() {
        return currentState;
    }

    public void addListener(Consumer<ConnectionState> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            listener.accept(currentState);
        }
    }

    public void removeListener(Consumer<ConnectionState> listener) {
        listeners.remove(listener);
    }
}
