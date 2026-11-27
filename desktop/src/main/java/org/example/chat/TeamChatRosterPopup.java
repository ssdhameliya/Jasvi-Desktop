package org.example.chat;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import org.example.api.chat.TeamChatApiClient.*;
import org.example.theme.ThemeManager;
import org.example.util.AppDialogService;
import org.example.util.DialogOwnerResolver;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.WindowUtilsFx;

import java.util.*;

/**
 * MS Teams / Messenger Roster popover launched from the top-bar chat button:
 * shows self live presence controls, Admin<->Super Admin support, Group Channels,
 * and Company teammates with live Online/Away/Busy/Offline dots.
 */
public final class TeamChatRosterPopup {
    private final TeamChatManager manager = TeamChatManager.getInstance();
    private final Popup popup = new Popup();
    private final VBox root = new VBox(12);
    private final Label lblOnlineSummary = new Label("0 Online");
    private final TextField txtSearch = new TextField();
    private final VBox listContainer = new VBox(8);
    private final Circle selfDot = new Circle(6);
    private final ToggleGroup statusGroup = new ToggleGroup();
    private final Map<String, ToggleButton> statusChips = new LinkedHashMap<>();
    private final TextField txtSelfNote = new TextField();
    private final Label lblStatusFeedback = new Label();
    private final PauseTransition feedbackClearTimer = new PauseTransition(Duration.seconds(2.5));
    private final Runnable dirListener = () -> Platform.runLater(this::renderDirectory);
    private Window anchorOwnerWindow;
    private boolean updatingSelfControls;
    private String lastCommittedNote = "";

    public TeamChatRosterPopup() {
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        root.getStyleClass().add("chat-roster-popup");
        root.setPrefWidth(560);
        root.setPrefHeight(560);
        root.setMaxHeight(620);
        root.setPadding(new Insets(16));

        // Header
        Label title = new Label("Team Chat & Live Status");
        title.getStyleClass().add("chat-roster-title");
        lblOnlineSummary.getStyleClass().add("chat-online-count-pill");
        Button btnNewGroup = new Button("New Group");
        btnNewGroup.setGraphic(IconFactory.compactIcon("add", 13));
        btnNewGroup.getStyleClass().addAll("approved-button", "approved-teal-button", "chat-roster-new-btn");
        btnNewGroup.setOnAction(e -> openCreateGroupDialog());
        HBox header = new HBox(10, IconFactory.compactIcon("communication", 18), title, lblOnlineSummary, new Region(), btnNewGroup);
        HBox.setHgrow(header.getChildren().get(3), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        // Self presence switcher (one-click chips + custom status note with explicit Save & blur commit)
        HBox chipRow = new HBox(6);
        chipRow.setAlignment(Pos.CENTER_LEFT);
        chipRow.getChildren().addAll(
                buildStatusChip("Available", "ONLINE", "chat-status-chip-online"),
                buildStatusChip("Busy", "BUSY", "chat-status-chip-busy"),
                buildStatusChip("Away", "AWAY", "chat-status-chip-away"),
                buildStatusChip("Offline", "OFFLINE", "chat-status-chip-offline")
        );
        selectStatusChip(manager.selfStatusProperty().get());

        lblStatusFeedback.getStyleClass().add("chat-status-feedback");
        lblStatusFeedback.setVisible(false);
        lblStatusFeedback.setManaged(false);
        Region chipSpacer = new Region();
        HBox.setHgrow(chipSpacer, Priority.ALWAYS);
        HBox statusHeaderRow = new HBox(8, selfDot, chipRow, chipSpacer, lblStatusFeedback);
        statusHeaderRow.setAlignment(Pos.CENTER_LEFT);

        txtSelfNote.setPromptText("Set custom status note (e.g. Support, In Meeting)...");
        txtSelfNote.getStyleClass().addAll("approved-input", "chat-note-input");
        HBox.setHgrow(txtSelfNote, Priority.ALWAYS);
        txtSelfNote.setOnAction(e -> commitSelfPresence(selectedStatusCode(), txtSelfNote.getText(), true));
        txtSelfNote.focusedProperty().addListener((o, wasFocused, isNowFocused) -> {
            if (wasFocused && !isNowFocused && !updatingSelfControls && isNoteDirty()) {
                commitSelfPresence(selectedStatusCode(), txtSelfNote.getText(), true);
            }
        });

        Button btnSaveNote = new Button("Save");
        btnSaveNote.setGraphic(IconFactory.compactIcon("save", 13));
        btnSaveNote.getStyleClass().addAll("approved-button", "approved-teal-button", "chat-note-save-btn");
        btnSaveNote.setOnAction(e -> commitSelfPresence(selectedStatusCode(), txtSelfNote.getText(), true));

        HBox noteRow = new HBox(8, txtSelfNote, btnSaveNote);
        noteRow.setAlignment(Pos.CENTER_LEFT);

        selfDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(manager.selfStatusProperty().get()));
        VBox selfBar = new VBox(8, statusHeaderRow, noteRow);
        selfBar.setPadding(new Insets(10));
        selfBar.getStyleClass().add("chat-self-status-bar");

        feedbackClearTimer.setOnFinished(ev -> {
            lblStatusFeedback.setVisible(false);
            lblStatusFeedback.setManaged(false);
        });

        // Search
        txtSearch.setPromptText("Search teammates or channels...");
        txtSearch.getStyleClass().add("approved-input");
        txtSearch.textProperty().addListener((o, ov, nv) -> renderDirectory());

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-roster-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, selfBar, txtSearch, scroll);
        popup.getContent().add(root);

