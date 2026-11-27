package org.example.chat;

import javafx.application.Platform;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.example.api.ApiSession;
import org.example.api.chat.TeamChatApiClient;
import org.example.api.chat.TeamChatApiClient.*;
import org.example.service.SessionService;
import org.example.util.DesktopLog;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Central real-time coordinator for MS Teams presence and Facebook Messenger-style
 * multi-window team chat across docked boxes and detached pop-out windows.
 */
public final class TeamChatManager {
    private static final TeamChatManager INSTANCE = new TeamChatManager();

    private final TeamChatApiClient api = new TeamChatApiClient();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> directoryListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<ChatStreamEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final Map<Long, Runnable> popoutBringToFront = new ConcurrentHashMap<>();

    private final LongProperty totalUnread = new SimpleLongProperty(0L);
    private final StringProperty selfStatus = new SimpleStringProperty("ONLINE");
    private final StringProperty selfCustomNote = new SimpleStringProperty("");

    private volatile DirectoryResponse latestDirectory;
    private volatile ScheduledExecutorService heartbeatExec;
    private volatile ChatWindowHost dockHost;
    private volatile java.io.InputStream activeSseStream;
    private volatile long lastDirectoryRefreshMs = 0L;
    private volatile boolean manualOffline = false;
    private volatile boolean initialPresenceSyncPending = false;

    public interface ChatWindowHost {
        void openDockedChannel(ChannelSummaryDto channel);
        void closePopoutOrRestore(ChannelSummaryDto channel);
        void closeAllWindows();
        void refreshAllTheme();
    }

    private TeamChatManager() {}

    public static TeamChatManager getInstance() {
        return INSTANCE;
    }

    public TeamChatApiClient api() {
        return api;
    }

    public LongProperty totalUnreadProperty() { return totalUnread; }
    public StringProperty selfStatusProperty() { return selfStatus; }
    public StringProperty selfCustomNoteProperty() { return selfCustomNote; }

    public DirectoryResponse latestDirectory() {
        return latestDirectory;
    }

    public void attachDockHost(ChatWindowHost host) {
        this.dockHost = host;
    }

    public void registerPopout(long channelId, Runnable closeOrFocusHook) {
        if (closeOrFocusHook != null) popoutBringToFront.put(channelId, closeOrFocusHook);
    }

    public void unregisterPopout(long channelId) {
        popoutBringToFront.remove(channelId);
    }

    public boolean isPoppedOut(long channelId) {
        return popoutBringToFront.containsKey(channelId);
    }

    public void start() {
        if (!ApiSession.isEstablished() || SessionService.current() == null) return;
        if (!running.compareAndSet(false, true)) {
            refreshDirectoryAsync();
            return;
        }
        manualOffline = false;
        initialPresenceSyncPending = true;
        refreshDirectoryAsync();

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "team-chat-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExec = exec;
        exec.scheduleAtFixedRate(() -> {
            if (!running.get() || !ApiSession.isEstablished()) return;
            try {
                api.heartbeat();
            } catch (Exception ex) {
                DesktopLog.warn("TeamChatManager", "HEARTBEAT", ex.getMessage());
            }
        }, 25, 25, TimeUnit.SECONDS);

        Thread.ofVirtual().name("team-chat-sse").start(this::runSseLoop);
    }

    public void stop() {
        boolean wasRunning = running.getAndSet(false);
        java.io.InputStream stream = activeSseStream;
        activeSseStream = null;
        if (stream != null) {
            try { stream.close(); } catch (Exception ignored) {}
        }
        ScheduledExecutorService exec = heartbeatExec;
        heartbeatExec = null;
        if (exec != null) exec.shutdownNow();

        if (wasRunning && ApiSession.isEstablished()) {
            Thread.ofVirtual().name("team-chat-offline").start(() -> {
                try {
                    api.updatePresence("OFFLINE", null);
                } catch (Exception ignored) {}
            });
        }
        Platform.runLater(() -> {
            if (dockHost != null) dockHost.closeAllWindows();
            totalUnread.set(0);
        });
        popoutBringToFront.clear();
        latestDirectory = null;
    }

