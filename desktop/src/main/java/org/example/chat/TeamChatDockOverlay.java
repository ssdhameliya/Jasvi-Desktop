package org.example.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.example.api.chat.TeamChatApiClient.ChannelSummaryDto;
import org.example.api.chat.TeamChatApiClient.ChatStreamEvent;
import org.example.api.chat.TeamChatApiClient.DirectoryResponse;
import org.example.service.SessionService;
import org.example.util.IconFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Facebook Messenger-style bottom-right multi-window chat dock overlay.
 * Allows opening and chatting in multiple independent conversations simultaneously
 * over any ERP screen, with minimize pills and pop-out OS windows.
 */
public final class TeamChatDockOverlay implements TeamChatManager.ChatWindowHost {
    private static final int MAX_VISIBLE_WINDOWS = 3;

    private final StackPane hostLayer;
    private final HBox dockRow = new HBox(10);
    private final Map<Long, DockedSlot> openSlots = new LinkedHashMap<>();
    private final Consumer<String> onOpenErpRecord;
    private final TeamChatManager manager = TeamChatManager.getInstance();

    private static final class DockedSlot {
        ChannelSummaryDto channel;
        boolean minimized;
        TeamChatWindowPane pane;
        VBox minimizedPill;
        Circle pillDot;
        Label pillBadge;
    }

    public TeamChatDockOverlay(StackPane hostLayer, Consumer<String> onOpenErpRecord) {
        this.hostLayer = hostLayer;
        this.onOpenErpRecord = onOpenErpRecord;
        hostLayer.setPickOnBounds(false);
        hostLayer.setAlignment(Pos.BOTTOM_RIGHT);
        dockRow.setAlignment(Pos.BOTTOM_RIGHT);
        dockRow.setPickOnBounds(false);
        dockRow.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dockRow.setPadding(new Insets(0, 14, 6, 14));
        StackPane.setAlignment(dockRow, Pos.BOTTOM_RIGHT);
        hostLayer.getChildren().setAll(dockRow);

        manager.attachDockHost(this);
        manager.addEventListener(this::onStreamEvent);
        manager.addDirectoryListener(() -> Platform.runLater(this::refreshMinimizedBadges));
    }

    @Override
    public void openDockedChannel(ChannelSummaryDto channel) {
        if (channel == null) return;
        DockedSlot existing = openSlots.get(channel.id());
        if (existing != null) {
            existing.channel = channel;
            existing.minimized = false;
            enforceExpandedWindowLimit(channel.id());
            rebuildDock();
            existing.pane.focusInput();
            return;
        }

        DockedSlot slot = new DockedSlot();
        slot.channel = channel;
        slot.minimized = false;
        slot.pane = new TeamChatWindowPane(
                channel,
                false,
                () -> closeSlot(channel.id()),
                () -> minimizeSlot(channel.id()),
                () -> popoutSlot(channel.id()),
                onOpenErpRecord
        );
        slot.minimizedPill = buildMinimizedPill(slot);
        openSlots.put(channel.id(), slot);
        enforceExpandedWindowLimit(channel.id());
        rebuildDock();
        slot.pane.focusInput();
    }

    @Override
    public void closePopoutOrRestore(ChannelSummaryDto channel) {
        openDockedChannel(channel);
    }

    @Override
    public void closeAllWindows() {
        for (DockedSlot s : openSlots.values()) {
            if (s.pane != null) s.pane.dispose();
        }
        openSlots.clear();
        dockRow.getChildren().clear();
    }

    @Override
    public void refreshAllTheme() {
        rebuildDock();
    }

