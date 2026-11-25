package org.example.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import org.example.api.chat.TeamChatApiClient.*;
import org.example.controller.LinkedRecordContext;
import org.example.service.SessionService;
import org.example.util.AppDialogService;
import org.example.util.BusinessClock;
import org.example.util.DesktopLog;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reusable Facebook Messenger / MS Teams chat conversation component.
 * Powers both docked bottom-right multi-chat boxes and detached pop-out OS windows.
 */
public final class TeamChatWindowPane extends VBox {
    private static final List<String> QUICK_REACTIONS = List.of("\uD83D\uDC4D", "\u2705", "\uD83D\uDC40", "\u2764\uFE0F", "\uD83D\uDE4F");
    private static final Map<Long, Image> IMAGE_CACHE = new ConcurrentHashMap<>();

    private final TeamChatManager manager = TeamChatManager.getInstance();
    private final boolean popoutMode;
    private final Runnable onCloseRequested;
    private final Runnable onMinimizeRequested;
    private final Runnable onPopoutOrDockRequested;
    private final Consumer<String> onOpenErpRecord;

    private ChannelSummaryDto channel;
    private final List<MessageDto> messages = new ArrayList<>();
    private final List<File> pendingAttachments = new ArrayList<>();
    private final List<File> tempClipboardFiles = new ArrayList<>();
    private MessageDto replyTarget;
    private String pendingDocType;
    private String pendingDocNo;

    private final Circle statusDot = new Circle(5);
    private final Label lblTitle = new Label();
    private final Label lblSubtitle = new Label();
    private final Label lblTyping = new Label();
    private final HBox searchBar = new HBox(6);
    private final TextField txtSearch = new TextField();
    private final VBox messageContainer = new VBox(8);
    private final ScrollPane messageScroll = new ScrollPane();
    private final VBox pendingBar = new VBox(4);
    private final TextArea txtInput = new TextArea();
    private final Button btnSend = new Button();

    private final Consumer<ChatStreamEvent> streamListener = this::handleStreamEvent;
    private final Runnable directoryListener = this::syncHeaderPresenceFromDirectory;

    private long lastTypingSentMs = 0L;
    private boolean disposed = false;

    public TeamChatWindowPane(
            ChannelSummaryDto channel,
            boolean popoutMode,
            Runnable onCloseRequested,
            Runnable onMinimizeRequested,
            Runnable onPopoutOrDockRequested,
            Consumer<String> onOpenErpRecord
    ) {
        this.channel = channel;
        this.popoutMode = popoutMode;
        this.onCloseRequested = onCloseRequested;
        this.onMinimizeRequested = onMinimizeRequested;
        this.onPopoutOrDockRequested = onPopoutOrDockRequested;
        this.onOpenErpRecord = onOpenErpRecord;

        getStyleClass().addAll("chat-window-card", popoutMode ? "chat-window-popout" : "chat-window-docked");
        setFillWidth(true);

        buildUi();
        installDragDrop();
        manager.addEventListener(streamListener);
        manager.addDirectoryListener(directoryListener);
        loadMessagesAsync(null);
    }

    public ChannelSummaryDto channel() {
        return channel;
    }

