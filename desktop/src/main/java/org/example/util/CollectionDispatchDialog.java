package org.example.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.api.insights.InsightsApiClient;
import org.example.api.support.SupportApiClient;
import org.example.api.whatsapp.BusinessWhatsappApiClient;
import org.example.config.ConfigManager;
import org.example.service.AgingStatementPdfService;
import org.example.service.BrandingService;
import org.example.service.EmailService;
import org.example.service.NotificationService;
import org.example.service.SessionService;

import java.awt.Desktop;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/**
 * Smart Overdue Collection & Party Statement Dispatch Modal (Option 1).
 * Strictly honors Settings -> Module Activation flags (module.collections.*)
 * with iPhone-style channel switches, severity templates, cooldown spam guard,
 * Branded Statement PDF preview/attachment, and real Email + WhatsApp delivery.
 */
public final class CollectionDispatchDialog {

    private static final NumberFormat INR = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private CollectionDispatchDialog() {}

    public record TargetParty(
            Long partyId,
            String partyName,
            String phone,
            String email,
            int unpaidInvoiceCount,
            BigDecimal totalOutstanding,
            BigDecimal bucket0To30,
            BigDecimal bucket31To60,
            BigDecimal bucket61To90,
            BigDecimal bucket90Plus,
            String riskLevel,
            String lastRemindedAt,
            String lastRemindedChannel
    ) {
        public BigDecimal overdueAmount() {
            return safe(bucket31To60).add(safe(bucket61To90)).add(safe(bucket90Plus));
        }

        private static BigDecimal safe(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    public static void showSingle(
            Node owner,
            TargetParty party,
            Function<Long, AgingStatementPdfService.StatementPayload> statementLoader,
            SupportApiClient supportApi,
            InsightsApiClient insightsApi,
            Runnable onDispatched) {
        if (party == null) return;

        boolean allowWhatsapp = ConfigManager.getBoolean("module.collections.whatsapp", true);
        boolean allowEmail = ConfigManager.getBoolean("module.collections.email", true);
        boolean defaultAttachPdf = ConfigManager.getBoolean("module.collections.attachStatementPdf", true);
        int cooldownHours = parseCooldownHours();

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Send Payment Reminder — " + party.partyName());
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-ui-standard");
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node bar = pane.lookup(".button-bar");
            if (bar != null) {
                bar.setVisible(false);
                bar.setManaged(false);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");

        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane icon = new StackPane(IconFactory.icon("communication", 22));
        icon.getStyleClass().add("erp-page-header-icon");
        VBox titleBox = new VBox(2);
        Label lblTitle = new Label("Smart Payment Reminder & Statement Dispatch");
        lblTitle.getStyleClass().add("screen-title");
        Label lblSub = new Label(party.partyName() + "  •  Total Due: " + money(party.totalOutstanding())
                + "  •  Overdue (>30d): " + money(party.overdueAmount()));
        lblSub.getStyleClass().add("screen-subtitle");
        titleBox.getChildren().addAll(lblTitle, lblSub);
        Region hdrSpacer = new Region();
        HBox.setHgrow(hdrSpacer, Priority.ALWAYS);
        Label lblSeverity = new Label(severityBadgeLabel(party));
        lblSeverity.getStyleClass().addAll("status-badge", severityBadgeClass(party));
        header.getChildren().addAll(icon, titleBox, hdrSpacer, lblSeverity);

        // Cooldown spam guard warning banner if recently reminded
        VBox body = new VBox(12);
        if (isWithinCooldown(party.lastRemindedAt(), cooldownHours)) {
            HBox cooldownBanner = new HBox(8);
            cooldownBanner.setAlignment(Pos.CENTER_LEFT);
            cooldownBanner.getStyleClass().add("collection-cooldown-banner");
            Label warnIcon = new Label();
            warnIcon.setGraphic(IconFactory.compactIcon("warning", 16));
            Label warnText = new Label("Cooldown Notice: " + party.partyName() + " was already reminded on "
                    + BusinessClock.formatTimestamp(party.lastRemindedAt())
                    + (party.lastRemindedChannel() != null ? " via " + party.lastRemindedChannel() : "")
                    + " (within your " + cooldownHours + "h Settings cooldown guard). Verify before sending again.");
            warnText.setWrapText(true);
            warnText.getStyleClass().add("accent-label-amber");
            HBox.setHgrow(warnText, Priority.ALWAYS);
            cooldownBanner.getChildren().addAll(warnIcon, warnText);
            body.getChildren().add(cooldownBanner);
        }

        // Channel Controls (iPhone-style switches matching Settings)
        HBox channelBar = new HBox(18);
        channelBar.setAlignment(Pos.CENTER_LEFT);
        channelBar.getStyleClass().add("settings-subcard");
        CheckBox chkWa = new CheckBox("WhatsApp (WAHA)");
        chkWa.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkWa.setSelected(allowWhatsapp && !clean(party.phone()).isBlank());
        chkWa.setDisable(!allowWhatsapp);

        CheckBox chkMail = new CheckBox("Email (SMTP)");
        chkMail.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkMail.setSelected(allowEmail && !clean(party.email()).isBlank());
        chkMail.setDisable(!allowEmail);

        CheckBox chkPdf = new CheckBox("Attach Branded Statement PDF");
        chkPdf.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkPdf.setSelected(defaultAttachPdf);
        channelBar.getChildren().addAll(chkWa, chkMail, chkPdf);

        // Recipient Inputs Grid
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        ColumnConstraints c0 = new ColumnConstraints(145);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);

        TextField txtPhone = new TextField(clean(party.phone()));
        txtPhone.setPromptText("Customer WhatsApp mobile (e.g. 9876543210)");
        txtPhone.getStyleClass().add("approved-input");
        txtPhone.disableProperty().bind(chkWa.selectedProperty().not());

        TextField txtEmail = new TextField(clean(party.email()));
        txtEmail.setPromptText("Customer email(s), comma-separated");
        txtEmail.getStyleClass().add("approved-input");
        txtEmail.disableProperty().bind(chkMail.selectedProperty().not());

        TextField txtSubject = new TextField(defaultSubject(party));
        txtSubject.getStyleClass().add("approved-input");

        String initialDefaultMessage = defaultMessage(party);
        TextArea txtMessage = new TextArea(initialDefaultMessage);
        txtMessage.setWrapText(true);
        txtMessage.setPrefRowCount(8);
        txtMessage.getStyleClass().addAll("approved-input", "dialog-text-area");

        Label lblPh = new Label("WhatsApp Mobile:");
        lblPh.getStyleClass().addAll("field-label", "accent-label-green");
        lblPh.setGraphic(IconFactory.compactIcon("whatsapp", 14));
        Label lblEm = new Label("Recipient Email(s):");
        lblEm.getStyleClass().addAll("field-label", "accent-label-purple");
        lblEm.setGraphic(IconFactory.compactIcon("email", 14));
        Label lblSubj = new Label("Email Subject:");
        lblSubj.getStyleClass().addAll("field-label", "accent-label-blue");
        lblSubj.setGraphic(IconFactory.compactIcon("document", 14));
        Label lblMsg = new Label("Message Content:");
        lblMsg.getStyleClass().addAll("field-label", "accent-label-indigo");
        lblMsg.setGraphic(IconFactory.compactIcon("notes", 14));

        grid.addRow(0, lblPh, txtPhone);
        grid.addRow(1, lblEm, txtEmail);
        grid.addRow(2, lblSubj, txtSubject);
        grid.addRow(3, lblMsg, txtMessage);

        body.getChildren().addAll(channelBar, grid);
        VBox.setVgrow(body, Priority.ALWAYS);

        // Footer Action Dock
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);

        Button btnPreviewPdf = new Button("Preview Statement PDF");
        btnPreviewPdf.setMinWidth(Region.USE_PREF_SIZE);
        btnPreviewPdf.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnPreviewPdf.setGraphic(IconFactory.compactIcon("pdf", 14));
        btnPreviewPdf.setOnAction(ev -> UiTaskExecutor.submitActionWithOverlay(
                pane,
                "Generating Branded Statement PDF...",
                "aging-statement-preview-" + party.partyId(),
                () -> {
                    AgingStatementPdfService.StatementPayload payload = statementLoader.apply(party.partyId());
                    return AgingStatementPdfService.generateTempStatement(payload);
                },
                pdfPath -> {
                    try {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(pdfPath.toFile());
                        } else {
                            AppDialogService.info(pane, "PDF Generated", "Saved statement to:\n" + pdfPath);
                        }
                    } catch (Exception ex) {
                        AppDialogService.info(pane, "PDF Generated", "Saved statement to:\n" + pdfPath);
                    }
                },
                err -> AppDialogService.error(pane, "Statement PDF Failed", err.getMessage())
        ));

        Button btnCancel = new Button("Cancel");
        btnCancel.setMinWidth(Region.USE_PREF_SIZE);
        btnCancel.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.setOnAction(ev -> dialog.close());

        Button btnSend = new Button("Send Now");
        btnSend.setMinWidth(Region.USE_PREF_SIZE);
        btnSend.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnSend.setGraphic(IconFactory.compactIcon("sent", 14));
        btnSend.setOnAction(ev -> {
            boolean sendWa = chkWa.isSelected();
            boolean sendEm = chkMail.isSelected();
            if (!sendWa && !sendEm) {
                AppDialogService.warning(pane, "No Channel Selected", "Turn ON at least one channel (WhatsApp or Email) to dispatch.");
                return;
            }
            String phone = clean(txtPhone.getText());
            String email = clean(txtEmail.getText());
            if (sendWa && phone.isBlank()) {
                AppDialogService.warning(pane, "Missing Phone", "Enter a WhatsApp mobile number or turn OFF WhatsApp dispatch.");
                return;
            }
            if (sendEm && email.isBlank()) {
                AppDialogService.warning(pane, "Missing Email", "Enter at least one recipient email or turn OFF Email dispatch.");
                return;
            }

            String subject = clean(txtSubject.getText());
            String message = clean(txtMessage.getText());
            boolean attachPdf = chkPdf.isSelected();

            LiveOperationDialog.run(
                    pane,
                    "Dispatching Collection Reminder — " + party.partyName(),
                    "communication",
                    "Preparing party outstanding statement & channels...",
                    reporter -> {
                        int totalSteps = 1 + (attachPdf && sendEm ? 1 : 0) + (sendEm ? 1 : 0) + (sendWa ? 1 : 0);
                        int step = 0;
                        Path statementPdf = null;
                        if (attachPdf && sendEm) {
                            reporter.stage(++step, totalSteps, "Generating Branded Party Aging Statement PDF...");
                            AgingStatementPdfService.StatementPayload payload = statementLoader.apply(party.partyId());
                            statementPdf = AgingStatementPdfService.generateTempStatement(payload);
                        }
                        String user = currentUser();
                        int partyIntId = party.partyId() == null ? 0 : party.partyId().intValue();
                        List<String> failures = new ArrayList<>();

                        if (sendEm) {
                            reporter.stage(++step, totalSteps, "Sending Email to " + email + "...");
                            try {
                                String emailPayload = message;
                                if (org.example.service.MessageTemplateService.shouldUseHtml("aging_collection")
                                        && !org.example.shared.communication.TokenTemplateEngine.isHtmlContent(message)) {
                                    emailPayload = org.example.shared.communication.TokenTemplateEngine.wrapInCardHtml(
                                            "#0284c7", "#0369a1",
                                            severityBadgeLabel(party),
                                            "Outstanding Account Statement",
                                            message,
                                            List.of(),
                                            org.example.service.BrandingService.companyName() + " Accounts Receivable"
                                    );
                                }
                                EmailService.send(email, subject, emailPayload, statementPdf);
                                logCommunication(supportApi, partyIntId, "EMAIL", email, subject, "SENT", null, user);
                            } catch (Exception ex) {
                                logCommunication(supportApi, partyIntId, "EMAIL", email, subject, "FAILED", ex.getMessage(), user);
                                failures.add("Email: " + ex.getMessage());
                            }
                        }

                        if (sendWa) {
                            reporter.stage(++step, totalSteps, "Sending WhatsApp to " + phone + "...");
                            try {
                                String waPayload = message.equals(initialDefaultMessage)
                                        ? defaultWhatsappMessage(party)
                                        : message;
                                var waha = new BusinessWhatsappApiClient();
                                String normalizedPhone = normalizeIndiaPhone(phone);
                                var resp = waha.sendText(new BusinessWhatsappApiClient.TextSendRequest(normalizedPhone, waPayload, null));
                                if (resp == null || !resp.success()) {
                                    String errMsg = resp != null && resp.message() != null ? resp.message() : "WhatsApp delivery failed";
                                    logCommunication(supportApi, partyIntId, "WHATSAPP", phone, subject, "FAILED", errMsg, user);
                                    failures.add("WhatsApp: " + errMsg);
                                } else {
                                    logCommunication(supportApi, partyIntId, "WHATSAPP", phone, subject, "SENT", null, user);
                                }
                            } catch (Exception ex) {
                                logCommunication(supportApi, partyIntId, "WHATSAPP", phone, subject, "FAILED", ex.getMessage(), user);
                                failures.add("WhatsApp: " + ex.getMessage());
                            }
                        }

                        reporter.stage(++step, totalSteps, "Recording follow-up in Reminder Center...");
                        saveReminderEntry(insightsApi, party, user, (sendEm && sendWa) ? "Email + WhatsApp" : (sendEm ? "Email" : "WhatsApp"));
                        NotificationService.add("Collection reminder dispatched for " + party.partyName() + " (" + money(party.totalOutstanding()) + ")");

                        if (!failures.isEmpty()) {
                            throw new IllegalStateException("Partial / failed delivery:\n" + String.join("\n", failures));
                        }
                    },
                    () -> {
                        AppDialogService.success(pane, "Reminder Dispatched",
                                "Collection reminder for " + party.partyName() + " was delivered and logged in Communication Center.");
                        dialog.close();
                        if (onDispatched != null) onDispatched.run();
                    },
                    err -> {
                        AppDialogService.warning(pane, "Dispatch Completed with Issues", err.getMessage());
                        if (onDispatched != null) onDispatched.run();
                    }
            );
        });

        Region fSpacer = new Region();
        HBox.setHgrow(fSpacer, Priority.ALWAYS);
        footer.getChildren().addAll(btnPreviewPdf, fSpacer, btnCancel, btnSend);

        root.getChildren().addAll(header, new Separator(), body, new Separator(), footer);
        pane.setContent(root);

        dialog.setOnShown(ev -> fitAdaptiveBounds(pane, owner, 860, 620));
        dialog.showAndWait();
    }