    public void openChannel(ChannelSummaryDto channel) {
        if (channel == null) return;
        Runnable popoutFocus = popoutBringToFront.get(channel.id());
        if (popoutFocus != null) {
            Platform.runLater(popoutFocus);
            return;
        }
        if (dockHost != null) {
            Platform.runLater(() -> dockHost.openDockedChannel(channel));
        }
    }

    public void openDirectWithUser(int userId) {
        Thread.ofVirtual().name("chat-open-dm").start(() -> {
            try {
                ChannelSummaryDto ch = api.openDirect(userId);
                refreshDirectoryAsync();
                openChannel(ch);
            } catch (Exception ex) {
                DesktopLog.warn("TeamChatManager", "OPEN_DM", ex.getMessage());
            }
        });
    }

    public void refreshDirectoryAsync() {
        if (!ApiSession.isEstablished()) return;
        Thread.ofVirtual().name("chat-directory-refresh").start(() -> {
            try {
                DirectoryResponse dir = api.directory();
                latestDirectory = dir;
                if (initialPresenceSyncPending && !manualOffline
                        && dir.selfPresence() != null
                        && "OFFLINE".equalsIgnoreCase(dir.selfPresence().status())) {
                    initialPresenceSyncPending = false;
                    updateSelfPresenceAsync("ONLINE", dir.selfPresence().customNote());
                } else {
                    initialPresenceSyncPending = false;
                }
                Platform.runLater(() -> {
                    totalUnread.set(dir.totalUnread());
                    if (dir.selfPresence() != null) {
                        selfStatus.set(dir.selfPresence().status());
                        selfCustomNote.set(dir.selfPresence().customNote() == null ? "" : dir.selfPresence().customNote());
                    }
                    notifyDirectoryListeners();
                });
            } catch (Exception ex) {
                DesktopLog.warn("TeamChatManager", "DIRECTORY_SYNC", ex.getMessage());
            }
        });
    }

    public void updateSelfPresenceAsync(String status, String customNote) {
        updateSelfPresenceAsync(status, customNote, null, null);
    }

    public void updateSelfPresenceAsync(String status, String customNote, Runnable onSuccess, Consumer<String> onError) {
        final String targetStatus = (status == null || status.isBlank()) ? "ONLINE" : status.trim().toUpperCase(Locale.ROOT);
        manualOffline = "OFFLINE".equals(targetStatus);
        initialPresenceSyncPending = false;
        Thread.ofVirtual().name("chat-presence-update").start(() -> {
            try {
                PresenceDto updated = api.updatePresence(targetStatus, customNote);
                Platform.runLater(() -> {
                    selfStatus.set(updated.status());
                    selfCustomNote.set(updated.customNote() == null ? "" : updated.customNote());
                    applyPresencePatchInMemory(updated);
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() == null || ex.getMessage().isBlank() ? "Failed to update status." : ex.getMessage();
                DesktopLog.warn("TeamChatManager", "PRESENCE_UPDATE", msg);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(msg));
                }
            }
        });
    }

    public void markChannelReadAsync(long channelId, long lastMessageId) {
        if (lastMessageId <= 0) return;
        Thread.ofVirtual().name("chat-mark-read").start(() -> {
            try {
                api.markRead(channelId, lastMessageId);
                refreshDirectoryAsync();
            } catch (Exception ignored) {}
        });
    }

    public void refreshThemeEverywhere() {
        Platform.runLater(() -> {
            if (dockHost != null) dockHost.refreshAllTheme();
        });
    }

    public void addDirectoryListener(Runnable listener) {
        if (listener != null) directoryListeners.addIfAbsent(listener);
    }

