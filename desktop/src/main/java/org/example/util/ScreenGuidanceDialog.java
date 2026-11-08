package org.example.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

/**
 * Universal Operational Guidance & Screen Information Dialog.
 * Displays step-by-step workflows, bold green best practices, and bold red critical alerts.
 */
public final class ScreenGuidanceDialog {

    private ScreenGuidanceDialog() {}

    public static void show(Object ownerTarget, ScreenGuidance guidance) {
        if (guidance == null) return;

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(ownerTarget);
        dialog.setTitle(guidance.screenTitle() + " — Operational Guide");
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        VBox contentBox = new VBox(14);
        contentBox.setPadding(new Insets(16, 20, 16, 20));
        contentBox.getStyleClass().addAll("erp-guidance-dialog-content", "approved-card");

        // 1. Hero Header
        HBox heroHeader = new HBox(14);
        heroHeader.setAlignment(Pos.CENTER_LEFT);
        heroHeader.getStyleClass().addAll("erp-guidance-header-box", "approved-section-header");

        StackPane iconBox = new StackPane();
        iconBox.setPrefSize(44, 44);
        iconBox.getStyleClass().addAll("erp-page-header-icon");
        iconBox.getChildren().setAll(IconFactory.icon(guidance.iconName(), 24));

        VBox titleCol = new VBox(3);
        Label lblTitle = new Label(guidance.screenTitle());
        lblTitle.getStyleClass().addAll("screen-title", "erp-guidance-title");

        Label lblSummary = new Label(guidance.overviewSummary());
        lblSummary.setWrapText(true);
        lblSummary.getStyleClass().addAll("screen-subtitle", "erp-guidance-subtitle");

        titleCol.getChildren().addAll(lblTitle, lblSummary);
        HBox.setHgrow(titleCol, Priority.ALWAYS);
        heroHeader.getChildren().addAll(iconBox, titleCol);

        contentBox.getChildren().add(heroHeader);

        // 2. Core Workflows
        if (!guidance.coreWorkflows().isEmpty()) {
            VBox cardWorkflows = new VBox(8);
            cardWorkflows.getStyleClass().addAll("approved-card", "erp-guidance-card");
            cardWorkflows.setPadding(new Insets(10, 14, 10, 14));

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label lblCardTitle = new Label("Core Operational Workflow");
            lblCardTitle.getStyleClass().addAll("section-title", "erp-guidance-card-title");
            cardHeader.getChildren().addAll(IconFactory.compactIcon("check", 15), lblCardTitle);
            cardWorkflows.getChildren().add(cardHeader);

            int step = 1;
            for (String wf : guidance.coreWorkflows()) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.TOP_LEFT);

                Label badge = new Label(String.valueOf(step++));
                badge.getStyleClass().add("wizard-badge");
                badge.setStyle("-fx-min-width: 22px; -fx-min-height: 22px; -fx-font-size: 11px;");

                Label lblText = new Label(wf);
                lblText.setWrapText(true);
                lblText.getStyleClass().add("erp-guidance-item-text");
                HBox.setHgrow(lblText, Priority.ALWAYS);

                row.getChildren().addAll(badge, lblText);
                cardWorkflows.getChildren().add(row);
            }
            contentBox.getChildren().add(cardWorkflows);
        }

        // 3. Important Guidelines (Bold Green)
        if (!guidance.importantPoints().isEmpty()) {
            VBox cardImportant = new VBox(8);
            cardImportant.getStyleClass().addAll("approved-card", "erp-guidance-card", "erp-guidance-card-green");
            cardImportant.setPadding(new Insets(10, 14, 10, 14));

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label lblCardTitle = new Label("Important Guidelines & Best Practices");
            lblCardTitle.getStyleClass().addAll("section-title", "erp-guidance-card-title");
            cardHeader.getChildren().addAll(IconFactory.compactIcon("document", 15), lblCardTitle);
            cardImportant.getChildren().add(cardHeader);

            for (String pt : guidance.importantPoints()) {
                HBox row = new HBox(8);
                row.setAlignment(Pos.TOP_LEFT);

                Label bullet = new Label("✓");
                bullet.getStyleClass().add("erp-guidance-bold-green");

                Label lblText = new Label(pt);
                lblText.setWrapText(true);
                lblText.getStyleClass().addAll("erp-guidance-bold-green", "erp-guidance-item-text");
                HBox.setHgrow(lblText, Priority.ALWAYS);

                row.getChildren().addAll(bullet, lblText);
                cardImportant.getChildren().add(row);
            }
            contentBox.getChildren().add(cardImportant);
        }

        // 4. Critical Alerts & Warnings (Bold Red)
        if (!guidance.criticalAlerts().isEmpty()) {
            VBox cardAlerts = new VBox(8);
            cardAlerts.getStyleClass().addAll("approved-card", "erp-guidance-card", "erp-guidance-card-red");
            cardAlerts.setPadding(new Insets(10, 14, 10, 14));

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);
            Label lblCardTitle = new Label("Critical Alerts, Safeguards & Warning Rules");
            lblCardTitle.getStyleClass().addAll("section-title", "erp-guidance-card-title");
            cardHeader.getChildren().addAll(IconFactory.compactIcon("warning", 15), lblCardTitle);
            cardAlerts.getChildren().add(cardHeader);

            for (String alert : guidance.criticalAlerts()) {
                HBox row = new HBox(8);
                row.setAlignment(Pos.TOP_LEFT);

                Label bullet = new Label("⚠️");
                bullet.getStyleClass().add("erp-guidance-bold-red");

                Label lblText = new Label(alert);
                lblText.setWrapText(true);
                lblText.getStyleClass().addAll("erp-guidance-bold-red", "erp-guidance-item-text");
                HBox.setHgrow(lblText, Priority.ALWAYS);

                row.getChildren().addAll(bullet, lblText);
                cardAlerts.getChildren().add(row);
            }
            contentBox.getChildren().add(cardAlerts);
        }

        // 5. Shortcuts
        if (!guidance.keyboardShortcuts().isEmpty()) {
            HBox shortcutsBar = new HBox(12);
            shortcutsBar.setAlignment(Pos.CENTER_LEFT);
            shortcutsBar.getStyleClass().addAll("approved-card", "erp-guidance-card");
            shortcutsBar.setPadding(new Insets(8, 14, 8, 14));

            Label lblScTitle = new Label("Shortcuts:");
            lblScTitle.getStyleClass().addAll("metric-label", "field-caption");
            shortcutsBar.getChildren().add(lblScTitle);

            for (String sc : guidance.keyboardShortcuts()) {
                Label pill = new Label(sc);
                pill.getStyleClass().addAll("doc-template-badge", "erp-table-value-colour-info");
                pill.setStyle("-fx-font-family: monospace; -fx-font-weight: bold; -fx-padding: 3 8;");
                shortcutsBar.getChildren().add(pill);
            }
            contentBox.getChildren().add(shortcutsBar);
        }

        ScrollPane scroll = new ScrollPane(contentBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setPrefWidth(780);
        scroll.setMaxWidth(840);
        scroll.setPrefHeight(580);
        scroll.getStyleClass().add("clean-scroll");

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "erp-guidance-dialog");
        pane.setContent(scroll);

        ButtonType btnClose = new ButtonType("Close Guide", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(btnClose);

        AppDialogRenderer.install(dialog);
        dialog.showAndWait();
    }
}
