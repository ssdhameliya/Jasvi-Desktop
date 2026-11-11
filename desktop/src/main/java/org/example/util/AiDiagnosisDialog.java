package org.example.util;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.example.ai.ErpAgentService;
import org.example.ai.JasviCopilotController;
import org.example.service.NotificationService;
import org.example.theme.ThemeManager;

import java.util.Optional;

/**
 * Enterprise AI Error Diagnostic Assistant Dialog for Jasvi ERP Desktop.
 *
 * <p>Activated when the user clicks [AI Diagnose] on any error alert or operational failure popup.
 * Analyzes the runtime error in real time using the Google Antigravity Agent (with Gemini API)
 * or the expert deterministic heuristic engine when offline, presenting a plain-English
 * root cause explanation, step-by-step fix recommendations, and 1-click clipboard export.</p>
 */
public final class AiDiagnosisDialog {

    private AiDiagnosisDialog() {}

    public static void show(Node owner, String title, String heading, String visibleMessage, String technicalDetail) {
        String cleanTitle = title != null && !title.isBlank() ? title : "Operation Failure";
        String cleanHeading = heading != null && !heading.isBlank() ? heading : "Unexpected System Error";
        String cleanMessage = visibleMessage != null && !visibleMessage.isBlank() ? visibleMessage : "An issue occurred during execution.";

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("AI Diagnostic Assistant — DSE Enterprise");
        AppDialogRenderer.configureWorkspace(dialog, "diagnostic", "AI Diagnostic Assistant", cleanHeading, cleanMessage, technicalDetail);

        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(720);
        pane.setMinHeight(460);
        pane.setPrefWidth(760);
        pane.setPrefHeight(500);

        // Header Section
        Label lblKicker = new Label("ENTERPRISE ANTIGRAVITY AI DIAGNOSTICS");
        lblKicker.getStyleClass().addAll("erp-dialog-kicker", "approved-status-info");

        Label lblMainTitle = new Label("Intelligent Error Analysis & Solution Guidance");
        lblMainTitle.getStyleClass().addAll("erp-dialog-title", "approved-hero-title");

        Label lblBadge = new Label("Analyzing with Antigravity AI…");
        lblBadge.getStyleClass().addAll("badge", "badge-pill", "status-neutral");
        lblBadge.setGraphic(IconFactory.compactIcon("refresh-cw", 12));

        HBox titleRow = new HBox(12, lblMainTitle, lblBadge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(lblMainTitle, Priority.ALWAYS);

        VBox headerBox = new VBox(4, lblKicker, titleRow);
        headerBox.getStyleClass().add("erp-dialog-header-banner");

        // Error Context Card
        Label lblContextTitle = new Label("Triggering Error Context");
        lblContextTitle.getStyleClass().addAll("erp-kpi-label", "erp-label-bold");
        lblContextTitle.setGraphic(IconFactory.compactIcon("alert-circle", 14));

        Label lblErrorMsg = new Label(cleanMessage);
        lblErrorMsg.setWrapText(true);
        lblErrorMsg.getStyleClass().addAll("erp-dialog-error-text", "erp-table-value-colour-red");

        VBox contextCard = new VBox(6, lblContextTitle, lblErrorMsg);
        contextCard.getStyleClass().addAll("erp-stat-card", "erp-card-elevated");
        contextCard.setPadding(new Insets(12, 14, 12, 14));

        // Root Cause Card
        Label lblCauseTitle = new Label("Identified Root Cause");
        lblCauseTitle.getStyleClass().addAll("erp-kpi-label", "erp-label-bold");
        lblCauseTitle.setGraphic(IconFactory.compactIcon("target", 14));

        Label lblCauseText = new Label("Performing intelligent diagnosis of stack trace and input parameters…");
        lblCauseText.setWrapText(true);
        lblCauseText.getStyleClass().add("erp-body-copy");

        VBox causeCard = new VBox(6, lblCauseTitle, lblCauseText);
        causeCard.getStyleClass().addAll("erp-stat-card", "erp-card-elevated");
        causeCard.setPadding(new Insets(12, 14, 12, 14));

        // Recommended Solution Card
        Label lblFixTitle = new Label("Recommended Action & Resolution Steps");
        lblFixTitle.getStyleClass().addAll("erp-kpi-label", "erp-label-bold");
        lblFixTitle.setGraphic(IconFactory.compactIcon("check-circle", 14));

        Label lblFixText = new Label("Formulating actionable guidance…");
        lblFixText.setWrapText(true);
        lblFixText.getStyleClass().addAll("erp-body-copy", "erp-table-value-colour-green");

        FlowPane selfHealStrip = new FlowPane(8, 8);
        selfHealStrip.setAlignment(Pos.CENTER_LEFT);
        for (ErpAgentService.CopilotAction action : ErpAgentService.getInstance().inferSelfHealingActions(cleanTitle, cleanMessage, technicalDetail)) {
            Button btnHeal = new Button(action.label());
            btnHeal.getStyleClass().addAll("approved-button", "approved-secondary-button");
            UiActionIcons.apply(btnHeal, action.semanticIcon(), action.label());
            btnHeal.setOnAction(e -> JasviCopilotController.executeCopilotAction(action.actionCode(), owner, dialog, null, null, null));
            selfHealStrip.getChildren().add(btnHeal);
        }

        VBox fixCard = new VBox(8, lblFixTitle, lblFixText, selfHealStrip);
        fixCard.getStyleClass().addAll("erp-stat-card", "erp-card-elevated");
        fixCard.setPadding(new Insets(12, 14, 12, 14));

        VBox mainContent = new VBox(12, headerBox, contextCard, causeCard, fixCard);
        mainContent.setPadding(new Insets(16, 20, 16, 20));
        VBox.setVgrow(mainContent, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(mainContent);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().addAll("edge-to-edge", "erp-transparent-scroll");

        pane.setContent(scroll);

        // Buttons
        ButtonType btnCopy = new ButtonType("Copy Diagnosis", ButtonBar.ButtonData.LEFT);
        ButtonType btnViewLogs = new ButtonType("View System Logs", ButtonBar.ButtonData.OTHER);
        ButtonType btnClose = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

        pane.getButtonTypes().setAll(btnCopy, btnViewLogs, btnClose);

        // Asynchronously query ErpAgentService
        ErpAgentService.getInstance().diagnoseError(cleanTitle, cleanHeading, cleanMessage, technicalDetail)
                .thenAccept(diagnosis -> Platform.runLater(() -> {
                    if (diagnosis.isAiGenerated()) {
                        lblBadge.setText("AI Generated Diagnosis");
                        lblBadge.getStyleClass().setAll("badge", "badge-pill", "status-positive");
                        lblBadge.setGraphic(IconFactory.compactIcon("cpu", 12));
                    } else {
                        lblBadge.setText("Deterministic Heuristic Diagnosis");
                        lblBadge.getStyleClass().setAll("badge", "badge-pill", "status-info");
                        lblBadge.setGraphic(IconFactory.compactIcon("shield-check", 12));
                    }

                    lblCauseText.setText(diagnosis.rootCause());
                    lblFixText.setText(diagnosis.recommendedFix());
                }))
                .exceptionally(err -> {
                    Platform.runLater(() -> {
                        lblBadge.setText("Fallback Diagnostic");
                        lblBadge.getStyleClass().setAll("badge", "badge-pill", "status-warning");
                        lblCauseText.setText("Diagnosis engine encountered an internal exception: " + err.getMessage());
                        lblFixText.setText("Please review application inputs or inspect raw server logs via [View System Logs].");
                    });
                    return null;
                });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent()) {
            if (result.get() == btnCopy) {
                ClipboardContent content = new ClipboardContent();
                String report = String.format("""
                        === DSE ERP AI DIAGNOSTIC REPORT ===
                        Error Context: %s • %s
                        Message: %s
                        Root Cause: %s
                        Recommended Fix: %s
                        ====================================
                        """, cleanTitle, cleanHeading, cleanMessage, lblCauseText.getText(), lblFixText.getText());
                content.putString(report);
                Clipboard.getSystemClipboard().setContent(content);
                NotificationService.createNotification("Copied to Clipboard", "AI diagnosis report copied successfully.", "INFO");
            } else if (result.get() == btnViewLogs) {
                Platform.runLater(() -> SystemDiagnosticsDialog.show(owner));
            }
        }
    }
}