    public void removeDirectoryListener(Runnable listener) {
        directoryListeners.remove(listener);
    }

    public void addEventListener(Consumer<ChatStreamEvent> listener) {
        if (listener != null) eventListeners.addIfAbsent(listener);
    }

    public void removeEventListener(Consumer<ChatStreamEvent> listener) {
        eventListeners.remove(listener);
    }

    private void runSseLoop() {
        long backoffMs = 3000L;
        while (running.get() && ApiSession.isEstablished()) {
            try {
                api.consumeEventStream(this::dispatchStreamEvent, running::get, s -> {
                    activeSseStream = s;
                });
                backoffMs = 3000L;
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (msg.contains("HTTP 401") || msg.contains("HTTP 403") || ex instanceof org.example.api.ApiSession.AuthenticationRequiredException) {
                    running.set(false);
                    break;
                }
                if (running.get()) {
                    DesktopLog.warn("TeamChatManager", "SSE_RECONNECT", msg);
                }
                backoffMs = Math.min(30_000L, backoffMs * 2);
            } finally {
                activeSseStream = null;
            }
            if (!running.get() || !ApiSession.isEstablished()) break;
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void dispatchStreamEvent(ChatStreamEvent ev) {
        if (ev == null) return;
        if ("PRESENCE".equals(ev.eventType()) && ev.presence() != null) {
            applyPresencePatchInMemory(ev.presence());
        } else if ("DIRECTORY".equals(ev.eventType()) || "MESSAGE".equals(ev.eventType()) || "MESSAGE_UPDATED".equals(ev.eventType())) {
            long now = System.currentTimeMillis();
            if (now - lastDirectoryRefreshMs >= 800L) {
                lastDirectoryRefreshMs = now;
                refreshDirectoryAsync();
            }
        }
        Platform.runLater(() -> {
            for (Consumer<ChatStreamEvent> l : eventListeners) {
                try {
                    l.accept(ev);
                } catch (Exception ex) {
                    DesktopLog.warn("TeamChatManager", "EVENT_LISTENER", ex.getMessage());
                }
            }
        });
    }

    private void applyPresencePatchInMemory(PresenceDto updated) {
        DirectoryResponse cur = latestDirectory;
        if (cur == null || updated == null) return;
        PresenceDto nextSelf = cur.selfPresence();
        if (nextSelf != null && nextSelf.userId() == updated.userId()) {
            nextSelf = updated;
        }
        List<PresenceDto> users = cur.users().stream()
                .map(u -> u.userId() == updated.userId() ? updated : u)
                .toList();
        List<ChannelSummaryDto> channels = cur.channels().stream()
                .map(c -> ("DIRECT".equals(c.channelType()) && Objects.equals(c.peerUserId(), updated.userId()))
                        ? new ChannelSummaryDto(c.id(), c.channelType(), c.channelKey(), c.name(), c.unreadCount(),
                                c.lastMessagePreview(), c.lastMessageUtc(), c.peerUserId(), updated.status(),
                                updated.customNote(), c.members())
                        : c)
                .toList();
        latestDirectory = new DirectoryResponse(nextSelf, users, channels, cur.totalUnread());
        Platform.runLater(this::notifyDirectoryListeners);
    }

    private void notifyDirectoryListeners() {
        for (Runnable r : directoryListeners) {
            try {
                r.run();
            } catch (Exception ignored) {}
        }
    }

    public static String presenceCssClass(String status) {
        return switch (status == null ? "OFFLINE" : status.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> "chat-dot-online";
            case "AWAY" -> "chat-dot-away";
            case "BUSY" -> "chat-dot-busy";
            default -> "chat-dot-offline";
        };
    }

    public static String presenceLabel(String status) {
        return switch (status == null ? "OFFLINE" : status.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> "Available";
            case "AWAY" -> "Away";
            case "BUSY" -> "Busy";
            default -> "Offline";
        };
    }
}