    public void dispose() {
        disposed = true;
        manager.removeEventListener(streamListener);
        manager.removeDirectoryListener(directoryListener);
        for (File f : tempClipboardFiles) {
            try { Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
        }
        tempClipboardFiles.clear();
    }

    public void focusInput() {
        Platform.runLater(() -> txtInput.requestFocus());
    }

    private void buildUi() {
        // Header bar
        StackPane avatar = buildChannelAvatar();
        lblTitle.getStyleClass().add("chat-window-title");
        lblSubtitle.getStyleClass().add("chat-window-subtitle");
        updateHeaderText();

        VBox titleBox = new VBox(1, lblTitle, lblSubtitle);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button btnSearch = compactIconBtn("search", "Search in conversation", e -> toggleSearchBar());
        Button btnPop = compactIconBtn(popoutMode ? "minimize" : "popout",
                popoutMode ? "Dock back inside ERP" : "Pop out into separate window",
                e -> { if (onPopoutOrDockRequested != null) onPopoutOrDockRequested.run(); });
        Button btnMin = compactIconBtn("minimize", "Minimize chat", e -> {
            if (onMinimizeRequested != null) onMinimizeRequested.run();
        });
        Button btnClose = compactIconBtn("cancel", "Close chat", e -> {
            if (onCloseRequested != null) onCloseRequested.run();
        });

        HBox headerActions = new HBox(3, btnSearch, btnPop);
        if (!popoutMode && onMinimizeRequested != null) headerActions.getChildren().add(btnMin);
        headerActions.getChildren().add(btnClose);
        headerActions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(8, avatar, titleBox, headerActions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-window-header");
        if (!popoutMode && onMinimizeRequested != null) {
            header.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) onMinimizeRequested.run();
            });
        }

        // Optional collapsible search bar
        txtSearch.setPromptText("Search messages or document #...");
        txtSearch.getStyleClass().addAll("approved-input", "chat-search-input");
        HBox.setHgrow(txtSearch, Priority.ALWAYS);
        txtSearch.setOnAction(e -> loadMessagesAsync(txtSearch.getText()));
        Button btnClearSearch = compactIconBtn("cancel", "Clear search", e -> {
            txtSearch.clear();
            toggleSearchBar();
            loadMessagesAsync(null);
        });
        searchBar.getChildren().addAll(txtSearch, btnClearSearch);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.getStyleClass().add("chat-search-bar");
        searchBar.setVisible(false);
        searchBar.setManaged(false);

        // Message stream
        messageContainer.setPadding(new Insets(10, 10, 10, 10));
        messageContainer.setFillWidth(true);
        messageScroll.setContent(messageContainer);
        messageScroll.setFitToWidth(true);
        messageScroll.getStyleClass().add("chat-message-scroll");
        VBox.setVgrow(messageScroll, Priority.ALWAYS);

        // Typing indicator
        lblTyping.getStyleClass().add("chat-typing-label");
        lblTyping.setVisible(false);
        lblTyping.setManaged(false);

        // Pending reply / attachment / ERP link bar
        pendingBar.getStyleClass().add("chat-pending-bar");
        pendingBar.setVisible(false);
        pendingBar.setManaged(false);

        // Composer bar
        Button btnAttach = compactIconBtn("attachment", "Attach files, screenshots, PDF, Excel...", e -> chooseFiles());
        Button btnLinkErp = compactIconBtn("link", "Link an ERP document (Invoice, Journal, Bill, Quotation...)", e -> promptLinkErpRecord());

        txtInput.setPromptText("Message... (Ctrl+V screenshot, Drop files, Enter to send)");
        txtInput.setWrapText(true);
        txtInput.setPrefRowCount(2);
        txtInput.setMinHeight(38);
        txtInput.setMaxHeight(86);
        txtInput.getStyleClass().add("chat-composer-input");
        HBox.setHgrow(txtInput, Priority.ALWAYS);

        txtInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                sendCurrentMessage();
            } else if (e.isControlDown() && e.getCode() == KeyCode.V) {
                if (tryAttachFromClipboard()) {
                    e.consume();
                }
            } else {
                maybeSendTypingPing();
            }
        });

        btnSend.setGraphic(IconFactory.compactIcon("chat-send", 14));
        btnSend.setTooltip(new Tooltip("Send message (Enter)"));
        btnSend.getStyleClass().addAll("approved-button", "approved-primary-button", "chat-send-btn");
        btnSend.setOnAction(e -> sendCurrentMessage());

        HBox composer = new HBox(6, btnAttach, btnLinkErp, txtInput, btnSend);
        composer.setAlignment(Pos.BOTTOM_CENTER);
        composer.getStyleClass().add("chat-composer");

        getChildren().addAll(header, searchBar, messageScroll, lblTyping, pendingBar, composer);
    }

    private StackPane buildChannelAvatar() {
        StackPane wrap = new StackPane();
        wrap.getStyleClass().add("chat-avatar-wrap");

        if ("DIRECT".equals(channel.channelType())) {
            Label initials = new Label(initialsOf(channel.name()));
            initials.getStyleClass().add("chat-avatar-initials");
            statusDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(channel.peerStatus()));
            StackPane.setAlignment(statusDot, Pos.BOTTOM_RIGHT);
            wrap.getChildren().addAll(initials, statusDot);
        } else if ("ADMIN_SUPPORT".equals(channel.channelType())) {
            Node icon = IconFactory.compactIcon("shield", 15);
            wrap.getStyleClass().add("chat-avatar-support");
            wrap.getChildren().add(icon);
        } else {
            Label hash = new Label("#");
            hash.getStyleClass().add("chat-avatar-hash");
            wrap.getStyleClass().add("chat-avatar-group");
            wrap.getChildren().add(hash);
        }
        return wrap;
    }

    private void updateHeaderText() {
        String name = channel.name() == null ? "Chat" : channel.name();
        if ("GROUP".equals(channel.channelType()) && !name.startsWith("#")) {
            lblTitle.setText("# " + name);
        } else {
            lblTitle.setText(name);
        }

        if ("DIRECT".equals(channel.channelType())) {
            statusDot.getStyleClass().setAll("chat-presence-dot", TeamChatManager.presenceCssClass(channel.peerStatus()));
            String stLabel = TeamChatManager.presenceLabel(channel.peerStatus());
            String note = channel.peerCustomNote();
            lblSubtitle.setText(note != null && !note.isBlank() ? stLabel + " • " + note : stLabel);
        } else if ("ADMIN_SUPPORT".equals(channel.channelType())) {
            lblSubtitle.setText("Admin ↔ Super Admin Priority Channel");
        } else {
            int count = channel.members() != null ? channel.members().size() : 0;
            lblSubtitle.setText(count + " team members");
        }
    }

    private void syncHeaderPresenceFromDirectory() {
        if (disposed || manager.latestDirectory() == null) return;
        for (ChannelSummaryDto ch : manager.latestDirectory().channels()) {
            if (ch.id() == channel.id()) {
                channel = ch;
                Platform.runLater(this::updateHeaderText);
                return;
            }
        }
    }

    private void toggleSearchBar() {
        boolean show = !searchBar.isVisible();
        searchBar.setVisible(show);
        searchBar.setManaged(show);
        if (show) txtSearch.requestFocus();
    }

    private void loadMessagesAsync(String query) {
        Thread.ofVirtual().name("chat-load-msgs").start(() -> {
            try {
                List<MessageDto> loaded = manager.api().listMessages(channel.id(), null, query, 80);
                Platform.runLater(() -> {
                    if (disposed) return;
                    messages.clear();
                    messages.addAll(loaded);
                    renderMessages(true);
                    markLatestRead();
                });
            } catch (Exception ex) {
                DesktopLog.warn("TeamChatWindowPane", "LOAD_MESSAGES", ex.getMessage());
            }
        });
    }

    private void handleStreamEvent(ChatStreamEvent ev) {
        if (disposed || ev == null || ev.channelId() == null || ev.channelId() != channel.id()) return;
        switch (ev.eventType()) {
            case "MESSAGE" -> {
                if (ev.message() == null) return;
                upsertMessage(ev.message());
                lblTyping.setVisible(false);
                lblTyping.setManaged(false);
                renderMessages(true);
                markLatestRead();
            }
            case "MESSAGE_UPDATED", "READ" -> {
                if ("READ".equals(ev.eventType())) {
                    loadMessagesAsync(txtSearch.isVisible() ? txtSearch.getText() : null);
                } else if (ev.message() != null) {
                    upsertMessage(ev.message());
                    renderMessages(false);
                }
            }
            case "TYPING" -> {
                Integer selfId = selfUserId();
                if (selfId != null && Objects.equals(selfId, ev.actorUserId())) return;
                boolean show = Boolean.TRUE.equals(ev.typing());
                lblTyping.setText((ev.actorName() != null ? ev.actorName() : "Teammate") + " is typing...");
                lblTyping.setVisible(show);
                lblTyping.setManaged(show);
            }
            default -> {}
        }
    }

    private void upsertMessage(MessageDto msg) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id() == msg.id()) {
                messages.set(i, msg);
                return;
            }
        }
        messages.add(msg);
    }

    private void markLatestRead() {
        if (messages.isEmpty()) return;
        long maxId = messages.getLast().id();
        manager.markChannelReadAsync(channel.id(), maxId);
    }

    private void renderMessages(boolean scrollToBottom) {
        messageContainer.getChildren().clear();
        if (messages.isEmpty()) {
            Label empty = new Label("No messages yet. Say hello or attach a file/ERP record below.");
            empty.setWrapText(true);
            empty.getStyleClass().add("chat-empty-hint");
            messageContainer.getChildren().add(empty);
            return;
        }

        Integer selfId = selfUserId();
        String lastDateLabel = null;
        for (MessageDto msg : messages) {
            String dateLabel = formatMessageDateLabel(msg.createdUtc());
            if (!Objects.equals(dateLabel, lastDateLabel)) {
                lastDateLabel = dateLabel;
                Label datePill = new Label(dateLabel);
                datePill.getStyleClass().add("chat-date-divider");
                HBox dateWrap = new HBox(datePill);
                dateWrap.setAlignment(Pos.CENTER);
                messageContainer.getChildren().add(dateWrap);
            }
            boolean isSelf = selfId != null && selfId == msg.senderId();
            messageContainer.getChildren().add(buildMessageNode(msg, isSelf));
        }

        if (scrollToBottom) {
            Platform.runLater(() -> messageScroll.setVvalue(1.0));
        }
    }

    private Node buildMessageNode(MessageDto msg, boolean isSelf) {
        VBox bubble = new VBox(5);
        bubble.setMaxWidth(popoutMode ? 520 : 272);
        bubble.getStyleClass().addAll("chat-bubble", isSelf ? "chat-bubble-self" : "chat-bubble-peer");
        if (msg.deleted()) bubble.getStyleClass().add("chat-bubble-deleted");

        if (!isSelf) {
            String roleTag = msg.senderRole() != null && !msg.senderRole().isBlank() ? " • " + msg.senderRole() : "";
            Label sender = new Label(msg.senderName() + roleTag);
            sender.getStyleClass().add("chat-msg-sender");
            bubble.getChildren().add(sender);
        }

        if (!msg.deleted() && msg.replyPreview() != null && !msg.replyPreview().isBlank()) {
            Label reply = new Label("↩ " + msg.replyPreview());
            reply.setWrapText(true);
            reply.getStyleClass().add("chat-reply-quote");
            bubble.getChildren().add(reply);
        }

        if (msg.body() != null && !msg.body().isBlank()) {
            Label body = new Label(msg.body());
            body.setWrapText(true);
            body.getStyleClass().add("chat-msg-body");
            bubble.getChildren().add(body);
        }

        if (!msg.deleted() && msg.linkedDocNo() != null && !msg.linkedDocNo().isBlank()) {
            Button linkCard = new Button((msg.linkedDocType() != null ? msg.linkedDocType() + ": " : "ERP Ref: ") + msg.linkedDocNo());
            linkCard.setGraphic(IconFactory.compactIcon("link", 12));
            linkCard.getStyleClass().addAll("approved-button", "chat-doc-link-btn");
            linkCard.setOnAction(e -> openLinkedDocument(msg.linkedDocType(), msg.linkedDocNo()));
            bubble.getChildren().add(linkCard);
        }

        if (!msg.deleted() && msg.attachments() != null && !msg.attachments().isEmpty()) {
            VBox attBox = new VBox(4);
            for (AttachmentDto att : msg.attachments()) {
                attBox.getChildren().add(buildAttachmentNode(att));
            }
            bubble.getChildren().add(attBox);
        }

        if (!msg.deleted() && msg.reactions() != null && !msg.reactions().isEmpty()) {
            FlowPane reactFlow = new FlowPane(4, 4);
            for (var entry : msg.reactions().entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                Button pill = new Button(entry.getKey() + " " + entry.getValue().size());
                pill.getStyleClass().add("chat-reaction-pill");
                Integer selfId = selfUserId();
                if (selfId != null && entry.getValue().contains(selfId)) {
                    pill.getStyleClass().add("chat-reaction-pill-mine");
                }
                pill.setOnAction(e -> toggleReactionAsync(msg.id(), entry.getKey()));
                reactFlow.getChildren().add(pill);
            }
            bubble.getChildren().add(reactFlow);
        }

        String timeText = formatMessageTime(msg.createdUtc())
                + (msg.editedUtc() != null && !msg.deleted() ? " (edited)" : "")
                + (isSelf ? (msg.readByPeer() ? "  ✓✓" : "  ✓") : "");
        Label meta = new Label(timeText);
        meta.getStyleClass().add("chat-msg-meta");
        bubble.getChildren().add(meta);

        // Quick action toolbar + ContextMenu
        if (!msg.deleted()) {
            ContextMenu menu = buildMessageContextMenu(msg, isSelf);
            bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
            Button btnMore = new Button("⋯");
            btnMore.getStyleClass().add("chat-msg-more-btn");
            btnMore.setOnAction(e -> menu.show(btnMore, javafx.geometry.Side.BOTTOM, 0, 0));

            HBox row = isSelf ? new HBox(4, btnMore, bubble) : new HBox(4, bubble, btnMore);
            row.setAlignment(isSelf ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            row.getStyleClass().add("chat-msg-row");
            return row;
        }

        HBox row = new HBox(bubble);
        row.setAlignment(isSelf ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.getStyleClass().add("chat-msg-row");
        return row;
    }

    private ContextMenu buildMessageContextMenu(MessageDto msg, boolean isSelf) {
        ContextMenu menu = new ContextMenu();
        MenuItem replyItem = new MenuItem("Reply", IconFactory.compactIcon("communication", 13));
        replyItem.setOnAction(e -> {
            replyTarget = msg;
            refreshPendingBar();
            txtInput.requestFocus();
        });
        menu.getItems().add(replyItem);

        for (String emoji : QUICK_REACTIONS) {
            MenuItem r = new MenuItem("React " + emoji);
            r.setOnAction(e -> toggleReactionAsync(msg.id(), emoji));
            menu.getItems().add(r);
        }
        if (isSelf) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem editItem = new MenuItem("Edit Message", IconFactory.compactIcon("edit", 13));
            editItem.setOnAction(e -> editMessagePrompt(msg));
            MenuItem delItem = new MenuItem("Delete Message", IconFactory.compactIcon("delete", 13));
            delItem.setOnAction(e -> deleteMessageAsync(msg.id()));
            menu.getItems().addAll(editItem, delItem);
        }
        return menu;
    }

    private Node buildAttachmentNode(AttachmentDto att) {
        if (att.image()) {
            VBox imgWrap = new VBox(3);
            imgWrap.getStyleClass().add("chat-attachment-image-card");
            ImageView iv = new ImageView();
            iv.setPreserveRatio(true);
            iv.setFitWidth(popoutMode ? 300 : 210);
            iv.setFitHeight(170);

            Label loading = new Label("Loading " + att.fileName() + "...");
            loading.getStyleClass().add("chat-msg-meta");
            imgWrap.getChildren().add(loading);

            Image cached = IMAGE_CACHE.get(att.id());
            if (cached != null) {
                iv.setImage(cached);
                imgWrap.getChildren().setAll(iv);
            } else {
                Thread.ofVirtual().name("chat-img-load").start(() -> {
                    try {
                        byte[] bytes = manager.api().fetchAttachmentBytes(att.id());
                        Image img = new Image(new ByteArrayInputStream(bytes));
                        IMAGE_CACHE.put(att.id(), img);
                        Platform.runLater(() -> {
                            iv.setImage(img);
                            imgWrap.getChildren().setAll(iv);
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> loading.setText("Image: " + att.fileName()));
                    }
                });
            }

            iv.setOnMouseClicked(e -> showFullImagePreview(att, iv.getImage()));
            Button btnSave = compactIconBtn("download", "Download " + att.fileName(), e -> saveAttachmentAs(att));
            HBox cap = new HBox(6, new Label(att.fileName()), btnSave);
            cap.setAlignment(Pos.CENTER_LEFT);
            imgWrap.getChildren().add(cap);
            return imgWrap;
        }

        HBox fileCard = new HBox(8);
        fileCard.setAlignment(Pos.CENTER_LEFT);
        fileCard.getStyleClass().add("chat-attachment-file-card");
        Node icon = IconFactory.compactIcon("attachment", 14);
        Label name = new Label(att.fileName() + " (" + humanSize(att.fileSize()) + ")");
        name.setWrapText(true);
        HBox.setHgrow(name, Priority.ALWAYS);
        Button btnDownload = compactIconBtn("download", "Download / Save file", e -> saveAttachmentAs(att));
        fileCard.getChildren().addAll(icon, name, btnDownload);
        return fileCard;
    }

    private void showFullImagePreview(AttachmentDto att, Image img) {
        if (img == null) return;
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(this);
        dlg.setTitle(att.fileName());
        ImageView full = new ImageView(img);
        full.setPreserveRatio(true);
        full.setFitWidth(760);
        full.setFitHeight(520);
        VBox box = new VBox(10, full);
        box.setPadding(new Insets(12));
        box.setAlignment(Pos.CENTER);
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dlg.showAndWait();
    }

    private void saveAttachmentAs(AttachmentDto att) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Attachment");
        fc.setInitialFileName(att.fileName());
        File dest = fc.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (dest == null) return;
        Thread.ofVirtual().name("chat-download").start(() -> {
            try {
                manager.api().downloadAttachment(att.id(), dest.toPath());
                Platform.runLater(() -> AppDialogService.success(this, "Saved", "Downloaded " + dest.getName()));
            } catch (Exception ex) {
                Platform.runLater(() -> AppDialogService.error(this, "Download Failed", ex.getMessage()));
            }
        });
    }

    private void chooseFiles() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Attach Files to Chat");
        List<File> chosen = fc.showOpenMultipleDialog(getScene() != null ? getScene().getWindow() : null);
        if (chosen != null && !chosen.isEmpty()) {
            pendingAttachments.addAll(chosen);
            refreshPendingBar();
        }
    }

    private boolean tryAttachFromClipboard() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasFiles() && !cb.getFiles().isEmpty()) {
            pendingAttachments.addAll(cb.getFiles());
            refreshPendingBar();
            return true;
        }
        if (cb.hasImage()) {
            Image img = cb.getImage();
            if (img != null) {
                try {
                    File tmp = writeFxImageToTempPng(img);
                    tempClipboardFiles.add(tmp);
                    pendingAttachments.add(tmp);
                    refreshPendingBar();
                    return true;
                } catch (Exception ex) {
                    DesktopLog.warn("TeamChatWindowPane", "CLIPBOARD_IMAGE", ex.getMessage());
                }
            }
        }
        if (cb.hasContent(DataFormat.IMAGE)) {
            Object raw = cb.getContent(DataFormat.IMAGE);
            if (raw instanceof Image img) {
                try {
                    File tmp = writeFxImageToTempPng(img);
                    tempClipboardFiles.add(tmp);
                    pendingAttachments.add(tmp);
                    refreshPendingBar();
                    return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static File writeFxImageToTempPng(Image fxImg) throws Exception {
        int w = (int) Math.max(1, Math.round(fxImg.getWidth()));
        int h = (int) Math.max(1, Math.round(fxImg.getHeight()));
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = fxImg.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        File tmp = Files.createTempFile("jasvi-chat-clip-", ".png").toFile();
        ImageIO.write(bi, "png", tmp);
        return tmp;
    }

    private void installDragDrop() {
        setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles() && !db.getFiles().isEmpty()) {
                pendingAttachments.addAll(db.getFiles());
                refreshPendingBar();
                success = true;
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    private void promptLinkErpRecord() {
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(this);
        dlg.setTitle("Link ERP Document");
        ComboBox<String> cmbType = new ComboBox<>();
        cmbType.getItems().addAll("SALE", "PURCHASE", "JOURNAL", "QUOTATION", "CREDIT_NOTE", "DEBIT_NOTE", "CUSTOMER", "SUPPLIER");
        cmbType.setValue("SALE");
        TextField txtDoc = new TextField();
        txtDoc.setPromptText("Document / Voucher No (e.g. IN/25-11-2026/0001)");
        VBox box = new VBox(10, new Label("Module"), cmbType, new Label("Reference No"), txtDoc);
        box.setPadding(new Insets(14));
        box.setPrefWidth(360);
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && txtDoc.getText() != null && !txtDoc.getText().isBlank()) {
            pendingDocType = cmbType.getValue();
            pendingDocNo = txtDoc.getText().trim();
            refreshPendingBar();
        }
    }

    private void refreshPendingBar() {
        pendingBar.getChildren().clear();
        boolean any = replyTarget != null || !pendingAttachments.isEmpty() || (pendingDocNo != null && !pendingDocNo.isBlank());
        pendingBar.setVisible(any);
        pendingBar.setManaged(any);
        if (!any) return;

        if (replyTarget != null) {
            Button clearReply = compactIconBtn("cancel", "Cancel reply", e -> { replyTarget = null; refreshPendingBar(); });
            Label lbl = new Label("Replying to " + replyTarget.senderName());
            HBox row = new HBox(6, lbl, new Region(), clearReply);
            HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            pendingBar.getChildren().add(row);
        }
        if (pendingDocNo != null && !pendingDocNo.isBlank()) {
            Button clearDoc = compactIconBtn("cancel", "Remove ERP link", e -> { pendingDocNo = null; pendingDocType = null; refreshPendingBar(); });
            Label lbl = new Label("Linked " + pendingDocType + ": " + pendingDocNo);
            HBox row = new HBox(6, IconFactory.compactIcon("link", 12), lbl, new Region(), clearDoc);
            HBox.setHgrow(row.getChildren().get(2), Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            pendingBar.getChildren().add(row);
        }
        for (File f : List.copyOf(pendingAttachments)) {
            Button clearFile = compactIconBtn("cancel", "Remove attachment", e -> { pendingAttachments.remove(f); refreshPendingBar(); });
            Label lbl = new Label(f.getName() + " (" + humanSize(f.length()) + ")");
            HBox row = new HBox(6, IconFactory.compactIcon("attachment", 12), lbl, new Region(), clearFile);
            HBox.setHgrow(row.getChildren().get(2), Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            pendingBar.getChildren().add(row);
        }
    }

    private void sendCurrentMessage() {
        String text = txtInput.getText() == null ? "" : txtInput.getText().trim();
        List<File> filesToSend = new ArrayList<>(pendingAttachments);
        String docType = pendingDocType;
        String docNo = pendingDocNo;
        Long replyId = replyTarget != null ? replyTarget.id() : null;
        if (text.isBlank() && filesToSend.isEmpty() && (docNo == null || docNo.isBlank())) return;

        txtInput.clear();
        pendingAttachments.clear();
        replyTarget = null;
        pendingDocType = null;
        pendingDocNo = null;
        refreshPendingBar();
        btnSend.setDisable(true);

        Thread.ofVirtual().name("chat-send-msg").start(() -> {
            try {
                MessageDto sent = filesToSend.isEmpty()
                        ? manager.api().sendText(channel.id(), text, replyId, docType, docNo)
                        : manager.api().sendWithAttachments(channel.id(), text, replyId, docType, docNo, filesToSend);
                Platform.runLater(() -> {
                    btnSend.setDisable(false);
                    upsertMessage(sent);
                    renderMessages(true);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    btnSend.setDisable(false);
                    txtInput.setText(text);
                    AppDialogService.error(this, "Send Failed", ex.getMessage());
                });
            }
        });
    }

    private void editMessagePrompt(MessageDto msg) {
        OwnedDialog<ButtonType> dlg = new OwnedDialog<>(this);
        dlg.setTitle("Edit Message");
        TextArea ta = new TextArea(msg.body() == null ? "" : msg.body());
        ta.setWrapText(true);
        ta.setPrefRowCount(3);
        VBox box = new VBox(10, ta);
        box.setPadding(new Insets(12));
        box.setPrefWidth(380);
        dlg.getDialogPane().setContent(box);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && ta.getText() != null && !ta.getText().isBlank()) {
            String next = ta.getText().trim();
            Thread.ofVirtual().name("chat-edit-msg").start(() -> {
                try {
                    MessageDto updated = manager.api().editMessage(msg.id(), next);
                    Platform.runLater(() -> { upsertMessage(updated); renderMessages(false); });
                } catch (Exception ex) {
                    Platform.runLater(() -> AppDialogService.error(this, "Edit Failed", ex.getMessage()));
                }
            });
        }
    }

    private void deleteMessageAsync(long messageId) {
        Thread.ofVirtual().name("chat-del-msg").start(() -> {
            try {
                MessageDto updated = manager.api().deleteMessage(messageId);
                Platform.runLater(() -> { upsertMessage(updated); renderMessages(false); });
            } catch (Exception ex) {
                Platform.runLater(() -> AppDialogService.error(this, "Delete Failed", ex.getMessage()));
            }
        });
    }

    private void toggleReactionAsync(long messageId, String emoji) {
        Thread.ofVirtual().name("chat-react").start(() -> {
            try {
                MessageDto updated = manager.api().react(messageId, emoji);
                Platform.runLater(() -> { upsertMessage(updated); renderMessages(false); });
            } catch (Exception ignored) {}
        });
    }

    private void maybeSendTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingSentMs < 2500) return;
        lastTypingSentMs = now;
        Thread.ofVirtual().name("chat-typing").start(() -> {
            try { manager.api().notifyTyping(channel.id(), true); } catch (Exception ignored) {}
        });
    }

    private void openLinkedDocument(String docType, String docNo) {
        String mod = docType == null ? "SALE" : docType.trim().toUpperCase(Locale.ROOT);
        LinkedRecordContext.open(mod, null, docNo, "VIEW", "TeamChat");
        if (onOpenErpRecord != null) {
            onOpenErpRecord.accept(mod);
        }
    }

    private Button compactIconBtn(String semantic, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Button b = new Button();
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setGraphic(IconFactory.compactIcon(semantic, 13));
        b.setTooltip(new Tooltip(tooltip));
        b.getStyleClass().addAll("approved-button", "chat-icon-btn");
        b.setOnAction(handler);
        return b;
    }

    private static Integer selfUserId() {
        var u = SessionService.current();
        return u != null ? u.getId() : null;
    }

    private static String initialsOf(String name) {
        if (name == null || name.isBlank()) return "U";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase(Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String formatMessageDateLabel(String utcIso) {
        if (utcIso == null || utcIso.isBlank()) return BusinessClock.formatDate(BusinessClock.today());
        try {
            ZonedDateTime zdt = Instant.parse(utcIso).atZone(ZoneId.systemDefault());
            LocalDate d = zdt.toLocalDate();
            LocalDate today = BusinessClock.today();
            if (d.equals(today)) return "Today";
            if (d.equals(today.minusDays(1))) return "Yesterday";
            return BusinessClock.formatDate(d);
        } catch (Exception e) {
            return BusinessClock.formatDate(BusinessClock.today());
        }
    }

    private static String formatMessageTime(String utcIso) {
        if (utcIso == null || utcIso.isBlank()) return "";
        try {
            ZonedDateTime zdt = Instant.parse(utcIso).atZone(ZoneId.systemDefault());
            return BusinessClock.formatTime(zdt.toLocalTime());
        } catch (Exception e) {
            return "";
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
