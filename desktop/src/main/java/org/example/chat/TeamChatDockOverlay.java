package org.example.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.example.api.chat.TeamChatApiClient.ChannelSummaryDto;
import org.example.api.chat.TeamChatApiClient.ChatStreamEvent;
import org.example.api.chat.TeamChatApiClient.DirectoryResponse;
import org.example.service.BrandingService;
import org.example.service.SessionService;
import org.example.util.IconFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Facebook Messenger-style bottom-right multi-window chat dock overlay with a moveable
 * floating Jasvi logo launcher (live presence dot + unread badge).
 */
public final class TeamChatDockOverlay implements TeamChatManager.ChatWindowHost {
    private static final int MAX_VISIBLE_WINDOWS = 3;
    private static final double FAB_SIZE = 74.0;
    private static final double FAB_RIGHT_MARGIN = 18.0;
    private static final double FAB_BOTTOM_MARGIN = 14.0;
    private static final double DRAG_THRESHOLD_PX = 4.0;

    private final StackPane hostLayer;
    private final TeamChatRosterPopup rosterPopup;
    private final HBox dockRow = new HBox(10);
    private final StackPane launcherWrap = new StackPane();
    private final Button btnFloatingChat = new Button();
    private final Circle selfPresenceDot = new Circle(8.0);
    private final Label unreadBadge = new Label("0");
    private final Map<Long, DockedSlot> openSlots = new LinkedHashMap<>();
    private final Consumer<String> onOpenErpRecord;
    private final TeamChatManager manager = TeamChatManager.getInstance();

    private double dragPressSceneX;
    private double dragPressSceneY;
    private double dragStartTx;
    private double dragStartTy;
    private boolean draggingLauncher;
    private boolean suppressNextClick;

    private static final class DockedSlot {
        ChannelSummaryDto channel;
        boolean minimized;
        TeamChatWindowPane pane;
        VBox minimizedPill;
        Circle pillDot;
        Label pillBadge;
    }

    public TeamChatDockOverlay(StackPane hostLayer, Consumer<String> onOpenErpRecord) {
        this(hostLayer, new TeamChatRosterPopup(), onOpenErpRecord);
    }

    public TeamChatDockOverlay(StackPane hostLayer, TeamChatRosterPopup rosterPopup, Consumer<String> onOpenErpRecord) {
        this.hostLayer = hostLayer;
        this.rosterPopup = rosterPopup;
        this.onOpenErpRecord = onOpenErpRecord;
        hostLayer.setPickOnBounds(false);
        hostLayer.setAlignment(Pos.BOTTOM_RIGHT);

        dockRow.setAlignment(Pos.BOTTOM_RIGHT);
        dockRow.setPickOnBounds(false);
        dockRow.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        dockRow.setPadding(new Insets(0, 108, 6, 14));
        StackPane.setAlignment(dockRow, Pos.BOTTOM_RIGHT);

        configureFloatingLauncher();
        hostLayer.getChildren().setAll(dockRow, launcherWrap);
        hostLayer.widthProperty().addListener((o, ov, nv) -> clampLauncherTranslation(launcherWrap.getTranslateX(), launcherWrap.getTranslateY()));
        hostLayer.heightProperty().addListener((o, ov, nv) -> clampLauncherTranslation(launcherWrap.getTranslateX(), launcherWrap.getTranslateY()));

        manager.attachDockHost(this);
        manager.addEventListener(this::onStreamEvent);
        manager.addDirectoryListener(() -> Platform.runLater(this::refreshMinimizedBadges));
    }

