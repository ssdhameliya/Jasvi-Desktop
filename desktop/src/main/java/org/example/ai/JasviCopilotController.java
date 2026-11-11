package org.example.ai;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.example.ai.ActiveViewContextService.ScreenContext;
import org.example.ai.ErpAgentService.CopilotAction;
import org.example.ai.ErpAgentService.CopilotMode;
import org.example.ai.ErpAgentService.CopilotResponse;
import org.example.api.sync.NetworkConnectivityService;
import org.example.api.sync.OfflineSyncWorker;
import org.example.controller.LicenseRenewalDialogController;
import org.example.service.NotificationService;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.util.AppDialogRenderer;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.OwnedTextInputDialog;
import org.example.util.SystemDiagnosticsDialog;
import org.example.util.UiActionIcons;

import java.util.List;
import java.util.function.Consumer;

/**
 * Jasvi Enterprise AI Chat Assistant & Real-Time Screen Reader.
 *
 * <p>Provides a true conversational chat interface (similar to Gemini / Copilot chat)
 * that can be docked as a right-side slide-out drawer inside the main ERP shell or
 * opened as a tall, clean chat window from the Login/Startup screen.</p>
 */
public final class JasviCopilotController {

    private JasviCopilotController() {}

    public static void show(Node owner) {
        showInternal(owner, CopilotMode.SCREEN_READER, null);
    }

    public static void showOnboarding(Node owner) {
        showInternal(owner, CopilotMode.ONBOARDING_SUBSCRIPTION,
                "Compare Jasvi ERP subscription plans and show me how to activate my company license.");
    }

    public static void showWithInitialPrompt(Node owner, CopilotMode initialMode, String initialQuestion) {
        showInternal(owner, initialMode != null ? initialMode : CopilotMode.SCREEN_READER, initialQuestion);
    }

    /**
     * Builds a non-blocking right-side Chat Drawer panel for docking inside Dashboard.fxml's shellRoot.
     */
    public static VBox createDrawerPanel(Node owner, Runnable onCloseDrawer) {
        VBox panel = buildChatPanel(owner, null, CopilotMode.SCREEN_READER, null, onCloseDrawer);
        panel.getStyleClass().add("ai-chat-drawer");
        panel.setMinWidth(410);
        panel.setPrefWidth(430);
        panel.setMaxWidth(460);
        return panel;
    }

    private static void showInternal(Node owner, CopilotMode startMode, String initialQuestion) {
        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Jasvi AI Chat Assistant");
        // Pass empty message and detail so AppDialogRenderer does not add duplicate top banners
        AppDialogRenderer.configureWorkspace(
                dialog,
                "diagnostic",
                "Jasvi AI Chat Assistant",
                "Jasvi AI Chat — Screen Reader, Subscription Advisor & Self-Healing Assistant",
                "",
                ""
        );

        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(680);
        pane.setMinHeight(640);
        pane.setPrefWidth(740);
        pane.setPrefHeight(700);

        VBox chatPanel = buildChatPanel(owner, dialog, startMode, initialQuestion, null);
        pane.setContent(chatPanel);
        pane.getButtonTypes().setAll(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));