    private void onStreamEvent(ChatStreamEvent ev) {
        if (ev == null || !"MESSAGE".equals(ev.eventType()) || ev.message() == null) return;
        var current = SessionService.current();
        if (current != null && current.getId() == ev.message().senderId()) return;
        long cid = ev.message().channelId();
        if (manager.isPoppedOut(cid)) return;

        DockedSlot slot = openSlots.get(cid);
        if (slot != null) {
            if (slot.minimized) refreshMinimizedBadges();
            return;
        }
        // Automatically pop up a compact minimized chat pill for incoming message unless user is BUSY
        if ("BUSY".equalsIgnoreCase(manager.selfStatusProperty().get())) return;
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) return;
        for (ChannelSummaryDto ch : dir.channels()) {
            if (ch.id() == cid) {
                DockedSlot incoming = new DockedSlot();
                incoming.channel = ch;
                incoming.minimized = true;
                incoming.pane = new TeamChatWindowPane(
                        ch, false,
                        () -> closeSlot(cid),
                        () -> minimizeSlot(cid),
                        () -> popoutSlot(cid),
                        onOpenErpRecord
                );
                incoming.minimizedPill = buildMinimizedPill(incoming);
                openSlots.put(cid, incoming);
                rebuildDock();
                break;
            }
        }
    }

    private void enforceExpandedWindowLimit(long keepExpandedId) {
        long expandedCount = openSlots.values().stream().filter(s -> !s.minimized).count();
        if (expandedCount <= MAX_VISIBLE_WINDOWS) return;
        for (DockedSlot s : openSlots.values()) {
            if (s.channel.id() != keepExpandedId && !s.minimized) {
                s.minimized = true;
                if (--expandedCount <= MAX_VISIBLE_WINDOWS) break;
            }
        }
    }

    private void minimizeSlot(long channelId) {
        DockedSlot s = openSlots.get(channelId);
        if (s != null) {
            s.minimized = true;
            rebuildDock();
        }
    }

    private void closeSlot(long channelId) {
        DockedSlot removed = openSlots.remove(channelId);
        if (removed != null && removed.pane != null) removed.pane.dispose();
        rebuildDock();
    }

    private void popoutSlot(long channelId) {
        DockedSlot s = openSlots.remove(channelId);
        if (s == null) return;
        ChannelSummaryDto ch = s.channel;
        if (s.pane != null) s.pane.dispose();
        rebuildDock();
        TeamChatPopoutWindow.open(ch, onOpenErpRecord, this::openDockedChannel);
    }

    private void rebuildDock() {
        List<Node> nodes = new ArrayList<>();
        for (DockedSlot s : openSlots.values()) {
            nodes.add(s.minimized ? s.minimizedPill : s.pane);
        }
        dockRow.getChildren().setAll(nodes);
        refreshMinimizedBadges();
    }

    private VBox buildMinimizedPill(DockedSlot slot) {
        slot.pillDot = new Circle(4.5);
        slot.pillDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(slot.channel.peerStatus()));
        Label lbl = new Label("GROUP".equals(slot.channel.channelType()) ? "#" + slot.channel.name() : slot.channel.name());
        lbl.getStyleClass().add("chat-pill-title");
        slot.pillBadge = new Label("0");
        slot.pillBadge.getStyleClass().add("chat-unread-pill");
        slot.pillBadge.setVisible(false);
        slot.pillBadge.setManaged(false);

        Button btnClose = new Button();
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 11));
        btnClose.getStyleClass().addAll("approved-button", "chat-icon-btn");
        btnClose.setOnAction(e -> { e.consume(); closeSlot(slot.channel.id()); });

        HBox row = new HBox(7,
                "DIRECT".equals(slot.channel.channelType()) ? slot.pillDot : IconFactory.compactIcon("communication", 13),
                lbl,
                slot.pillBadge,
                new Region(),
                btnClose);
        HBox.setHgrow(row.getChildren().get(3), Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("chat-minimized-pill");

        VBox box = new VBox(row);
        box.setAlignment(Pos.BOTTOM_CENTER);
        box.setPickOnBounds(false);
        row.setOnMouseClicked(e -> {
            if (e.getTarget() == btnClose) return;
            openDockedChannel(slot.channel);
        });
        return box;
    }

    private void refreshMinimizedBadges() {
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) return;
        for (DockedSlot s : openSlots.values()) {
            for (ChannelSummaryDto ch : dir.channels()) {
                if (ch.id() == s.channel.id()) {
                    s.channel = ch;
                    if (s.pillDot != null) {
                        s.pillDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(ch.peerStatus()));
                    }
                    if (s.pillBadge != null) {
                        boolean hasUnread = ch.unreadCount() > 0;
                        s.pillBadge.setText(String.valueOf(ch.unreadCount()));
                        s.pillBadge.setVisible(hasUnread);
                        s.pillBadge.setManaged(hasUnread);
                    }
                    break;
                }
            }
        }
    }
}
