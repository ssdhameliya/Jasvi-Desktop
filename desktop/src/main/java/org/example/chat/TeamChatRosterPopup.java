package org.example.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Popup;
import org.example.api.chat.TeamChatApiClient.*;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;

import java.util.*;

/**
 * MS Teams / Messenger Roster popover launched from the top-bar chat button:
 * shows self live presence controls, Admin<->Super Admin support, Group Channels,
 * and Company teammates with live Online/Away/Busy/Offline dots.
 */
public final class TeamChatRosterPopup {
    private final TeamChatManager manager = TeamChatManager.getInstance();
    private final Popup popup = new Popup();
    private final TextField txtSearch = new TextField();
    private final VBox listContainer = new VBox(6);
    private final Circle selfDot = new Circle(5);
    private final ComboBox<String> cmbSelfStatus = new ComboBox<>();
    private final TextField txtSelfNote = new TextField();
    private final Runnable dirListener = () -> Platform.runLater(this::renderDirectory);

    public TeamChatRosterPopup() {
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        VBox root = new VBox(10);
        root.getStyleClass().add("chat-roster-popup");
        root.setPrefWidth(360);
        root.setMaxHeight(580);
        root.setPadding(new Insets(12));

        // Header
        Label title = new Label("Team Chat & Live Status");
        title.getStyleClass().add("chat-roster-title");
        Button btnNewGroup = new Button("New Group");
        btnNewGroup.setGraphic(IconFactory.compactIcon("add", 12));
        btnNewGroup.getStyleClass().addAll("approved-button", "approved-secondary-button", "chat-roster-new-btn");
        btnNewGroup.setOnAction(e -> openCreateGroupDialog(root));
        HBox header = new HBox(8, IconFactory.compactIcon("communication", 16), title, new Region(), btnNewGroup);
        HBox.setHgrow(header.getChildren().get(2), Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);

        // Self presence switcher
        cmbSelfStatus.getItems().addAll("Available", "Busy", "Away", "Offline");
        cmbSelfStatus.getStyleClass().add("chat-status-combo");
        cmbSelfStatus.setValue(TeamChatManager.presenceLabel(manager.selfStatusProperty().get()));
        cmbSelfStatus.setOnAction(e -> {
            String code = statusCodeFromLabel(cmbSelfStatus.getValue());
            manager.updateSelfPresenceAsync(code, txtSelfNote.getText());
        });

        txtSelfNote.setPromptText("Set custom status note...");
        txtSelfNote.getStyleClass().addAll("approved-input", "chat-note-input");
        HBox.setHgrow(txtSelfNote, Priority.ALWAYS);
        txtSelfNote.setOnAction(e -> manager.updateSelfPresenceAsync(statusCodeFromLabel(cmbSelfStatus.getValue()), txtSelfNote.getText()));

        selfDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(manager.selfStatusProperty().get()));
        HBox selfRow = new HBox(8, selfDot, cmbSelfStatus, txtSelfNote);
        selfRow.setAlignment(Pos.CENTER_LEFT);
        selfRow.getStyleClass().add("chat-self-status-bar");

        // Search
        txtSearch.setPromptText("Search teammates or channels...");
        txtSearch.getStyleClass().add("approved-input");
        txtSearch.textProperty().addListener((o, ov, nv) -> renderDirectory());

        ScrollPane scroll = new ScrollPane(listContainer);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("chat-roster-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, selfRow, txtSearch, scroll);
        popup.getContent().add(root);

        popup.setOnShown(e -> {
            manager.addDirectoryListener(dirListener);
            manager.refreshDirectoryAsync();
            renderDirectory();
            txtSearch.requestFocus();
        });
        popup.setOnHidden(e -> manager.removeDirectoryListener(dirListener));
    }

    public void toggle(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) return;
        if (!popup.getContent().isEmpty() && popup.getContent().getFirst() instanceof Region root) {
            root.getStylesheets().setAll(anchor.getScene().getStylesheets());
        }
        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        double x = Math.max(12, bounds.getMaxX() - 360);
        double y = bounds.getMaxY() + 6;
        popup.show(anchor.getScene().getWindow(), x, y);
    }

    private void renderDirectory() {
        listContainer.getChildren().clear();
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) {
            listContainer.getChildren().add(new Label("Connecting to Team Chat..."));
            return;
        }

        if (dir.selfPresence() != null) {
            selfDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(dir.selfPresence().status()));
            String desired = TeamChatManager.presenceLabel(dir.selfPresence().status());
            if (!Objects.equals(cmbSelfStatus.getValue(), desired)) cmbSelfStatus.setValue(desired);
            if (!txtSelfNote.isFocused()) {
                txtSelfNote.setText(dir.selfPresence().customNote() == null ? "" : dir.selfPresence().customNote());
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
        Node icon = IconFactory.compactIcon(support ? "shield" : "communication", 14);
        Label name = new Label(support ? ch.name() : "# " + ch.name());
        name.getStyleClass().add("chat-roster-item-name");
        Label preview = new Label(ch.lastMessagePreview() == null || ch.lastMessagePreview().isBlank()
                ? (support ? "Direct line between Company Admin & Super Admin" : "Team channel")
                : ch.lastMessagePreview());
        preview.getStyleClass().add("chat-roster-item-sub");
        VBox text = new VBox(1, name, preview);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, icon, text);
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
        Circle dot = new Circle(5);
        dot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(user.status()));

        Label name = new Label(user.fullName());
        name.getStyleClass().add("chat-roster-item-name");

        String rolePart = user.role() != null ? user.role() : "";
        String deptPart = user.department() != null && !user.department().isBlank() ? " • " + user.department() : "";
        String statusPart = " • " + TeamChatManager.presenceLabel(user.status());
        String notePart = user.customNote() != null && !user.customNote().isBlank() ? " — " + user.customNote() : "";
        Label sub = new Label(rolePart + deptPart + statusPart + notePart);
        sub.getStyleClass().add("chat-roster-item-sub");

        VBox text = new VBox(1, name, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, dot, text);
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

    private void openCreateGroupDialog(Node owner) {
        DirectoryResponse dir = manager.latestDirectory();
        if (dir == null) return;
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(owner);
        dlg.setTitle("Create Team Channel");

        TextField txtName = new TextField();
        txtName.setPromptText("e.g. accounts, sales-team, dispatch");
        txtName.getStyleClass().add("approved-input");

        VBox membersBox = new VBox(6);
        List<CheckBox> checks = new ArrayList<>();
        for (PresenceDto u : dir.users()) {
            CheckBox cb = new CheckBox(u.fullName() + " (" + u.role() + ")");
            cb.setUserData(u.userId());
            checks.add(cb);
            membersBox.getChildren().add(cb);
        }
        ScrollPane sp = new ScrollPane(membersBox);
        sp.setPrefHeight(210);
        sp.setFitToWidth(true);

        VBox content = new VBox(10, new Label("Channel Name"), txtName, new Label("Add Team Members"), sp);
        content.setPadding(new Insets(14));
        content.setPrefWidth(380);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

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
                    Platform.runLater(() -> {
                        popup.hide();
                        manager.openChannel(ch);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> AppDialogService.error(owner, "Channel Creation Failed", ex.getMessage()));
                }
            });
        }
    }

    private static String statusCodeFromLabel(String label) {
        if (label == null) return "ONLINE";
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "busy" -> "BUSY";
            case "away" -> "AWAY";
            case "offline" -> "OFFLINE";
            default -> "ONLINE";
        };
    }
}
