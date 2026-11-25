package org.example.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 95% Responsive Inspection Modal for Communication Center log entries.
 * Solves multi-recipient email clipping by rendering every recipient address
 * as an individual copyable semantic chip, alongside the full unwrapped subject,
 * SMTP/WAHA delivery diagnostics, full attempt history timeline, and direct
 * document linkage & resend actions.
 */
public final class CommunicationInspectionDialog {

    private CommunicationInspectionDialog() {}

    public record CommunicationSnapshot(
            String formattedTime,
            String documentLabel,
            String entityType,
            int entityId,
            String channel,
            String rawRecipient,
            String subject,
            String status,
            String errorMessage,
            String createdBy,
            String attemptText,
            int totalAttempts
    ) {}

    /** Parses comma/semicolon-separated recipients cleanly with case-insensitive deduplication. */
    public static List<String> parseRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String token : raw.split("[;,]")) {
            String clean = token == null ? "" : token.trim();
            if (clean.isEmpty()) continue;
            if (seen.add(clean.toLowerCase(Locale.ROOT))) {
                out.add(clean);
            }
        }
        return out;
    }

    public static void show(Node ownerNode,
                            CommunicationSnapshot entry,
                            List<CommunicationSnapshot> relatedAttempts,
                            Runnable onOpenDocument,
                            Runnable onResend) {
        if (entry == null) return;

        Dialog<ButtonType> dialog = new OwnedDialog<>(ownerNode);
        dialog.setTitle("Communication Details — " + safe(entry.documentLabel()));
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("dse-dialog-pane", "approved-dialog", "comm-inspection-dialog", "erp-responsive-dialog");
        pane.getProperties().put("dse.dialog.presented", true);
        PopupTableWorkspace.applyInspectionSizing(dialog, ownerNode);

        List<String> recipients = parseRecipients(entry.rawRecipient());
        boolean isEmail = "EMAIL".equalsIgnoreCase(entry.channel());
        boolean isFailed = "FAILED".equalsIgnoreCase(entry.status());

        // 1. Branded Channel-Colored Top Accent Bar & Header Hero (Blue for Email, Green for WhatsApp)
        Region topAccent = new Region();
        topAccent.setMinHeight(5);
        topAccent.setPrefHeight(5);
        topAccent.setMaxHeight(5);
        topAccent.getStyleClass().addAll("dse-dialog-accent", "comm-top-accent", isEmail ? "comm-accent-email" : "comm-accent-whatsapp");

        StackPane heroIcon = new StackPane(IconFactory.icon(isEmail ? "email" : "whatsapp", 24));
        heroIcon.getStyleClass().addAll("erp-page-header-icon", "dse-dialog-icon-box");

        Label lblKicker = new Label(isEmail ? "EMAIL DISPATCH  •  DELIVERY INSPECTOR" : "WHATSAPP BUSINESS  •  DELIVERY INSPECTOR");
        lblKicker.getStyleClass().addAll("comm-hero-kicker", isEmail ? "comm-kicker-email" : "comm-kicker-whatsapp");

        Label lblDoc = new Label(safe(entry.documentLabel()).isBlank() ? "Direct Communication" : entry.documentLabel());
        lblDoc.getStyleClass().add("stock-ledger-title");

        Label badgeChannel = new Label(safe(entry.channel()).toUpperCase(Locale.ROOT));
        badgeChannel.setMinWidth(Region.USE_PREF_SIZE);
        badgeChannel.getStyleClass().addAll("comm-channel-badge", isEmail ? "comm-channel-email" : "comm-channel-whatsapp");

        Label badgeStatus = new Label(isFailed ? "FAILED" : "SENT");
        badgeStatus.setMinWidth(Region.USE_PREF_SIZE);
        badgeStatus.setGraphic(IconFactory.compactIcon(isFailed ? "error" : "complete", 12));
        badgeStatus.getStyleClass().addAll("comm-status-badge", isFailed ? "comm-status-failed" : "comm-status-sent");

        HBox titleRow = new HBox(10, lblDoc, badgeChannel, badgeStatus);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        String metaText = safe(entry.formattedTime())
                + "  •  Dispatched by " + (safe(entry.createdBy()).isBlank() ? "System" : entry.createdBy())
                + "  •  " + (safe(entry.attemptText()).isBlank() ? "Original" : entry.attemptText());
        Label lblMeta = new Label(metaText);
        lblMeta.getStyleClass().add("stock-ledger-subtitle");

        VBox heroText = new VBox(3, lblKicker, titleRow, lblMeta);
        HBox.setHgrow(heroText, Priority.ALWAYS);

        Button btnTopClose = new Button();
        btnTopClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnTopClose.getStyleClass().addAll("dse-dialog-close", "icon-button");
        btnTopClose.getProperties().put("erp-icon-preserve", true);
        btnTopClose.setTooltip(IconFactory.createTooltip("Close"));
        btnTopClose.setOnAction(e -> dialog.close());

        HBox headerHero = new HBox(14, heroIcon, heroText, btnTopClose);
        headerHero.setAlignment(Pos.CENTER_LEFT);
        headerHero.getStyleClass().addAll("comm-hero-banner", isEmail ? "comm-hero-email" : "comm-hero-whatsapp");
        DocumentInspectionScaffold.installDragPhysics(headerHero, dialog);

        VBox headerWrap = new VBox(0, topAccent, headerHero);
        headerWrap.getStyleClass().addAll("dse-stage-header-wrap", "comm-header-wrap");

        // 2. All Recipients Card (Zero Clipping — individual chips!)
        Label lblRecipHeading = new Label("ALL RECIPIENTS (" + recipients.size() + (recipients.size() == 1 ? " ADDRESS)" : " ADDRESSES)"));
        lblRecipHeading.getStyleClass().addAll("comm-section-heading", "comm-heading-indigo");
        lblRecipHeading.setGraphic(IconFactory.compactIcon(isEmail ? "email" : "whatsapp", 15));

        Region recipSpacer = new Region();
        HBox.setHgrow(recipSpacer, Priority.ALWAYS);

        Button btnCopyAllTop = new Button("Copy All");
        btnCopyAllTop.setMinWidth(Region.USE_PREF_SIZE);
        btnCopyAllTop.setGraphic(IconFactory.compactIcon("duplicate", 13));
        btnCopyAllTop.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCopyAllTop.setDisable(recipients.isEmpty());
        btnCopyAllTop.setOnAction(e -> copyToClipboard(pane, String.join("; ", recipients), "Copied " + recipients.size() + " recipient address(es)."));

        HBox recipTop = new HBox(8, lblRecipHeading, recipSpacer, btnCopyAllTop);
        recipTop.setAlignment(Pos.CENTER_LEFT);

        FlowPane chipsPane = new FlowPane(8, 8);
        chipsPane.setPrefWrapLength(920);
        if (recipients.isEmpty()) {
            Label empty = new Label("No recipient recorded");
            empty.getStyleClass().add("muted-label");
            chipsPane.getChildren().add(empty);
        } else {
            for (String addr : recipients) {
                Button chip = new Button(addr);
                chip.setMinWidth(Region.USE_PREF_SIZE);
                chip.setGraphic(IconFactory.compactIcon(isEmail ? "email" : "whatsapp", 13));
                chip.getStyleClass().addAll("comm-recipient-chip", isEmail ? "comm-chip-email" : "comm-chip-whatsapp");
                chip.setTooltip(IconFactory.createTooltip("Click to copy: " + addr));
                chip.setOnAction(e -> copyToClipboard(pane, addr, "Copied recipient: " + addr));
                chipsPane.getChildren().add(chip);
            }
        }

        VBox recipientsCard = new VBox(10, recipTop, chipsPane);
        recipientsCard.getStyleClass().add("comm-detail-card");

        // 3. Full Subject & Delivery Diagnostic Grid
        Label lblSubjHeading = new Label("FULL SUBJECT / MESSAGE HEADER");
        lblSubjHeading.getStyleClass().addAll("comm-section-heading", "comm-heading-amber");
        lblSubjHeading.setGraphic(IconFactory.compactIcon("notes", 15));

        Label lblSubjBody = new Label(safe(entry.subject()).isBlank() ? "—" : entry.subject());
        lblSubjBody.setWrapText(true);
        lblSubjBody.getStyleClass().add("comm-detail-body-strong");

        VBox subjectCard = new VBox(8, lblSubjHeading, lblSubjBody);
        subjectCard.getStyleClass().add("comm-detail-card");
        HBox.setHgrow(subjectCard, Priority.ALWAYS);

        Label lblDiagHeading = new Label(isFailed ? "FAILURE DIAGNOSTIC / ERROR TRACE" : "DELIVERY STATUS & DIAGNOSTICS");
        lblDiagHeading.getStyleClass().addAll("comm-section-heading", isFailed ? "comm-heading-danger" : "comm-heading-success");
        lblDiagHeading.setGraphic(IconFactory.compactIcon(isFailed ? "error" : "complete", 15));

        String diagText = sanitizeDiagnosticText(entry.errorMessage(), entry.rawRecipient(), isFailed, isEmail);
        Label lblDiagBody = new Label(diagText);
        lblDiagBody.setWrapText(true);
        lblDiagBody.getStyleClass().add(isFailed ? "comm-detail-error-text" : "comm-detail-success-text");

        VBox diagCard = new VBox(8, lblDiagHeading, lblDiagBody);
        diagCard.getStyleClass().add("comm-detail-card");
        HBox.setHgrow(diagCard, Priority.ALWAYS);

        HBox detailGrid = new HBox(12, subjectCard, diagCard);
        detailGrid.setAlignment(Pos.TOP_LEFT);

        // 4. Attempt History Timeline
        Label lblTimelineHeading = new Label("DELIVERY ATTEMPT TIMELINE FOR THIS DOCUMENT (" + (relatedAttempts == null ? 1 : relatedAttempts.size()) + ")");
        lblTimelineHeading.getStyleClass().addAll("comm-section-heading", "comm-heading-blue");
        lblTimelineHeading.setGraphic(IconFactory.compactIcon("history", 15));

        VBox timelineList = new VBox(6);
        List<CommunicationSnapshot> attempts = (relatedAttempts == null || relatedAttempts.isEmpty()) ? List.of(entry) : relatedAttempts;
        for (CommunicationSnapshot att : attempts) {
            boolean attFail = "FAILED".equalsIgnoreCase(att.status());
            List<String> attRecips = parseRecipients(att.rawRecipient());

            Label lblAttName = new Label(safe(att.attemptText()).isBlank() ? "Original" : att.attemptText());
            lblAttName.getStyleClass().add("comm-timeline-attempt");
            lblAttName.setMinWidth(Region.USE_PREF_SIZE);

            Label lblAttTime = new Label(safe(att.formattedTime()));
            lblAttTime.getStyleClass().add("stock-date-time");
            lblAttTime.setMinWidth(Region.USE_PREF_SIZE);

            String subjText = safe(att.subject());
            String recipMeta = attRecips.size() + (attRecips.size() == 1 ? " recipient" : " recipients")
                    + " • " + (safe(att.createdBy()).isBlank() ? "System" : att.createdBy());
            Label lblAttRecip = new Label(subjText.isBlank() ? recipMeta : subjText + "  •  " + recipMeta);
            lblAttRecip.getStyleClass().add("muted-label");
            lblAttRecip.setTooltip(IconFactory.createTooltip((subjText.isBlank() ? "" : subjText + "\n") + String.join(", ", attRecips)));
            HBox.setHgrow(lblAttRecip, Priority.ALWAYS);

            Label lblAttStatus = new Label(attFail ? "FAILED" : "SENT");
            lblAttStatus.setMinWidth(Region.USE_PREF_SIZE);
            lblAttStatus.getStyleClass().addAll("comm-status-badge", attFail ? "comm-status-failed" : "comm-status-sent");

            HBox attRow = new HBox(12, lblAttName, lblAttTime, lblAttRecip, lblAttStatus);
            attRow.setAlignment(Pos.CENTER_LEFT);
            attRow.getStyleClass().add("comm-timeline-row");
            timelineList.getChildren().add(attRow);
        }

        VBox timelineCard = new VBox(10, lblTimelineHeading, timelineList);
        timelineCard.getStyleClass().add("comm-detail-card");

        VBox scrollBody = new VBox(12, recipientsCard, detailGrid, timelineCard);
        scrollBody.setPadding(new Insets(2, 2, 6, 2));

        ScrollPane bodyScroll = new ScrollPane(scrollBody);
        bodyScroll.setFitToWidth(true);
        bodyScroll.getStyleClass().add("erp-clean-scroll");
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        // 5. Bottom Zero-Truncation Action Dock
        HBox actionsContainer = new HBox(10);
        actionsContainer.setAlignment(Pos.CENTER_LEFT);

        if (onOpenDocument != null) {
            Button btnOpenDoc = new Button("Open Linked Document");
            btnOpenDoc.setMinWidth(Region.USE_PREF_SIZE);
            btnOpenDoc.setGraphic(IconFactory.compactIcon("view", 14));
            btnOpenDoc.getStyleClass().addAll("approved-button", "approved-primary-button");
            btnOpenDoc.setOnAction(e -> {
                dialog.close();
                onOpenDocument.run();
            });
            actionsContainer.getChildren().add(btnOpenDoc);
        }

        if (onResend != null) {
            Button btnResend = new Button(isEmail ? "Re-send / Edit Recipients" : "Re-send WhatsApp");
            btnResend.setMinWidth(Region.USE_PREF_SIZE);
            btnResend.setGraphic(IconFactory.compactIcon(isEmail ? "email" : "whatsapp", 14));
            btnResend.getStyleClass().addAll("approved-button", "approved-success-button");
            btnResend.setOnAction(e -> {
                dialog.close();
                onResend.run();
            });
            actionsContainer.getChildren().add(btnResend);
        }

        Button btnCopyAll = new Button("Copy All Recipients");
        btnCopyAll.setMinWidth(Region.USE_PREF_SIZE);
        btnCopyAll.setGraphic(IconFactory.compactIcon("duplicate", 14));
        btnCopyAll.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCopyAll.setDisable(recipients.isEmpty());
        btnCopyAll.setOnAction(e -> copyToClipboard(pane, String.join("; ", recipients), "Copied all " + recipients.size() + " recipient address(es)."));
        actionsContainer.getChildren().add(btnCopyAll);

        if (!safe(entry.errorMessage()).isBlank()) {
            Button btnCopyError = new Button("Copy Error Trace");
            btnCopyError.setMinWidth(Region.USE_PREF_SIZE);
            btnCopyError.setGraphic(IconFactory.compactIcon("error", 14));
            btnCopyError.getStyleClass().addAll("approved-button", "approved-secondary-button");
            btnCopyError.setOnAction(e -> copyToClipboard(pane, entry.errorMessage(), "Copied diagnostic error trace to clipboard."));
            actionsContainer.getChildren().add(btnCopyError);
        }

        ScrollPane dockScroll = new ScrollPane(actionsContainer);
        dockScroll.setFitToHeight(true);
        dockScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        dockScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        dockScroll.getStyleClass().addAll("erp-dock-scroll", "erp-clean-scroll");
        HBox.setHgrow(dockScroll, Priority.ALWAYS);

        Button btnClose = new Button("Close");
        btnClose.setMinWidth(Region.USE_PREF_SIZE);
        btnClose.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnClose.getStyleClass().addAll("approved-button", "approved-danger-ghost-button");
        btnClose.setOnAction(e -> dialog.close());

        HBox actionDock = new HBox(12, dockScroll, btnClose);
        actionDock.setAlignment(Pos.CENTER_LEFT);
        actionDock.getStyleClass().add("erp-inspection-action-dock");

        VBox contentBody = new VBox(12, bodyScroll, actionDock);
        contentBody.setPadding(new Insets(14, 20, 14, 20));
        VBox.setVgrow(contentBody, Priority.ALWAYS);

        VBox root = new VBox(0, headerWrap, contentBody);
        root.getStyleClass().addAll("dse-dialog-shell", "dse-dialog-shell-workspace", "comm-inspection-shell");
        pane.setContent(root);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node defaultBar = pane.lookup(".button-bar");
            if (defaultBar != null) {
                defaultBar.setVisible(false);
                defaultBar.setManaged(false);
            }
        });

        dialog.showAndWait();
    }

    private static void copyToClipboard(Node owner, String value, String feedback) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
        AppDialogService.success(owner, "Copied", feedback);
    }

    private static String sanitizeDiagnosticText(String raw, String recipient, boolean isFailed, boolean isEmail) {
        if (raw == null || raw.isBlank()) {
            return isFailed ? "Delivery failed with no additional error detail from transport."
                    : "Delivered successfully via " + (isEmail ? "Company SMTP Gateway" : "Business WhatsApp Engine") + ".";
        }

        String clean = raw.trim();
        if (clean.contains("No LID for user")) {
            String targetPhone = (recipient != null && !recipient.isBlank()) ? " (" + recipient.trim() + ")" : "";
            return "WhatsApp Delivery Failed: The recipient phone number" + targetPhone + " is not registered on WhatsApp or has an invalid number format.\n\n"
                    + "Diagnostic Root Cause:\n"
                    + "WhatsApp Web engine rejected the delivery with 'No LID for user'. This occurs when the phone number has invalid digits (e.g. 11 digits instead of 10) or does not have an active WhatsApp account.\n\n"
                    + "Recommended Action:\n"
                    + "1. Verify and update the customer's mobile number in Customer Master.\n"
                    + "2. Click 'Re-send WhatsApp' below to re-transmit.";
        }

        if (clean.contains(";base64,") || clean.contains("\"data\":\"JVBERi")) {
            int idx = clean.indexOf("\"data\":\"JVBERi");
            if (idx > 0) {
                int endIdx = clean.indexOf("\"", idx + 15);
                if (endIdx > 0) {
                    clean = clean.substring(0, idx + 15) + "[...ATTACHMENT BASE64 DATA STRIPPED FOR DISPLAY...]" + clean.substring(endIdx);
                }
            }
        }

        return clean;
    }

    private static String safe(String v) {
        return v == null ? "" : v.trim();
    }
}