    public static void showBulk(
            Node owner,
            List<TargetParty> overdueParties,
            Function<Long, AgingStatementPdfService.StatementPayload> statementLoader,
            SupportApiClient supportApi,
            InsightsApiClient insightsApi,
            Runnable onDispatched) {
        if (overdueParties == null || overdueParties.isEmpty()) return;

        boolean allowWhatsapp = ConfigManager.getBoolean("module.collections.whatsapp", true);
        boolean allowEmail = ConfigManager.getBoolean("module.collections.email", true);
        boolean defaultAttachPdf = ConfigManager.getBoolean("module.collections.attachStatementPdf", true);
        int cooldownHours = parseCooldownHours();

        OwnedDialog<ButtonType> dialog = new OwnedDialog<>(owner);
        dialog.setTitle("Bulk Overdue Collection Dispatch (" + overdueParties.size() + " Accounts)");
        dialog.setResizable(true);

        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().addAll("approved-dialog", "erp-document-inspection-dialog", "erp-ui-standard");
        pane.getButtonTypes().add(ButtonType.CLOSE);
        dialog.setOnShowing(ev -> {
            Node bar = pane.lookup(".button-bar");
            if (bar != null) {
                bar.setVisible(false);
                bar.setManaged(false);
            }
        });

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));
        root.getStyleClass().addAll("approved-card", "erp-ui-standard");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane icon = new StackPane(IconFactory.icon("communication", 22));
        icon.getStyleClass().add("erp-page-header-icon");
        VBox titleBox = new VBox(2);
        Label lblTitle = new Label("Bulk Overdue Collections Queue");
        lblTitle.getStyleClass().add("screen-title");
        Label lblSub = new Label("Select overdue accounts to dispatch real WhatsApp & Email payment reminders with Branded Statement PDFs.");
        lblSub.getStyleClass().add("screen-subtitle");
        titleBox.getChildren().addAll(lblTitle, lblSub);
        header.getChildren().addAll(icon, titleBox);

        HBox channelBar = new HBox(18);
        channelBar.setAlignment(Pos.CENTER_LEFT);
        channelBar.getStyleClass().add("settings-subcard");
        CheckBox chkWa = new CheckBox("WhatsApp (WAHA)");
        chkWa.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkWa.setSelected(allowWhatsapp);
        chkWa.setDisable(!allowWhatsapp);

        CheckBox chkMail = new CheckBox("Email (SMTP)");
        chkMail.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkMail.setSelected(allowEmail);
        chkMail.setDisable(!allowEmail);

        CheckBox chkPdf = new CheckBox("Attach Branded Statement PDF");
        chkPdf.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkPdf.setSelected(defaultAttachPdf);

        CheckBox chkSkipCooldown = new CheckBox("Skip recently reminded (<" + cooldownHours + "h)");
        chkSkipCooldown.getStyleClass().addAll("approved-checkbox", "ios-toggle-switch");
        chkSkipCooldown.setSelected(true);
        channelBar.getChildren().addAll(chkWa, chkMail, chkPdf, chkSkipCooldown);

        VBox rowsBox = new VBox(6);
        rowsBox.setPadding(new Insets(6));
        Map<TargetParty, CheckBox> checks = new LinkedHashMap<>();
        for (TargetParty p : overdueParties) {
            boolean recent = isWithinCooldown(p.lastRemindedAt(), cooldownHours);
            boolean hasContact = !clean(p.email()).isBlank() || !clean(p.phone()).isBlank();
            CheckBox cb = new CheckBox(p.partyName());
            cb.setSelected(hasContact && !recent);
            cb.setMinWidth(220);

            Label lblAmt = new Label("Overdue: " + money(p.overdueAmount()) + "  (Total: " + money(p.totalOutstanding()) + ")");
            lblAmt.getStyleClass().add("accent-label-rose");
            Label lblContacts = new Label((clean(p.phone()).isBlank() ? "No Phone" : p.phone())
                    + "  |  " + (clean(p.email()).isBlank() ? "No Email" : p.email()));
            lblContacts.getStyleClass().add("muted-label");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);

            Label lblStatus = new Label(recent ? ("Reminded <" + cooldownHours + "h ago") : (hasContact ? "Ready" : "Missing Contact"));
            lblStatus.getStyleClass().addAll("aging-reminded-pill", recent ? "aging-reminded-stale" : (hasContact ? "aging-reminded-recent" : "aging-reminded-none"));

            HBox row = new HBox(10, cb, lblAmt, lblContacts, sp, lblStatus);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("settings-impact-box");
            rowsBox.getChildren().add(row);
            checks.put(p, cb);
        }

        ScrollPane scroll = new ScrollPane(rowsBox);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        Button btnCancel = new Button("Cancel");
        btnCancel.setMinWidth(Region.USE_PREF_SIZE);
        btnCancel.getStyleClass().addAll("approved-button", "approved-secondary-button");
        btnCancel.setGraphic(IconFactory.compactIcon("cancel", 14));
        btnCancel.setOnAction(ev -> dialog.close());

        Button btnDispatch = new Button("Dispatch Selected Reminders");
        btnDispatch.setMinWidth(Region.USE_PREF_SIZE);
        btnDispatch.getStyleClass().addAll("approved-button", "approved-primary-button");
        btnDispatch.setGraphic(IconFactory.compactIcon("sent", 14));
        btnDispatch.setOnAction(ev -> {
            boolean sendWa = chkWa.isSelected();
            boolean sendEm = chkMail.isSelected();
            if (!sendWa && !sendEm) {
                AppDialogService.warning(pane, "No Channel Selected", "Turn ON WhatsApp or Email to run bulk dispatch.");
                return;
            }
            List<TargetParty> selected = checks.entrySet().stream()
                    .filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .filter(p -> !chkSkipCooldown.isSelected() || !isWithinCooldown(p.lastRemindedAt(), cooldownHours))
                    .toList();
            if (selected.isEmpty()) {
                AppDialogService.info(pane, "Nothing Selected", "Select at least one eligible party to send reminders.");
                return;
            }
            boolean attachPdf = chkPdf.isSelected();
            String user = currentUser();

            LiveOperationDialog.run(
                    pane,
                    "Bulk Collection Dispatch (" + selected.size() + " Parties)",
                    "communication",
                    "Dispatching reminders sequentially...",
                    reporter -> {
                        int idx = 0;
                        int sentCount = 0;
                        int failCount = 0;
                        var waha = new BusinessWhatsappApiClient();
                        for (TargetParty p : selected) {
                            idx++;
                            reporter.stage(idx, selected.size(), "Sending reminder to " + p.partyName() + " (" + idx + "/" + selected.size() + ")...");
                            Map<String, String> tokens = buildCollectionTokens(p);
                            var renderedEmail = org.example.service.MessageTemplateService.renderEmailByKey(
                                    "aging_collection",
                                    tokens,
                                    "Outstanding Account Statement",
                                    severityBadgeLabel(p),
                                    "#0284c7",
                                    "#0369a1"
                            );
                            String subject = renderedEmail.subject();
                            String waMessage = org.example.service.MessageTemplateService.renderWhatsappByKey("aging_collection", tokens);
                            int pid = p.partyId() == null ? 0 : p.partyId().intValue();
                            boolean anySent = false;

                            if (sendEm && !clean(p.email()).isBlank()) {
                                try {
                                    Path pdf = attachPdf ? AgingStatementPdfService.generateTempStatement(statementLoader.apply(p.partyId())) : null;
                                    EmailService.send(p.email().trim(), subject, renderedEmail.body(), pdf);
                                    logCommunication(supportApi, pid, "EMAIL", p.email().trim(), subject, "SENT", null, user);
                                    anySent = true;
                                } catch (Exception ex) {
                                    logCommunication(supportApi, pid, "EMAIL", p.email().trim(), subject, "FAILED", ex.getMessage(), user);
                                    failCount++;
                                }
                            }
                            if (sendWa && !clean(p.phone()).isBlank()) {
                                try {
                                    var resp = waha.sendText(new BusinessWhatsappApiClient.TextSendRequest(normalizeIndiaPhone(p.phone()), waMessage, null));
                                    if (resp != null && resp.success()) {
                                        logCommunication(supportApi, pid, "WHATSAPP", p.phone().trim(), subject, "SENT", null, user);
                                        anySent = true;
                                    } else {
                                        logCommunication(supportApi, pid, "WHATSAPP", p.phone().trim(), subject, "FAILED", resp != null ? resp.message() : "Failed", user);
                                        failCount++;
                                    }
                                } catch (Exception ex) {
                                    logCommunication(supportApi, pid, "WHATSAPP", p.phone().trim(), subject, "FAILED", ex.getMessage(), user);
                                    failCount++;
                                }
                            }
                            if (anySent) {
                                sentCount++;
                                saveReminderEntry(insightsApi, p, user, "Bulk Dispatch");
                            }
                        }
                        NotificationService.add("Bulk collections complete: " + sentCount + " delivered, " + failCount + " channel failure(s).");
                        if (failCount > 0) {
                            throw new IllegalStateException("Delivered to " + sentCount + " account(s), with " + failCount + " channel error(s). Check Communication Center for details.");
                        }
                    },
                    () -> {
                        AppDialogService.success(pane, "Bulk Dispatch Complete",
                                "Collection reminders delivered to " + selected.size() + " account(s) and logged in Communication Center.");
                        dialog.close();
                        if (onDispatched != null) onDispatched.run();
                    },
                    err -> {
                        AppDialogService.warning(pane, "Bulk Dispatch Summary", err.getMessage());
                        dialog.close();
                        if (onDispatched != null) onDispatched.run();
                    }
            );
        });

        footer.getChildren().addAll(btnCancel, btnDispatch);
        root.getChildren().addAll(header, new Separator(), channelBar, scroll, new Separator(), footer);
        pane.setContent(root);

        dialog.setOnShown(ev -> fitAdaptiveBounds(pane, owner, 980, 620));
        dialog.showAndWait();
    }

    public static int parseCooldownHours() {
        try {
            int val = Integer.parseInt(ConfigManager.get("module.collections.cooldownHours", "48").trim());
            return Math.max(1, val);
        } catch (Exception ignored) {
            return 48;
        }
    }

    public static boolean isWithinCooldown(String timestamp, int cooldownHours) {
        if (timestamp == null || timestamp.isBlank()) return false;
        try {
            Instant ts = BusinessClock.parseTimestamp(timestamp);
            if (ts == null) return false;
            return ChronoUnit.HOURS.between(ts, Instant.now()) < cooldownHours;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String severityBadgeLabel(TargetParty p) {
        if (p.bucket90Plus() != null && p.bucket90Plus().compareTo(BigDecimal.ZERO) > 0) return "URGENT • 90+ DAYS";
        if (p.bucket61To90() != null && p.bucket61To90().compareTo(BigDecimal.ZERO) > 0) return "FORMAL • 61-90 DAYS";
        if (p.bucket31To60() != null && p.bucket31To60().compareTo(BigDecimal.ZERO) > 0) return "OVERDUE • 31-60 DAYS";
        return "COURTESY • 0-30 DAYS";
    }

    private static String severityBadgeClass(TargetParty p) {
        if (p.bucket90Plus() != null && p.bucket90Plus().compareTo(BigDecimal.ZERO) > 0) return "status-inactive";
        if (p.bucket61To90() != null && p.bucket61To90().compareTo(BigDecimal.ZERO) > 0) return "erp-badge-amber";
        if (p.bucket31To60() != null && p.bucket31To60().compareTo(BigDecimal.ZERO) > 0) return "erp-badge-amber";
        return "status-active";
    }

    private static Map<String, String> buildCollectionTokens(TargetParty p) {
        String company = BrandingService.companyName();
        String urgencyPrefix;
        if (p.bucket90Plus() != null && p.bucket90Plus().compareTo(BigDecimal.ZERO) > 0) {
            urgencyPrefix = "URGENT Payment Notice — Outstanding Statement";
        } else if (p.overdueAmount().compareTo(BigDecimal.ZERO) > 0) {
            urgencyPrefix = "Payment Reminder & Outstanding Statement";
        } else {
            urgencyPrefix = "Account Statement";
        }

        String intro;
        if (p.bucket90Plus() != null && p.bucket90Plus().compareTo(BigDecimal.ZERO) > 0) {
            intro = "This is an urgent reminder regarding your long-overdue account balance with " + company + ".";
        } else if (p.bucket61To90() != null && p.bucket61To90().compareTo(BigDecimal.ZERO) > 0) {
            intro = "We are writing to follow up on overdue invoices pending beyond 60 days on your account with " + company + ".";
        } else if (p.bucket31To60() != null && p.bucket31To60().compareTo(BigDecimal.ZERO) > 0) {
            intro = "This is a polite reminder regarding your overdue balance with " + company + ".";
        } else {
            intro = "Please find below your current account statement summary with " + company + ".";
        }

        String upi = ConfigManager.get("payment.upiId", "").trim();
        String bank = ConfigManager.get("payment.bankName", ConfigManager.get("company.bank.name", "")).trim();
        String ac = ConfigManager.get("payment.accountNumber", ConfigManager.get("company.bank.account", "")).trim();
        String ifsc = ConfigManager.get("payment.ifsc", ConfigManager.get("company.bank.ifsc", "")).trim();
        StringBuilder payBlock = new StringBuilder();
        if (!bank.isBlank() || !upi.isBlank()) {
            payBlock.append("Payment Details:\n");
            if (!bank.isBlank()) {
                payBlock.append("• Bank: ").append(bank)
                        .append(!ac.isBlank() ? " | A/C: " + ac : "")
                        .append(!ifsc.isBlank() ? " | IFSC: " + ifsc : "");
            }
            if (!bank.isBlank() && !upi.isBlank()) payBlock.append("\n");
            if (!upi.isBlank()) payBlock.append("• UPI: ").append(upi);
        }

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("PartyName", p.partyName());
        tokens.put("CustomerName", p.partyName());
        tokens.put("AsOfDate", BusinessClock.formatDate(BusinessClock.today()));
        tokens.put("TotalOutstanding", money(p.totalOutstanding()));
        tokens.put("OverdueAmount", money(p.overdueAmount()));
        tokens.put("OpenInvoiceCount", String.valueOf(p.unpaidInvoiceCount()));
        tokens.put("UrgencySubjectPrefix", urgencyPrefix);
        tokens.put("UrgencyIntro", intro);
        tokens.put("BankName", bank);
        tokens.put("BankAccount", ac);
        tokens.put("Ifsc", ifsc);
        tokens.put("UpiId", upi);
        tokens.put("PaymentDetails", payBlock.toString());
        tokens.put("CompanyName", company);
        return tokens;
    }

    private static String defaultSubject(TargetParty p) {
        return org.example.service.MessageTemplateService.format(
                org.example.service.MessageTemplateService.getEmailSubject("aging_collection"),
                buildCollectionTokens(p)
        );
    }

    private static String defaultMessage(TargetParty p) {
        return org.example.service.MessageTemplateService.format(
                org.example.service.MessageTemplateService.getEmailBody("aging_collection"),
                buildCollectionTokens(p)
        );
    }

    private static String defaultWhatsappMessage(TargetParty p) {
        return org.example.service.MessageTemplateService.renderWhatsappByKey(
                "aging_collection",
                buildCollectionTokens(p)
        );
    }

    private static void logCommunication(SupportApiClient supportApi, int partyId, String channel, String recipient, String subject, String status, String error, String user) {
        if (supportApi == null) return;
        try {
            supportApi.communication(new SupportApiClient.CommunicationRequest(
                    "AGING_COLLECTION", partyId, channel, recipient, subject, status, error, user
            ));
        } catch (Exception ignored) {}
    }

    private static void saveReminderEntry(InsightsApiClient insightsApi, TargetParty p, String user, String via) {
        if (insightsApi == null) return;
        try {
            String prio = (p.bucket90Plus() != null && p.bucket90Plus().compareTo(BigDecimal.ZERO) > 0) ? "URGENT"
                    : (p.bucket61To90() != null && p.bucket61To90().compareTo(BigDecimal.ZERO) > 0 ? "HIGH" : "NORMAL");
            insightsApi.saveReminder(new InsightsApiClient.ReminderDto(
                    null,
                    "Payment Follow-up: " + p.partyName(),
                    p.partyName() + " • " + money(p.totalOutstanding()),
                    BusinessClock.today().toString(),
                    prio,
                    "Dispatched via " + via + " from Aging Analysis. Total: " + money(p.totalOutstanding()),
                    "OPEN",
                    user,
                    null
            ));
        } catch (Exception ignored) {}
    }

    private static String normalizeIndiaPhone(String raw) {
        String digits = clean(raw).replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        return digits;
    }

    private static String currentUser() {
        return SessionService.current() != null ? SessionService.current().getFullName() : "System";
    }

    private static String money(BigDecimal value) {
        return INR.format(value == null ? BigDecimal.ZERO : value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void fitAdaptiveBounds(DialogPane pane, Node owner, double prefW, double prefH) {
        if (pane.getScene() != null && pane.getScene().getWindow() instanceof Stage stage) {
            javafx.geometry.Rectangle2D bounds = WindowUtilsFx.visualBoundsFor(owner);
            double w = Math.min(bounds.getWidth() * 0.92, prefW);
            double h = Math.min(bounds.getHeight() * 0.90, prefH);
            stage.setMinWidth(Math.min(720, w));
            stage.setMinHeight(Math.min(480, h));
            stage.setWidth(w);
            stage.setHeight(h);
            WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
        }
    }
}