    private void configureFloatingLauncher() {
        btnFloatingChat.setGraphic(buildLauncherGraphic());
        btnFloatingChat.getProperties().put("erp.icon.skip", true);
        btnFloatingChat.getStyleClass().setAll("button", "chat-fab-btn");
        btnFloatingChat.setMinSize(FAB_SIZE, FAB_SIZE);
        btnFloatingChat.setPrefSize(FAB_SIZE, FAB_SIZE);
        btnFloatingChat.setMaxSize(FAB_SIZE, FAB_SIZE);
        btnFloatingChat.setFocusTraversable(false);
        btnFloatingChat.setTooltip(new Tooltip("Team Chat & Live Status (Drag to move • Right-click to reset)"));
        btnFloatingChat.setOnAction(e -> {
            if (suppressNextClick) {
                suppressNextClick = false;
                return;
            }
            if (rosterPopup != null) {
                rosterPopup.toggle(launcherWrap);
            }
        });

        MenuItem resetItem = new MenuItem("Snap to Bottom-Right", IconFactory.compactIcon("restore", 13));
        resetItem.setOnAction(ev -> snapLauncherToCorner());
        ContextMenu menu = new ContextMenu(resetItem);
        btnFloatingChat.setContextMenu(menu);

        unreadBadge.getStyleClass().setAll("chat-fab-badge");
        unreadBadge.setMouseTransparent(true);
        StackPane.setAlignment(unreadBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(unreadBadge, new Insets(-3, -3, 0, 0));

        selfPresenceDot.getStyleClass().setAll("chat-presence-dot", "chat-fab-dot",
                TeamChatManager.presenceCssClass(manager.selfStatusProperty().get()));
        selfPresenceDot.setMouseTransparent(true);
        StackPane.setAlignment(selfPresenceDot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(selfPresenceDot, new Insets(0, 2, 2, 0));

        launcherWrap.getStyleClass().setAll("chat-fab-wrap");
        launcherWrap.setMinSize(FAB_SIZE, FAB_SIZE);
        launcherWrap.setPrefSize(FAB_SIZE, FAB_SIZE);
        launcherWrap.setMaxSize(FAB_SIZE, FAB_SIZE);
        launcherWrap.getChildren().setAll(btnFloatingChat, unreadBadge, selfPresenceDot);
        StackPane.setAlignment(launcherWrap, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(launcherWrap, new Insets(0, FAB_RIGHT_MARGIN, FAB_BOTTOM_MARGIN, 16));

        updateUnreadBadge((int) manager.totalUnreadProperty().get());
        manager.totalUnreadProperty().addListener((o, ov, nv) ->
                Platform.runLater(() -> updateUnreadBadge(nv == null ? 0 : nv.intValue())));
        manager.selfStatusProperty().addListener((o, ov, nv) -> Platform.runLater(() ->
                selfPresenceDot.getStyleClass().setAll("chat-presence-dot", "chat-fab-dot", TeamChatManager.presenceCssClass(nv))));

        launcherWrap.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            dragPressSceneX = e.getSceneX();
            dragPressSceneY = e.getSceneY();
            dragStartTx = launcherWrap.getTranslateX();
            dragStartTy = launcherWrap.getTranslateY();
            draggingLauncher = false;
        });

        launcherWrap.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isPrimaryButtonDown()) return;
            double dx = e.getSceneX() - dragPressSceneX;
            double dy = e.getSceneY() - dragPressSceneY;
            if (!draggingLauncher && Math.hypot(dx, dy) > DRAG_THRESHOLD_PX) {
                draggingLauncher = true;
                if (rosterPopup != null) rosterPopup.hide();
            }
            if (draggingLauncher) {
                clampLauncherTranslation(dragStartTx + dx, dragStartTy + dy);
                e.consume();
            }
        });

        launcherWrap.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (draggingLauncher) {
                draggingLauncher = false;
                suppressNextClick = true;
                Platform.runLater(() -> suppressNextClick = false);
                e.consume();
            }
        });
    }

    private void updateUnreadBadge(int count) {
        unreadBadge.setText(count > 99 ? "99+" : Integer.toString(Math.max(0, count)));
        unreadBadge.setVisible(count > 0);
        unreadBadge.setManaged(count > 0);
    }

    private void snapLauncherToCorner() {
        launcherWrap.setTranslateX(0);
        launcherWrap.setTranslateY(0);
    }

    private Node buildLauncherGraphic() {
        Image logo = BrandingService.applicationMarkImage();
        if (logo == null || logo.isError()) {
            logo = BrandingService.companyLogo();
        }
        if (logo == null || logo.isError()) {
            logo = BrandingService.bundledMarkImage();
        }
        if (logo != null && !logo.isError()) {
            ImageView iv = new ImageView(logo);
            iv.setFitWidth(50);
            iv.setFitHeight(50);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            Circle clip = new Circle(25, 25, 25);
            iv.setClip(clip);
            StackPane markHolder = new StackPane(iv);
            markHolder.getStyleClass().add("chat-fab-logo-holder");
            markHolder.setMinSize(50, 50);
            markHolder.setPrefSize(50, 50);
            markHolder.setMaxSize(50, 50);
            return markHolder;
        }
        return IconFactory.compactIcon("communication", 30);
    }

    private void clampLauncherTranslation(double targetTx, double targetTy) {
        double hostW = hostLayer.getWidth();
        double hostH = hostLayer.getHeight();
        if (hostW <= 0 || hostH <= 0) {
            launcherWrap.setTranslateX(Math.min(0, targetTx));
            launcherWrap.setTranslateY(Math.min(0, targetTy));
            return;
        }
        double fabW = launcherWrap.getWidth() > 0 ? launcherWrap.getWidth() : FAB_SIZE;
        double fabH = launcherWrap.getHeight() > 0 ? launcherWrap.getHeight() : FAB_SIZE;
        double minTx = -(Math.max(0, hostW - fabW - FAB_RIGHT_MARGIN - 8.0));
        double maxTx = FAB_RIGHT_MARGIN - 6.0;
        double minTy = -(Math.max(0, hostH - fabH - FAB_BOTTOM_MARGIN - 8.0));
        double maxTy = FAB_BOTTOM_MARGIN - 6.0;
        launcherWrap.setTranslateX(Math.max(minTx, Math.min(maxTx, targetTx)));
        launcherWrap.setTranslateY(Math.max(minTy, Math.min(maxTy, targetTy)));
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