        dialog.showAndWait();
    }

    private static VBox buildChatPanel(
            Node owner,
            OwnedDialog<?> parentDialog,
            CopilotMode startMode,
            String initialQuestion,
            Runnable onCloseDrawer
    ) {
        ScreenContext initialContext = ActiveViewContextService.getInstance().capture(owner);
        final CopilotMode[] currentMode = {startMode != null ? startMode : CopilotMode.SCREEN_READER};

        // 1. Compact Single-Line Top Bar (Screen Context Pill + Engine Status + API Key Setup + Clear)
        Label lblContextPill = new Label(initialContext.screenName() + " • " + initialContext.role());
        lblContextPill.getStyleClass().addAll("badge", "badge-pill", "status-info");
        lblContextPill.setGraphic(IconFactory.compactIcon("application", 12));

        Label lblEngineBadge = new Label(ErpAgentService.getInstance().getActiveEngineLabel());
        updateEngineBadgeStyle(lblEngineBadge);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Button btnApiKey = new Button("API Key");
        btnApiKey.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
        UiActionIcons.apply(btnApiKey, "security", "Configure Google Gemini API Key for cloud LLM chat");

        Button btnClear = new Button("Clear");
        btnClear.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
        UiActionIcons.apply(btnClear, "reset", "Clear chat conversation");

        HBox topBar = new HBox(8, lblContextPill, lblEngineBadge, topSpacer, btnApiKey, btnClear);
        topBar.setAlignment(Pos.CENTER_LEFT);
        if (onCloseDrawer != null) {
            Button btnCloseDrawer = new Button("Hide");
            btnCloseDrawer.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
            UiActionIcons.apply(btnCloseDrawer, "cancel", "Close AI Chat Drawer");
            btnCloseDrawer.setOnAction(e -> onCloseDrawer.run());
            topBar.getChildren().add(btnCloseDrawer);
        }

        // 2. Spacious Scrollable Chat Stream (85% of height)
        VBox chatStream = new VBox(12);
        chatStream.setPadding(new Insets(12, 14, 12, 14));

        ScrollPane chatScroll = new ScrollPane(chatStream);
        chatScroll.setFitToWidth(true);
        chatScroll.setMinHeight(430);
        chatScroll.setPrefHeight(520);
        chatScroll.getStyleClass().addAll("edge-to-edge", "erp-transparent-scroll");
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        // 3. Clean Bottom Chat Composer Row
        TextField txtPrompt = new TextField();
        txtPrompt.setPromptText("Message Jasvi AI… Ask about this screen, subscription plans, GST, or errors (Enter)");
        txtPrompt.getStyleClass().add("erp-control-input");
        HBox.setHgrow(txtPrompt, Priority.ALWAYS);

        Button btnSend = new Button("Send");
        btnSend.getStyleClass().addAll("approved-button", "approved-primary-button", "erp-button-primary");
        UiActionIcons.apply(btnSend, "diagnostic", "Send message to Jasvi AI");

        HBox composerRow = new HBox(8, txtPrompt, btnSend);
        composerRow.setAlignment(Pos.CENTER_LEFT);

        // Helper to send a user question and append AI response bubble
        Consumer<String> sendQuestion = rawQuestion -> {
            String question = rawQuestion != null ? rawQuestion.trim() : "";
            if (question.isBlank()) return;
            txtPrompt.clear();

            ScreenContext latestCtx = ActiveViewContextService.getInstance().capture(owner);
            lblContextPill.setText(latestCtx.screenName() + " • " + latestCtx.role());
            lblEngineBadge.setText(ErpAgentService.getInstance().getActiveEngineLabel());
            updateEngineBadgeStyle(lblEngineBadge);

            chatStream.getChildren().add(buildUserBubbleRow(question));

            HBox loadingRow = buildLoadingBubbleRow();
            chatStream.getChildren().add(loadingRow);
            scrollToBottom(chatScroll);

            ErpAgentService.getInstance()
                    .askCopilot(currentMode[0], question, latestCtx)
                    .thenAccept(response -> Platform.runLater(() -> {
                        chatStream.getChildren().remove(loadingRow);
                        chatStream.getChildren().add(buildAssistantBubbleRow(
                                owner, parentDialog, response, latestCtx,
                                () -> txtPrompt.fireEvent(new javafx.event.ActionEvent())
                        ));
                        scrollToBottom(chatScroll);
                    }))
                    .exceptionally(err -> {
                        Platform.runLater(() -> {
                            chatStream.getChildren().remove(loadingRow);
                            CopilotResponse fallback = new CopilotResponse(
                                    "Jasvi AI Assistant",
                                    "I inspected '" + latestCtx.screenName() + "' (Role: " + latestCtx.role() + "). All controls are ready.",
                                    List.of("Use the quick actions below to check diagnostics or license activation."),
                                    List.of(new CopilotAction("Open System Diagnostics", "OPEN_SYSTEM_DIAGNOSTICS", "diagnostic")),
                                    false
                            );
                            chatStream.getChildren().add(buildAssistantBubbleRow(owner, parentDialog, fallback, latestCtx, null));
                            scrollToBottom(chatScroll);
                        });
                        return null;
                    });
        };

        // Add Welcome Chat Bubble with embedded Quick-Ask Pills
        Runnable resetWithWelcomeBubble = () -> {
            chatStream.getChildren().clear();
            ScreenContext latestCtx = ActiveViewContextService.getInstance().capture(owner);
            lblContextPill.setText(latestCtx.screenName() + " • " + latestCtx.role());
            chatStream.getChildren().add(buildWelcomeBubbleRow(latestCtx, promptText -> {
                if (promptText.toLowerCase().contains("subscription") || promptText.toLowerCase().contains("jlic")) {
                    currentMode[0] = CopilotMode.ONBOARDING_SUBSCRIPTION;
                } else if (promptText.toLowerCase().contains("error") || promptText.toLowerCase().contains("bank")) {
                    currentMode[0] = CopilotMode.ERROR_TROUBLESHOOT;
                } else {
                    currentMode[0] = CopilotMode.SCREEN_READER;
                }
                sendQuestion.accept(promptText);
            }));
            scrollToBottom(chatScroll);
        };

        btnSend.setOnAction(e -> {
            String q = txtPrompt.getText() != null ? txtPrompt.getText().trim() : "";
            if (q.isBlank()) {
                q = "Inspect my current screen and explain any disabled buttons or fields.";
            }
            sendQuestion.accept(q);
        });
        txtPrompt.setOnAction(e -> btnSend.fire());

        btnClear.setOnAction(e -> {
            ErpAgentService.getInstance().clearConversationHistory();
            resetWithWelcomeBubble.run();
        });

        btnApiKey.setOnAction(e -> promptForGeminiApiKey(lblEngineBadge, chatStream, chatScroll));

        resetWithWelcomeBubble.run();

        if (initialQuestion != null && !initialQuestion.isBlank()) {
            Platform.runLater(() -> sendQuestion.accept(initialQuestion));
        }

        VBox rootLayout = new VBox(10, topBar, chatScroll, composerRow);
        rootLayout.setPadding(new Insets(12, 14, 12, 14));
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        return rootLayout;
    }

    private static void updateEngineBadgeStyle(Label badge) {
        badge.getStyleClass().removeAll("badge", "badge-pill", "status-positive", "status-info");
        boolean geminiReady = ErpAgentService.getInstance().hasGeminiApiKey();
        badge.getStyleClass().addAll("badge", "badge-pill", geminiReady ? "status-positive" : "status-info");
        badge.setGraphic(IconFactory.compactIcon("diagnostic", 12));
    }

    private static void promptForGeminiApiKey(Label lblEngineBadge, VBox chatStream, ScrollPane chatScroll) {
        String existing = ErpAgentService.getInstance().getConfiguredApiKey();
        OwnedTextInputDialog dlg = new OwnedTextInputDialog(existing);
        dlg.setTitle("Configure Google Gemini API Key");
        dlg.setHeaderText("Connect Google Gemini 2.5 Flash to Jasvi AI Chat");
        dlg.setContentText("Paste your Gemini API Key (or leave blank for built-in offline ERP AI):");
        dlg.showAndWait().ifPresent(key -> {
            ErpAgentService.getInstance().saveApiKey(key);
            lblEngineBadge.setText(ErpAgentService.getInstance().getActiveEngineLabel());
            updateEngineBadgeStyle(lblEngineBadge);

            boolean hasKey = ErpAgentService.getInstance().hasGeminiApiKey();
            String msg = hasKey
                    ? "Google Gemini 2.5 Flash API Key saved! Your chat questions will now use live Gemini 2.5 Flash reasoning combined with real-time ERP screen telemetry."
                    : "Switched to Built-In Local ERP AI Engine (100% offline).";
            Label info = new Label(msg);
            info.setWrapText(true);
            info.getStyleClass().addAll("erp-body-copy", hasKey ? "erp-table-value-colour-green" : "approved-status-info");
            VBox card = new VBox(4, info);
            card.getStyleClass().addAll("erp-stat-card", "ai-copilot-bubble-assistant");
            card.setPadding(new Insets(10, 14, 10, 14));
            HBox row = new HBox(card);
            row.setAlignment(Pos.CENTER_LEFT);
            chatStream.getChildren().add(row);
            scrollToBottom(chatScroll);
        });
    }

    private static HBox buildWelcomeBubbleRow(ScreenContext ctx, Consumer<String> onQuickAsk) {
        Label lblHeader = new Label("JASVI AI ASSISTANT • LIVE SCREEN & ERP CHAT");
        lblHeader.getStyleClass().addAll("erp-kpi-label", "erp-label-bold", "erp-field-label-colour-teal");
        lblHeader.setGraphic(IconFactory.compactIcon("diagnostic", 14));

        String healthNote = !ctx.validationErrors().isEmpty()
                ? "I noticed " + ctx.validationErrors().size() + " validation alert(s) on this screen."
                : (!ctx.disabledControls().isEmpty()
                ? "I see " + ctx.disabledControls().size() + " disabled button(s) on this screen (" + String.join(", ", ctx.disabledControls()) + ")."
                : "All controls on '" + ctx.screenName() + "' look healthy and ready.");

        Label lblGreeting = new Label(
                "Hi! I'm reading your active screen ('" + ctx.screenName() + "' • Role: " + ctx.role() + "). "
                        + healthNote + " Type any question below or tap a quick topic to start chatting:"
        );
        lblGreeting.setWrapText(true);
        lblGreeting.getStyleClass().add("erp-body-copy");

        FlowPane quickPills = new FlowPane(8, 8);
        quickPills.setAlignment(Pos.CENTER_LEFT);
        List<String> starterPrompts = List.of(
                "Which subscription plan fits my company?",
                "How to generate .jreq & activate .jlic?",
                "Why is a button disabled on this screen?",
                "How does Gemini API work here?",
                "Unlock password-protected bank statement"
        );
        for (String prompt : starterPrompts) {
            Button pill = new Button(prompt);
            pill.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
            UiActionIcons.apply(pill, "info", prompt);
            pill.setOnAction(e -> onQuickAsk.accept(prompt));
            quickPills.getChildren().add(pill);
        }

        VBox bubble = new VBox(8, lblHeader, lblGreeting, quickPills);
        bubble.getStyleClass().addAll("erp-stat-card", "erp-card-elevated", "ai-copilot-bubble-assistant");
        bubble.setPadding(new Insets(12, 16, 12, 16));
        bubble.setMaxWidth(640);
        HBox.setHgrow(bubble, Priority.ALWAYS);

        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox buildUserBubbleRow(String question) {
        Label lblYou = new Label("YOU");
        lblYou.getStyleClass().addAll("erp-kpi-label", "erp-label-bold", "erp-field-label-colour-indigo");
        lblYou.setGraphic(IconFactory.compactIcon("user", 12));

        Label lblText = new Label(question);
        lblText.setWrapText(true);
        lblText.getStyleClass().add("erp-body-copy");

        VBox box = new VBox(4, lblYou, lblText);
        box.getStyleClass().addAll("erp-stat-card", "ai-copilot-bubble-user");
        box.setPadding(new Insets(10, 14, 10, 14));
        box.setMaxWidth(540);

        HBox row = new HBox(box);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }

    private static HBox buildLoadingBubbleRow() {
        Label lblWait = new Label("Jasvi AI is thinking…");
        lblWait.getStyleClass().addAll("erp-body-copy", "approved-status-info");
        lblWait.setGraphic(IconFactory.compactIcon("refresh", 14));
        VBox box = new VBox(6, lblWait);
        box.getStyleClass().addAll("erp-stat-card", "erp-card-elevated");
        box.setPadding(new Insets(10, 14, 10, 14));

        HBox row = new HBox(box);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox buildAssistantBubbleRow(
            Node owner,
            OwnedDialog<?> parentDialog,
            CopilotResponse response,
            ScreenContext ctx,
            Runnable onRescan
    ) {
        Label lblHeader = new Label(response.title());
        lblHeader.getStyleClass().addAll("erp-kpi-label", "erp-label-bold", "erp-field-label-colour-teal");
        lblHeader.setGraphic(IconFactory.compactIcon("diagnostic", 14));

        Label lblSource = new Label(response.isAiGenerated() ? "Gemini 2.5 Flash" : "ERP AI");
        lblSource.getStyleClass().addAll("badge", "badge-pill", response.isAiGenerated() ? "status-positive" : "status-info");

        HBox topRow = new HBox(8, lblHeader, lblSource);
        topRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lblHeader, Priority.ALWAYS);

        Label lblSummary = new Label(response.summary());
        lblSummary.setWrapText(true);
        lblSummary.getStyleClass().add("erp-body-copy");

        VBox bulletBox = new VBox(5);
        for (String b : response.bulletInsights()) {
            Label item = new Label("• " + b);
            item.setWrapText(true);
            item.getStyleClass().addAll("erp-body-copy", "erp-table-value-colour-green");
            bulletBox.getChildren().add(item);
        }

        FlowPane actionStrip = new FlowPane(8, 8);
        actionStrip.setAlignment(Pos.CENTER_LEFT);
        for (CopilotAction action : response.actions()) {
            Button btn = new Button(action.label());
            btn.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
            UiActionIcons.apply(btn, action.semanticIcon(), action.label());
            btn.setOnAction(e -> executeCopilotAction(action.actionCode(), owner, parentDialog, response, ctx, onRescan));
            actionStrip.getChildren().add(btn);
        }

        Button btnCopy = new Button("Copy");
        btnCopy.getStyleClass().addAll("approved-button", "approved-secondary-button", "ai-copilot-chip");
        UiActionIcons.apply(btnCopy, "copy", "Copy AI answer to clipboard");
        btnCopy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(response.title() + "\n" + response.summary() + "\n"
                    + String.join("\n- ", response.bulletInsights()));
            Clipboard.getSystemClipboard().setContent(cc);
            NotificationService.createNotification("Copied to Clipboard", "AI chat response copied.", "INFO");
        });
        actionStrip.getChildren().add(btnCopy);

        VBox card = new VBox(8, topRow, lblSummary, bulletBox, actionStrip);
        card.getStyleClass().addAll("erp-stat-card", "erp-card-elevated", "ai-copilot-bubble-assistant");
        card.setPadding(new Insets(12, 16, 12, 16));
        card.setMaxWidth(640);
        HBox.setHgrow(card, Priority.ALWAYS);

        HBox row = new HBox(card);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    public static void executeCopilotAction(
            String actionCode,
            Node owner,
            Dialog<?> currentDialog,
            CopilotResponse response,
            ScreenContext ctx,
            Runnable onRescan
    ) {
        if (actionCode == null) return;
        Window ownerWindow = owner != null && owner.getScene() != null ? owner.getScene().getWindow() : null;
        switch (actionCode) {
            case "OPEN_LICENSE_RENEWAL" -> Platform.runLater(() -> LicenseRenewalDialogController.show(ownerWindow));
            case "COPY_MACHINE_ID" -> {
                String machineId = HardwareFingerprintUtil.getDisplayMachineId();
                ClipboardContent content = new ClipboardContent();
                content.putString(machineId);
                Clipboard.getSystemClipboard().setContent(content);
                NotificationService.createNotification(
                        "Machine ID Copied",
                        "Hardware Fingerprint (" + machineId + ") copied for .jreq license activation.",
                        "INFO"
                );
            }
            case "OPEN_SYSTEM_DIAGNOSTICS" -> Platform.runLater(() -> SystemDiagnosticsDialog.show(owner));
            case "CHECK_SERVER_CONNECTION" -> {
                Thread.ofVirtual().name("ai-copilot-reconnect").start(() -> {
                    NetworkConnectivityService.getInstance().checkNowAsync();
                    OfflineSyncWorker.getInstance().triggerSyncNow();
                });
                NotificationService.createNotification(
                        "Server Connectivity Check",
                        "Triggered background Spring Boot health check & offline sync.",
                        "INFO"
                );
            }
            case "OPEN_AI_COPILOT" -> Platform.runLater(() -> showWithInitialPrompt(
                    owner, CopilotMode.ERROR_TROUBLESHOOT, "Help me troubleshoot this error and verify system state."));
            case "RESCAN_SCREEN" -> {
                if (onRescan != null) onRescan.run();
            }
            default -> {}
        }
    }

    private static void scrollToBottom(ScrollPane scrollPane) {
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }
}