        popup.setOnShown(e -> {
            manager.addDirectoryListener(dirListener);
            manager.refreshDirectoryAsync();
            renderDirectory();
            txtSearch.requestFocus();
        });
        popup.setOnHidden(e -> {
            if (!updatingSelfControls && isNoteDirty()) {
                commitSelfPresence(selectedStatusCode(), txtSelfNote.getText(), false);
            }
            manager.removeDirectoryListener(dirListener);
        });
    }

    public void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
    }

    public void toggle(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) return;
        anchorOwnerWindow = DialogOwnerResolver.resolve(anchor);
        Window ownerWin = anchorOwnerWindow != null ? anchorOwnerWindow : anchor.getScene().getWindow();
        ThemeManager.applyTheme(popup.getScene(), ownerWin);
        renderDirectory();

        Rectangle2D screen = WindowUtilsFx.visualBoundsFor(ownerWin);
        double popW = Math.max(460.0, Math.min(560.0, screen.getWidth() - 32.0));
        double popH = Math.max(440.0, Math.min(560.0, screen.getHeight() * 0.68));
        root.setMinWidth(popW);
        root.setPrefWidth(popW);
        root.setMaxWidth(popW);
        root.setMinHeight(Math.min(420.0, popH));
        root.setPrefHeight(popH);
        root.setMaxHeight(popH);

        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        boolean openAbove = (bounds.getMinY() - screen.getMinY()) >= (popH + 22.0);
        double rawY = openAbove ? (bounds.getMinY() - popH - 14.0) : (bounds.getMaxY() + 14.0);
        double y = Math.max(screen.getMinY() + 12.0, Math.min(rawY, screen.getMaxY() - popH - 12.0));
        double rawX = bounds.getMaxX() - popW;
        double x = Math.max(screen.getMinX() + 12.0, Math.min(rawX, screen.getMaxX() - popW - 12.0));
        popup.show(ownerWin, x, y);
    }

    private void renderDirectory() {
        listContainer.getChildren().clear();
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) {
            lblOnlineSummary.setText("Connecting...");
            listContainer.getChildren().add(new Label("Connecting to Team Chat..."));
            return;
        }

        long onlineCount = dir.users().stream()
                .filter(u -> "ONLINE".equalsIgnoreCase(u.status()))
                .count();
        lblOnlineSummary.setText(onlineCount + " Online • " + dir.users().size() + " Total");

        if (dir.selfPresence() != null) {
            updatingSelfControls = true;
            try {
                String st = dir.selfPresence().status();
                selfDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(st));
                selectStatusChip(st);
                String serverNote = dir.selfPresence().customNote() == null ? "" : dir.selfPresence().customNote();
                if (!txtSelfNote.isFocused() && !isNoteDirty()) {
                    txtSelfNote.setText(serverNote);
                    lastCommittedNote = serverNote;
                }
            } finally {
                updatingSelfControls = false;
            }
        }

        String q = txtSearch.getText() == null ? "" : txtSearch.getText().trim().toLowerCase(Locale.ROOT);

        // 1. Pinned Support & Channels
        List<ChannelSummaryDto> nonDirect = dir.channels().stream()
                .filter(c -> !"DIRECT".equals(c.channelType()))
                .filter(c -> q.isBlank() || c.name().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        if (!nonDirect.isEmpty()) {
            Label hdr = new Label("CHANNELS & ESCALATION");
            hdr.getStyleClass().add("chat-roster-section-header");
            listContainer.getChildren().add(hdr);
            for (ChannelSummaryDto ch : nonDirect) {
                listContainer.getChildren().add(buildChannelRow(ch));
            }
        }

        // 2. Company Teammates (Direct 1-on-1)
        List<PresenceDto> peers = dir.users().stream()
                .filter(u -> q.isBlank()
                        || u.fullName().toLowerCase(Locale.ROOT).contains(q)
                        || u.username().toLowerCase(Locale.ROOT).contains(q)
                        || (u.role() != null && u.role().toLowerCase(Locale.ROOT).contains(q)))
                .toList();

        Label pplHdr = new Label("TEAMMATES (" + peers.size() + ")");
        pplHdr.getStyleClass().add("chat-roster-section-header");
        listContainer.getChildren().add(pplHdr);

        Map<Integer, ChannelSummaryDto> dmByPeer = new HashMap<>();
        for (ChannelSummaryDto ch : dir.channels()) {
            if ("DIRECT".equals(ch.channelType()) && ch.peerUserId() != null) {
                dmByPeer.put(ch.peerUserId(), ch);
            }
        }

        for (PresenceDto user : peers) {
            listContainer.getChildren().add(buildUserRow(user, dmByPeer.get(user.userId())));
        }
    }

    private HBox buildChannelRow(ChannelSummaryDto ch) {
        boolean support = "ADMIN_SUPPORT".equals(ch.channelType());
        StackPane avatar = new StackPane(IconFactory.compactIcon(support ? "shield" : "communication", 16));
        avatar.getStyleClass().addAll("chat-roster-avatar", support ? "chat-roster-avatar-support" : "chat-roster-avatar-channel");

        Label name = new Label(support ? ch.name() : "# " + ch.name());
        name.getStyleClass().add("chat-roster-item-name");
        Label preview = new Label(ch.lastMessagePreview() == null || ch.lastMessagePreview().isBlank()
                ? (support ? "Direct line between Company Admin & Super Admin" : "Team channel conversation")
                : ch.lastMessagePreview());
        preview.getStyleClass().add("chat-roster-item-sub");
        preview.setWrapText(true);
        VBox text = new VBox(2, name, preview);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(12, avatar, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("chat-roster-row");
        if (ch.unreadCount() > 0) {
            Label badge = new Label(String.valueOf(ch.unreadCount()));
            badge.getStyleClass().add("chat-unread-pill");
            row.getChildren().add(badge);
        }
        row.setOnMouseClicked(e -> {
            popup.hide();
            manager.openChannel(ch);
        });
        return row;
    }

    private HBox buildUserRow(PresenceDto user, ChannelSummaryDto existingDm) {
        Label initials = new Label(initialsOf(user.fullName()));
        initials.getStyleClass().add("chat-avatar-initials");
        Circle dot = new Circle(5.5);
        dot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(user.status()));
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        StackPane avatar = new StackPane(initials, dot);
        avatar.getStyleClass().add("chat-roster-avatar");

        Label name = new Label(user.fullName());
        name.getStyleClass().add("chat-roster-item-name");

        String rolePart = user.role() != null ? user.role() : "";
        String deptPart = user.department() != null && !user.department().isBlank() ? " • " + user.department() : "";
        String notePart = user.customNote() != null && !user.customNote().isBlank() ? " — " + user.customNote() : "";
        Label sub = new Label(rolePart + deptPart + notePart);
        sub.getStyleClass().add("chat-roster-item-sub");
        sub.setWrapText(true);

        VBox text = new VBox(2, name, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label statusPill = new Label(TeamChatManager.presenceLabel(user.status()));
        statusPill.getStyleClass().addAll("chat-status-pill", presencePillCssClass(user.status()));

        HBox row = new HBox(12, avatar, text, statusPill);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("chat-roster-row");

        if (existingDm != null && existingDm.unreadCount() > 0) {
            Label badge = new Label(String.valueOf(existingDm.unreadCount()));
            badge.getStyleClass().add("chat-unread-pill");
            row.getChildren().add(badge);
        }

        row.setOnMouseClicked(e -> {
            popup.hide();
            if (existingDm != null) {
                manager.openChannel(existingDm);
            } else {
                manager.openDirectWithUser(user.userId());
            }
        });
        return row;
    }

    private static String initialsOf(String fullName) {
        if (fullName == null || fullName.isBlank()) return "?";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String presencePillCssClass(String status) {
        return switch (status == null ? "OFFLINE" : status.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> "chat-status-pill-online";
            case "AWAY" -> "chat-status-pill-away";
            case "BUSY" -> "chat-status-pill-busy";
            default -> "chat-status-pill-offline";
        };
    }

    private void openCreateGroupDialog() {
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) return;

        // Always hide auto-hiding Popup FIRST and resolve the primary Stage so modal showAndWait
        // never attaches to a closing PopupWindow (which causes TKStage.setImportant NPE).
        Window owner = DialogOwnerResolver.resolve(anchorOwnerWindow);
        popup.hide();

        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(owner);
        dlg.setTitle("Create Team Channel");
        dlg.setHeaderText("Create a shared group conversation for selected teammates");

        Label lblName = new Label("Channel Name", IconFactory.compactIcon("communication", 14));
        lblName.getStyleClass().add("chat-group-field-label");

        TextField txtName = new TextField();
        txtName.setPromptText("e.g. accounts, sales-team, dispatch");
        txtName.getStyleClass().add("approved-input");

        Label lblMembers = new Label("Add Team Members (" + dir.users().size() + ")", IconFactory.compactIcon("user", 14));
        lblMembers.getStyleClass().add("chat-group-field-label");

        VBox membersBox = new VBox(8);
        membersBox.setPadding(new Insets(6));
        List<CheckBox> checks = new ArrayList<>();
        for (PresenceDto u : dir.users()) {
            String roleSuffix = u.role() == null || u.role().isBlank() ? "" : " (" + u.role() + ")";
            CheckBox cb = new CheckBox(u.fullName() + roleSuffix);
            cb.setUserData(u.userId());
            checks.add(cb);
            membersBox.getChildren().add(cb);
        }
        if (dir.users().isEmpty()) {
            Label empty = new Label("No additional active teammates available yet.");
            empty.getStyleClass().add("chat-roster-item-sub");
            membersBox.getChildren().add(empty);
        }
        ScrollPane sp = new ScrollPane(membersBox);
        sp.setPrefViewportHeight(220);
        sp.setMinHeight(150);
        sp.setMaxHeight(260);
        sp.setFitToWidth(true);
        sp.getStyleClass().add("chat-group-members-scroll");

        Label lblValidation = new Label();
        lblValidation.getStyleClass().add("chat-group-validation");
        lblValidation.setVisible(false);
        lblValidation.setManaged(false);
        lblValidation.setWrapText(true);

        VBox content = new VBox(10, lblName, txtName, lblMembers, sp, lblValidation);
        content.setPadding(new Insets(14));
        content.setPrefWidth(440);
        content.setMinWidth(400);
        dlg.getDialogPane().setMinWidth(460);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Node okButton = dlg.getDialogPane().lookupButton(ButtonType.OK);
        if (okButton != null) {
            okButton.addEventFilter(ActionEvent.ACTION, ev -> {
                if (txtName.getText() == null || txtName.getText().trim().isBlank()) {
                    ev.consume();
                    lblValidation.setText("Please enter a channel name before creating the group.");
                    lblValidation.setVisible(true);
                    lblValidation.setManaged(true);
                    txtName.requestFocus();
                }
            });
        }

        if (dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && txtName.getText() != null && !txtName.getText().isBlank()) {
            List<Integer> ids = checks.stream()
                    .filter(CheckBox::isSelected)
                    .map(c -> (Integer) c.getUserData())
                    .toList();
            String channelName = txtName.getText().trim();
            Thread.ofVirtual().name("chat-create-group").start(() -> {
                try {
                    ChannelSummaryDto ch = manager.api().createGroup(channelName, ids);
                    manager.refreshDirectoryAsync();
                    Platform.runLater(() -> manager.openChannel(ch));
                } catch (Exception ex) {
                    Platform.runLater(() -> AppDialogService.error(owner, "Channel Creation Failed", ex.getMessage()));
                }
            });
        }
    }

    private ToggleButton buildStatusChip(String label, String statusCode, String semanticClass) {
        ToggleButton btn = new ToggleButton(label);
        btn.setUserData(statusCode);
        btn.setToggleGroup(statusGroup);
        btn.setFocusTraversable(false);
        btn.getStyleClass().setAll("toggle-button", "chat-status-chip", semanticClass);
        btn.setOnAction(e -> {
            if (!btn.isSelected()) {
                btn.setSelected(true);
                return;
            }
            if (!updatingSelfControls) {
                commitSelfPresence(statusCode, txtSelfNote.getText(), true);
            }
        });
        statusChips.put(statusCode, btn);
        return btn;
    }

    private void selectStatusChip(String statusCode) {
        String norm = statusCode == null ? "ONLINE" : statusCode.trim().toUpperCase(Locale.ROOT);
        ToggleButton chip = statusChips.getOrDefault(norm, statusChips.get("ONLINE"));
        if (chip != null && !chip.isSelected()) {
            chip.setSelected(true);
        }
    }

    private String selectedStatusCode() {
        Toggle selected = statusGroup.getSelectedToggle();
        if (selected != null && selected.getUserData() instanceof String code) {
            return code;
        }
        return manager.selfStatusProperty().get();
    }

    private boolean isNoteDirty() {
        String current = txtSelfNote.getText() == null ? "" : txtSelfNote.getText().trim();
        String committed = lastCommittedNote == null ? "" : lastCommittedNote.trim();
        return !Objects.equals(current, committed);
    }

    private void commitSelfPresence(String statusCode, String rawNote, boolean showFeedback) {
        String trimmedNote = rawNote == null ? "" : rawNote.trim();
        lastCommittedNote = trimmedNote;
        selfDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(statusCode));
        selectStatusChip(statusCode);
        if (showFeedback) {
            showStatusFeedback("Saving...", false);
        }
        manager.updateSelfPresenceAsync(statusCode, trimmedNote,
                () -> {
                    if (showFeedback) showStatusFeedback("Saved", false);
                },
                err -> {
                    if (showFeedback) showStatusFeedback("Save failed", true);
                });
    }

    private void showStatusFeedback(String message, boolean error) {
        feedbackClearTimer.stop();
        lblStatusFeedback.setText(message);
        lblStatusFeedback.getStyleClass().setAll("chat-status-feedback",
                error ? "chat-status-feedback-err" : "chat-status-feedback-ok");
        lblStatusFeedback.setVisible(true);
        lblStatusFeedback.setManaged(true);
        if (!"Saving...".equals(message)) {
            feedbackClearTimer.playFromStart();
        }
    }
}
