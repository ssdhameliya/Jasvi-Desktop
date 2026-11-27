package org.example.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.example.service.EmailService;
import org.example.service.MessageTemplateService;
import org.example.util.*;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Enterprise Unified Payment Transmission Dialog for Jasvi ERP Desktop.
 * Provides simultaneous or selective dispatch across Email (SMTP) and WhatsApp (WAHA Business),
 * full letterhead and live WhatsApp chat bubble preview, signature attachment, and PDF receipt inspection.
 * Adheres to Rule 12 (Content-Adaptive Bounds & Zero Truncation) and app-dialog.css top accent gradient line.
 */
public class PaymentTransmissionDialog {

    public enum ChannelPreference {
        BOTH,
        EMAIL_ONLY,
        WHATSAPP_ONLY
    }

    public record PaymentDraft(
            String title,
            String subtitle,
            String documentLabel,
            String documentNo,
            String recipientEmail,
            String recipientPhone,
            String emailSubject,
            String emailBody,
            String whatsappMessage,
            Path attachmentPdf,
            Path signatureImage,
            String activityKey,
            String publicStatusUrl,
            Double totalAmount,
            Double paidAmount,
            Double balanceAmount,
            String statusBadge,
            String entityType,
            Integer entityId,
            Integer paymentId,
            Integer refundId,
            ChannelPreference preferredChannel
    ) {
        public PaymentDraft(
                String title,
                String subtitle,
                String documentLabel,
                String documentNo,
                String recipientEmail,
                String recipientPhone,
                String emailSubject,
                String emailBody,
                String whatsappMessage,
                Path attachmentPdf,
                Path signatureImage,
                String activityKey,
                String publicStatusUrl,
                Double totalAmount,
                Double paidAmount,
                Double balanceAmount,
                String statusBadge,
                String entityType,
                Integer entityId,
                Integer paymentId,
                Integer refundId
        ) {
            this(title, subtitle, documentLabel, documentNo, recipientEmail, recipientPhone,
                 emailSubject, emailBody, whatsappMessage, attachmentPdf, signatureImage,
                 activityKey, publicStatusUrl, totalAmount, paidAmount, balanceAmount,
                 statusBadge, entityType, entityId, paymentId, refundId, ChannelPreference.BOTH);
        }
    }

    public record TransmissionResult(
            boolean sendEmail,
            boolean sendWhatsapp,
            String recipientEmail,
            String emailSubject,
            String emailBody,
            String recipientPhone,
            String whatsappMessage,
            Path attachmentPdf,
            Path signatureImage,
            EmailService.SenderProfile senderProfile
    ) {}

    public record SenderProfileOption(String label, EmailService.SenderProfile profile) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FXML private StackPane headerIconHolder;
    @FXML private Label lblTitle;
    @FXML private Label lblSubtitle;
    @FXML private Label lblStatusBadge;
    @FXML private Button btnClose;

    // Dual-Channel Selector
    @FXML private CheckBox chkEmail;
    @FXML private Label lblEmailStatusChip;
    @FXML private VBox boxEmailFields;
    @FXML private TextField txtRecipientEmail;
    @FXML private Label errEmail;
    @FXML private Label lblEmailInfo;

    @FXML private CheckBox chkWhatsapp;
    @FXML private Label lblWhatsappStatusChip;
    @FXML private VBox boxWhatsappFields;
    @FXML private TextField txtRecipientPhone;
    @FXML private Label errPhone;
    @FXML private Label lblPhoneInfo;

    // Tabs & Previews
    @FXML private TabPane tabPanePreview;
    @FXML private Tab tabEmail;
    @FXML private Tab tabWhatsapp;
    @FXML private Tab tabAttachment;

    // Email Tab Controls
    @FXML private VBox boxSenderProfile;
    @FXML private ComboBox<SenderProfileOption> cmbSenderProfile;
    @FXML private TextField txtEmailSubject;
    @FXML private Label errEmailSubject;
    @FXML private Button btnTabEditEmail;
    @FXML private Button btnTabPreviewEmail;
    @FXML private Button btnPreviewHtml;
    @FXML private TextArea txtEmailBody;
    @FXML private VBox boxExecutivePreview;
    @FXML private Label lblPreviewCompany;
    @FXML private Label lblPreviewBadge;
    @FXML private Label lblPreviewDocNo;
    @FXML private Label lblPreviewRecipient;
    @FXML private Label lblPreviewAmount;
    @FXML private Label lblPreviewStatus;
    @FXML private HBox boxLiveStatusPreview;
    @FXML private Button btnLiveStatusSample;
    @FXML private Label lblPreviewBody;

    @FXML private Tab tabSignature;
    @FXML private HBox boxSignatureImage;
    @FXML private StackPane sigIconHolder;
    @FXML private Label lblSigStatusBadge;
    @FXML private Label lblSignatureInfo;
    @FXML private ImageView imgSignatureThumb;
    @FXML private Button btnPreviewSignature;

    // WhatsApp Tab Controls
    @FXML private Label lblWhatsappChannelRoute;
    @FXML private TextArea txtWhatsappMessage;
    @FXML private Label lblWhatsappChatPreview;

    // Attachment Tab Controls
    @FXML private HBox boxAttachment;
    @FXML private StackPane attachIconHolder;
    @FXML private Label lblAttachmentName;
    @FXML private Label lblAttachmentDesc;
    @FXML private Button btnPreviewPdf;

    // Bottom Actions
    @FXML private Button btnCancel;
    @FXML private Button btnTransmit;

    private PaymentDraft draft;
    private Dialog<TransmissionResult> hostDialog;
    private TransmissionResult result;

    public void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.icon("wallet", 22));
        }
        if (attachIconHolder != null) {
            attachIconHolder.getChildren().setAll(IconFactory.icon("file", 18));
        }
        if (sigIconHolder != null) {
            sigIconHolder.getChildren().setAll(IconFactory.icon("signature", 18));
        }
        if (btnClose != null) {
            btnClose.setGraphic(SemanticIconManager.compact("close", 14));
            btnClose.setTooltip(new Tooltip("Close"));
        }
        if (btnPreviewPdf != null) {
            UiActionIcons.apply(btnPreviewPdf, "eye", "Preview PDF");
        }
        if (btnPreviewSignature != null) {
            UiActionIcons.apply(btnPreviewSignature, "eye", "Preview Signature");
        }
        if (btnTabEditEmail != null) {
            UiActionIcons.apply(btnTabEditEmail, "document", "Edit Text");
        }
        if (btnTabPreviewEmail != null) {
            UiActionIcons.apply(btnTabPreviewEmail, "eye", "Executive Preview");
        }
        if (lblEmailInfo != null) {
            lblEmailInfo.setTooltip(new Tooltip("Separate multiple email addresses with a semicolon (;) or comma (,).\nAn official remittance advice will be transmitted to each address."));
            lblEmailInfo.setCursor(javafx.scene.Cursor.HAND);
        }
        if (lblPhoneInfo != null) {
            lblPhoneInfo.setTooltip(new Tooltip("Separate multiple WhatsApp numbers with a semicolon (;) or comma (,).\nAn official remittance advice will be transmitted to each number."));
            lblPhoneInfo.setCursor(javafx.scene.Cursor.HAND);
        }

        // Live Action button dynamic updating
        if (chkEmail != null) {
            chkEmail.selectedProperty().addListener((obs, old, val) -> {
                if (boxEmailFields != null) boxEmailFields.setDisable(!val);
                updateDynamicTabs();
                updateTransmitButtonText();
            });
        }
        if (chkWhatsapp != null) {
            chkWhatsapp.selectedProperty().addListener((obs, old, val) -> {
                if (boxWhatsappFields != null) boxWhatsappFields.setDisable(!val);
                updateDynamicTabs();
                updateTransmitButtonText();
            });
        }

        // Validation listeners
        if (txtRecipientEmail != null) {
            if (txtRecipientEmail.getParent() instanceof VBox parentBox) {
                org.example.util.RecipientChipBox.attach(parentBox, txtRecipientEmail, org.example.util.RecipientChipBox.ChannelType.EMAIL);
            }
            txtRecipientEmail.textProperty().addListener((obs, old, val) -> {
                if (errEmail != null) {
                    errEmail.setVisible(false);
                    errEmail.setManaged(false);
                }
                if (lblPreviewRecipient != null) {
                    lblPreviewRecipient.setText(val == null || val.isBlank() ? "-" : val.trim());
                }
            });
        }
        if (txtRecipientPhone != null) {
            if (txtRecipientPhone.getParent() instanceof VBox parentBox) {
                org.example.util.RecipientChipBox.attach(parentBox, txtRecipientPhone, org.example.util.RecipientChipBox.ChannelType.WHATSAPP);
            }
            txtRecipientPhone.textProperty().addListener((obs, old, val) -> {
                if (errPhone != null) {
                    errPhone.setVisible(false);
                    errPhone.setManaged(false);
                }
            });
        }
        if (txtEmailSubject != null) {
            txtEmailSubject.textProperty().addListener((obs, old, val) -> {
                if (errEmailSubject != null) {
                    errEmailSubject.setVisible(false);
                    errEmailSubject.setManaged(false);
                }
            });
        }
        if (txtEmailBody != null) {
            txtEmailBody.textProperty().addListener((obs, old, val) -> {
                if (lblPreviewBody != null) {
                    lblPreviewBody.setText(val == null ? "" : val);
                }
            });
        }
        if (txtWhatsappMessage != null) {
            txtWhatsappMessage.textProperty().addListener((obs, old, val) -> {
                if (lblWhatsappChatPreview != null) {
                    lblWhatsappChatPreview.setText(val == null ? "" : val);
                }
            });
        }

        // Live Tracking Button
        if (btnLiveStatusSample != null) {
            btnLiveStatusSample.setOnAction(ev -> {
                if (draft == null || draft.publicStatusUrl() == null || draft.publicStatusUrl().isBlank()) {
                    Alert info = new OwnedAlert(Alert.AlertType.INFORMATION);
                    info.setTitle("Live Tracking Status");
                    info.setHeaderText("Status Link Notification");
                    info.setContentText("Live settlement and status verification URL is generated for confirmed payment advice or invoice records.");
                    info.showAndWait();
                    return;
                }
                String url = draft.publicStatusUrl();
                UiTaskExecutor.submitAction("open-live-status-url", () -> {
                    boolean launched = false;
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        try {
                            Desktop.getDesktop().browse(java.net.URI.create(url));
                            launched = true;
                        } catch (Exception ignored) {}
                    }
                    if (!launched) {
                        try {
                            new ProcessBuilder("cmd", "/c", "start", '\"' + url + '\"').start();
                            launched = true;
                        } catch (Exception ignored) {}
                    }
                    if (!launched) {
                        throw new IllegalStateException("System default browser could not be launched for URL: " + url);
                    }
                    return Boolean.TRUE;
                }, ok -> {}, failure -> {
                    showError("Could not launch web browser for live tracking status:\n\n" + failure.getMessage() + "\n\nURL: " + url);
                });
            });
        }
    }

    private void updateDynamicTabs() {
        if (tabPanePreview == null) return;
        boolean emailSelected = chkEmail != null && chkEmail.isSelected();
        boolean whatsappSelected = chkWhatsapp != null && chkWhatsapp.isSelected();

        var tabs = tabPanePreview.getTabs();

        // 1. Manage Email Tab
        if (emailSelected) {
            if (tabEmail != null && !tabs.contains(tabEmail)) {
                tabs.add(0, tabEmail);
            }
        } else {
            if (tabEmail != null) {
                tabs.remove(tabEmail);
            }
        }

        // 2. Manage WhatsApp Tab
        if (whatsappSelected) {
            if (tabWhatsapp != null && !tabs.contains(tabWhatsapp)) {
                int targetIdx = (tabEmail != null && tabs.contains(tabEmail)) ? tabs.indexOf(tabEmail) + 1 : 0;
                tabs.add(Math.min(targetIdx, tabs.size()), tabWhatsapp);
            }
        } else {
            if (tabWhatsapp != null) {
                tabs.remove(tabWhatsapp);
            }
        }

        // 3. Keep Active Focus Harmonious
        Tab current = tabPanePreview.getSelectionModel().getSelectedItem();
        if (current == null || !tabs.contains(current)) {
            if (whatsappSelected && !emailSelected && tabWhatsapp != null) {
                tabPanePreview.getSelectionModel().select(tabWhatsapp);
            } else if (emailSelected && tabEmail != null) {
                tabPanePreview.getSelectionModel().select(tabEmail);
            } else if (!tabs.isEmpty()) {
                tabPanePreview.getSelectionModel().select(0);
            }
        }
    }

    private void updateTransmitButtonText() {
        if (btnTransmit == null) return;
        boolean email = chkEmail != null && chkEmail.isSelected();
        boolean whatsapp = chkWhatsapp != null && chkWhatsapp.isSelected();

        if (email && whatsapp) {
            btnTransmit.setText("🚀 Transmit Email & WhatsApp");
            btnTransmit.setDisable(false);
        } else if (email) {
            btnTransmit.setText("📧 Transmit Email Only");
            btnTransmit.setDisable(false);
        } else if (whatsapp) {
            btnTransmit.setText("💬 Transmit WhatsApp Only");
            btnTransmit.setDisable(false);
        } else {
            btnTransmit.setText("Select at least one channel");
            btnTransmit.setDisable(true);
        }
    }

    public void setDraft(PaymentDraft draft, Dialog<TransmissionResult> dialog) {
        this.draft = draft;
        this.hostDialog = dialog;

        if (draft == null) return;

        if (lblTitle != null && draft.title() != null) lblTitle.setText(draft.title());
        if (lblSubtitle != null && draft.subtitle() != null) lblSubtitle.setText(draft.subtitle());
        if (lblStatusBadge != null && draft.statusBadge() != null) lblStatusBadge.setText(draft.statusBadge());

        // Fill Email fields
        boolean hasEmail = draft.recipientEmail() != null && !draft.recipientEmail().isBlank();
        if (txtRecipientEmail != null) txtRecipientEmail.setText(hasEmail ? draft.recipientEmail().trim() : "");
        if (txtEmailSubject != null) txtEmailSubject.setText(draft.emailSubject() != null ? draft.emailSubject() : "");
        if (txtEmailBody != null) txtEmailBody.setText(draft.emailBody() != null ? draft.emailBody() : "");
        if (lblPreviewBody != null) lblPreviewBody.setText(draft.emailBody() != null ? draft.emailBody() : "");

        // Fill WhatsApp fields
        boolean hasPhone = draft.recipientPhone() != null && !draft.recipientPhone().isBlank();
        if (txtRecipientPhone != null) txtRecipientPhone.setText(hasPhone ? draft.recipientPhone().trim() : "");
        if (txtWhatsappMessage != null) txtWhatsappMessage.setText(draft.whatsappMessage() != null ? draft.whatsappMessage() : "");
        if (lblWhatsappChatPreview != null) lblWhatsappChatPreview.setText(draft.whatsappMessage() != null ? draft.whatsappMessage() : "");

        // Auto-select channels based on preferred channel, available contact info and settings
        ChannelPreference pref = draft.preferredChannel() != null ? draft.preferredChannel() : ChannelPreference.BOTH;
        boolean emailActiveInSettings = org.example.config.ConfigManager.getBoolean("bank.paymentConfirmationEmail.enabled", true);
        boolean whatsappActiveInSettings = org.example.config.ConfigManager.getBoolean("whatsapp.paymentConfirmation.enabled", true);

        if (pref == ChannelPreference.WHATSAPP_ONLY) {
            if (chkWhatsapp != null) chkWhatsapp.setSelected(true);
            if (chkEmail != null) chkEmail.setSelected(false);
        } else if (pref == ChannelPreference.EMAIL_ONLY) {
            if (chkEmail != null) chkEmail.setSelected(true);
            if (chkWhatsapp != null) chkWhatsapp.setSelected(false);
        } else {
            if (chkEmail != null) chkEmail.setSelected(hasEmail && emailActiveInSettings);
            if (chkWhatsapp != null) chkWhatsapp.setSelected(hasPhone && whatsappActiveInSettings);
            if (chkEmail != null && chkWhatsapp != null && !chkEmail.isSelected() && !chkWhatsapp.isSelected()) {
                if (hasEmail && emailActiveInSettings) chkEmail.setSelected(true);
                else if (hasPhone && whatsappActiveInSettings) chkWhatsapp.setSelected(true);
            }
        }
        if (boxEmailFields != null && chkEmail != null) {
            boxEmailFields.setDisable(!chkEmail.isSelected());
        }
        if (boxWhatsappFields != null && chkWhatsapp != null) {
            boxWhatsappFields.setDisable(!chkWhatsapp.isSelected());
        }
        updateDynamicTabs();
        updateTransmitButtonText();

        if (pref == ChannelPreference.WHATSAPP_ONLY && tabWhatsapp != null && tabPanePreview != null) {
            tabPanePreview.getSelectionModel().select(tabWhatsapp);
        } else if (pref == ChannelPreference.EMAIL_ONLY && tabEmail != null && tabPanePreview != null) {
            tabPanePreview.getSelectionModel().select(tabEmail);
        }

        // Fill Executive preview letterhead
        if (lblPreviewCompany != null) lblPreviewCompany.setText(org.example.config.ConfigManager.get("company.name", "JASVI INDUSTRIES"));
        if (lblPreviewBadge != null) lblPreviewBadge.setText(draft.statusBadge() != null ? draft.statusBadge() : "CONFIRMED");
        if (lblPreviewDocNo != null) lblPreviewDocNo.setText(draft.documentNo() != null ? draft.documentNo() : "-");
        if (lblPreviewRecipient != null) lblPreviewRecipient.setText(hasEmail ? draft.recipientEmail() : (hasPhone ? draft.recipientPhone() : "-"));
        if (lblPreviewAmount != null) {
            lblPreviewAmount.setText("₹ " + String.format(java.util.Locale.of("en", "IN"), "%,.2f", draft.paidAmount() != null ? draft.paidAmount() : 0.0));
        }
        if (lblPreviewStatus != null) {
            lblPreviewStatus.setText(draft.balanceAmount() != null && draft.balanceAmount() <= 0.009 ? "FULLY SETTLED" : "PARTIAL SETTLEMENT");
        }

        // Live status CTA box visibility
        if (boxLiveStatusPreview != null) {
            boolean hasStatusUrl = draft.publicStatusUrl() != null && !draft.publicStatusUrl().isBlank();
            boxLiveStatusPreview.setVisible(hasStatusUrl);
            boxLiveStatusPreview.setManaged(hasStatusUrl);
        }

        // Signature image setup
        Path sigPath = draft.signatureImage();
        if (sigPath == null && MessageTemplateService.isAttachSignatureToEmail()) {
            sigPath = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        boolean hasSig = sigPath != null && Files.isRegularFile(sigPath);
        if (lblSigStatusBadge != null) {
            if (hasSig) {
                lblSigStatusBadge.setText("OFFICIALLY STAMPED");
                lblSigStatusBadge.getStyleClass().setAll("status-pill", "status-pill-success");
            } else {
                lblSigStatusBadge.setText("NOT CONFIGURED");
                lblSigStatusBadge.getStyleClass().setAll("status-pill", "status-pill-warning");
            }
        }
        if (lblSignatureInfo != null) {
            lblSignatureInfo.setText(hasSig
                    ? "Appended automatically below letterhead and email advice transmission"
                    : "No signature/stamp uploaded in Settings — Company & Billing");
        }
        if (btnPreviewSignature != null) {
            btnPreviewSignature.setDisable(!hasSig);
        }
        if (boxSignatureImage != null) {
            boxSignatureImage.setVisible(true);
            boxSignatureImage.setManaged(true);
            if (hasSig && imgSignatureThumb != null) {
                try {
                    imgSignatureThumb.setImage(new Image(sigPath.toUri().toString(), 90, 42, true, true));
                } catch (Exception ignored) {}
            }
        }

        // Attachment PDF setup
        boolean hasAttach = draft.attachmentPdf() != null && Files.isRegularFile(draft.attachmentPdf());
        if (boxAttachment != null) {
            boxAttachment.setVisible(hasAttach);
            boxAttachment.setManaged(hasAttach);
            if (hasAttach && lblAttachmentName != null) {
                lblAttachmentName.setText(draft.attachmentPdf().getFileName().toString());
            }
        }

        // Department Mailbox Routing Profile setup
        boolean activityRouting = org.example.config.ConfigManager.getBoolean("email.activityRouting.enabled", false);
        if (boxSenderProfile != null) {
            boxSenderProfile.setVisible(activityRouting);
            boxSenderProfile.setManaged(activityRouting);
        }
        if (activityRouting && cmbSenderProfile != null) {
            cmbSenderProfile.getItems().clear();
            var defProfile = org.example.service.EmailService.defaultSenderProfile();
            var defOpt = new SenderProfileOption("Company Master (" + defProfile.email() + ")", defProfile);
            cmbSenderProfile.getItems().add(defOpt);

            String[] activities = {"sale", "quote", "purchase", "return", "accounts"};
            String[] activityNames = {"Sales & Billing", "Quotations & RFQ", "Procurement & Purchase", "Customer Support / Returns", "Accounts & Finance"};
            SenderProfileOption selectedOpt = defOpt;
            for (int i = 0; i < activities.length; i++) {
                String actKey = activities[i];
                var p = org.example.service.EmailService.resolveSenderProfile(actKey);
                if (p != null && !p.email().isBlank() && !p.email().equalsIgnoreCase(defProfile.email())) {
                    var opt = new SenderProfileOption(activityNames[i] + " (" + p.email() + ")", p);
                    cmbSenderProfile.getItems().add(opt);
                    if (draft.activityKey() != null && draft.activityKey().equalsIgnoreCase(actKey)) {
                        selectedOpt = opt;
                    }
                }
            }
            cmbSenderProfile.setValue(selectedOpt);
        }
    }

    @FXML
    public void showEmailEditMode() {
        if (txtEmailBody != null) {
            txtEmailBody.setVisible(true);
            txtEmailBody.setManaged(true);
        }
        if (boxExecutivePreview != null) {
            boxExecutivePreview.setVisible(false);
            boxExecutivePreview.setManaged(false);
        }
        if (btnTabEditEmail != null) {
            btnTabEditEmail.getStyleClass().removeAll("approved-primary-button");
            btnTabEditEmail.getStyleClass().add("approved-primary-button");
        }
        if (btnTabPreviewEmail != null) {
            btnTabPreviewEmail.getStyleClass().removeAll("approved-primary-button");
        }
    }

    @FXML
    public void showEmailPreviewMode() {
        if (txtEmailBody != null) {
            txtEmailBody.setVisible(false);
            txtEmailBody.setManaged(false);
        }
        if (boxExecutivePreview != null) {
            boxExecutivePreview.setVisible(true);
            boxExecutivePreview.setManaged(true);
            if (lblPreviewBody != null && txtEmailBody != null) {
                lblPreviewBody.setText(txtEmailBody.getText());
            }
        }
        if (btnTabPreviewEmail != null) {
            btnTabPreviewEmail.getStyleClass().removeAll("approved-primary-button");
            btnTabPreviewEmail.getStyleClass().add("approved-primary-button");
        }
        if (btnTabEditEmail != null) {
            btnTabEditEmail.getStyleClass().removeAll("approved-primary-button");
        }
    }

    @FXML
    public void previewHtmlInBrowser() {
        try {
            String recipient = txtRecipientEmail == null ? "" : txtRecipientEmail.getText().trim();
            String docNo = (draft != null && draft.documentNo() != null) ? draft.documentNo() : "-";
            String docLabel = (draft != null && draft.documentLabel() != null) ? draft.documentLabel() : "PAYMENT RECEIPT";
            String statusBadge = (draft != null && draft.statusBadge() != null) ? draft.statusBadge() : "CONFIRMED";
            double total = (draft != null && draft.totalAmount() != null) ? draft.totalAmount() : 0.0;
            double paid = (draft != null && draft.paidAmount() != null) ? draft.paidAmount() : 0.0;
            double balance = (draft != null && draft.balanceAmount() != null) ? draft.balanceAmount() : 0.0;
            String statusUrl = (draft != null) ? draft.publicStatusUrl() : null;
            String bodyContent = txtEmailBody == null ? "" : txtEmailBody.getText();

            String ourBankName = org.example.config.ConfigManager.get("payment.bankName", org.example.config.ConfigManager.get("company.bank.name", "")).trim();
            String ourAccNo = org.example.config.ConfigManager.get("payment.accountNumber", org.example.config.ConfigManager.get("company.bank.account", "")).trim();
            String ourIfsc = org.example.config.ConfigManager.get("payment.ifsc", org.example.config.ConfigManager.get("company.bank.ifsc", "")).trim();
            boolean hasBankDetails = !ourBankName.isBlank() || !ourAccNo.isBlank() || !ourIfsc.isBlank();

            Path sigPath = draft != null ? draft.signatureImage() : null;
            if (sigPath == null && MessageTemplateService.isAttachSignatureToEmail()) {
                sigPath = MessageTemplateService.getOrCreateSignatureImagePath();
            }

            String html = MessageTemplateService.formatExecutiveHtmlEmail(
                    docLabel,
                    statusBadge,
                    docNo,
                    BusinessClock.formatDate(BusinessClock.today()),
                    recipient.isBlank() ? "Valued Client" : recipient,
                    total,
                    paid,
                    balance,
                    bodyContent,
                    hasBankDetails,
                    ourBankName,
                    ourAccNo,
                    ourIfsc,
                    "Live Electronic Settlement Verification",
                    "-",
                    statusUrl,
                    sigPath != null,
                    (sigPath != null) ? sigPath.toUri().toString() : "cid:signatureImage"
            );

            Path tempHtml = Files.createTempFile("dse-payment-preview-", ".html");
            Files.writeString(tempHtml, html, java.nio.charset.StandardCharsets.UTF_8);
            tempHtml.toFile().deleteOnExit();

            UiTaskExecutor.submitAction("open-html-preview", () -> {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(tempHtml.toUri());
                } else {
                    new ProcessBuilder("cmd", "/c", "start", '\"' + tempHtml.toFile().getAbsolutePath() + '\"').start();
                }
                return Boolean.TRUE;
            }, ok -> {}, failure -> {
                showError("Could not launch browser for HTML preview:\n\n" + failure.getMessage());
            });
        } catch (Exception ex) {
            showError("Could not generate HTML preview: " + ex.getMessage());
        }
    }

    @FXML
    public void previewPdf() {
        if (draft == null || draft.attachmentPdf() == null || !Files.isRegularFile(draft.attachmentPdf())) {
            showError("Attached PDF receipt is not available on disk.");
            return;
        }
        File pdfFile = draft.attachmentPdf().toFile();
        UiTaskExecutor.submitAction("open-pdf-viewer", () -> {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pdfFile);
            } else {
                new ProcessBuilder("cmd", "/c", "start", '\"' + pdfFile.getAbsolutePath() + '\"').start();
            }
            return Boolean.TRUE;
        }, ok -> {}, failure -> {
            showError("Could not launch system PDF viewer:\n\n" + failure.getMessage());
        });
    }

    @FXML
    public void previewSignature() {
        Path sigPath = draft != null ? draft.signatureImage() : null;
        if (sigPath == null && MessageTemplateService.isAttachSignatureToEmail()) {
            sigPath = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        if (sigPath == null || !Files.isRegularFile(sigPath)) {
            showError("Company signature / stamp image file is not available.");
            return;
        }
        File sigFile = sigPath.toFile();
        UiTaskExecutor.submitAction("preview-sig-img", () -> {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(sigFile);
            } else {
                new ProcessBuilder("cmd", "/c", "start", '\"' + sigFile.getAbsolutePath() + '\"').start();
            }
            return Boolean.TRUE;
        }, ok -> {}, failure -> {
            showError("Could not launch image viewer:\n\n" + failure.getMessage());
        });
    }

    @FXML
    public void transmit() {
        boolean sendEmail = chkEmail != null && chkEmail.isSelected();
        boolean sendWhatsapp = chkWhatsapp != null && chkWhatsapp.isSelected();

        if (!sendEmail && !sendWhatsapp) {
            showError("Please select at least one transmission channel (Email or WhatsApp).");
            return;
        }

        String recipientEmail = txtRecipientEmail == null ? "" : txtRecipientEmail.getText().trim();
        String emailSubject = txtEmailSubject == null ? "" : txtEmailSubject.getText().trim();
        String emailBody = txtEmailBody == null ? "" : txtEmailBody.getText();

        if (sendEmail) {
            if (recipientEmail.isBlank()) {
                if (errEmail != null) {
                    errEmail.setText("Recipient email address is required.");
                    errEmail.setVisible(true);
                    errEmail.setManaged(true);
                }
                if (tabPanePreview != null) tabPanePreview.getSelectionModel().select(0);
                if (txtRecipientEmail != null) txtRecipientEmail.requestFocus();
                return;
            }
            String[] emails = recipientEmail.split("[;,]");
            boolean allValid = true;
            for (String em : emails) {
                String trimmed = em.trim();
                if (trimmed.isBlank() || !trimmed.contains("@") || !trimmed.contains(".")) {
                    allValid = false;
                    break;
                }
            }
            if (!allValid) {
                if (errEmail != null) {
                    errEmail.setText("Please enter valid recipient email address(es), separated by ';' if multiple.");
                    errEmail.setVisible(true);
                    errEmail.setManaged(true);
                }
                if (tabPanePreview != null) tabPanePreview.getSelectionModel().select(0);
                if (txtRecipientEmail != null) txtRecipientEmail.requestFocus();
                return;
            }
            if (emailSubject.isBlank()) {
                if (errEmailSubject != null) {
                    errEmailSubject.setText("Email subject cannot be blank.");
                    errEmailSubject.setVisible(true);
                    errEmailSubject.setManaged(true);
                }
                if (tabPanePreview != null) tabPanePreview.getSelectionModel().select(0);
                if (txtEmailSubject != null) txtEmailSubject.requestFocus();
                return;
            }
        }

        String recipientPhone = txtRecipientPhone == null ? "" : txtRecipientPhone.getText().trim();
        String whatsappMsg = txtWhatsappMessage == null ? "" : txtWhatsappMessage.getText();

        if (sendWhatsapp) {
            if (recipientPhone.isBlank()) {
                if (errPhone != null) {
                    errPhone.setText("Recipient mobile phone number is required.");
                    errPhone.setVisible(true);
                    errPhone.setManaged(true);
                }
                if (tabPanePreview != null) tabPanePreview.getSelectionModel().select(1);
                if (txtRecipientPhone != null) txtRecipientPhone.requestFocus();
                return;
            }
            String[] phones = recipientPhone.split("[;,]");
            boolean allValid = true;
            for (String ph : phones) {
                String digits = ph.replaceAll("[^0-9]", "");
                if (digits.length() < 7) {
                    allValid = false;
                    break;
                }
            }
            if (!allValid) {
                if (errPhone != null) {
                    errPhone.setText("Please enter valid recipient mobile number(s) (min 7 digits), separated by ';' if multiple.");
                    errPhone.setVisible(true);
                    errPhone.setManaged(true);
                }
                if (tabPanePreview != null) tabPanePreview.getSelectionModel().select(1);
                if (txtRecipientPhone != null) txtRecipientPhone.requestFocus();
                return;
            }
        }

        EmailService.SenderProfile profile = null;
        if (cmbSenderProfile != null && cmbSenderProfile.getValue() != null) {
            profile = cmbSenderProfile.getValue().profile();
        }

        String entityHint = draft != null && draft.entityType() != null && !draft.entityType().isBlank()
                ? draft.entityType()
                : (draft != null ? draft.documentLabel() : "PAYMENT_RECEIPT");
        boolean useHtmlFormat = MessageTemplateService.shouldUseHtmlForEntity(entityHint);

        Path sigPath = draft != null ? draft.signatureImage() : null;
        if (sigPath == null && MessageTemplateService.isAttachSignatureToEmail()) {
            sigPath = MessageTemplateService.getOrCreateSignatureImagePath();
        }
        if (!useHtmlFormat) {
            sigPath = null;
        }

        // Format executive HTML email body if HTML mode is active
        String finalEmailBody = emailBody;
        if (sendEmail && useHtmlFormat && !emailBody.contains("<!DOCTYPE html>")) {
            String docNo = (draft != null && draft.documentNo() != null) ? draft.documentNo() : "-";
            String docLabel = (draft != null && draft.documentLabel() != null) ? draft.documentLabel() : "PAYMENT RECEIPT";
            String statusBadge = (draft != null && draft.statusBadge() != null) ? draft.statusBadge() : "CONFIRMED";
            double total = (draft != null && draft.totalAmount() != null) ? draft.totalAmount() : 0.0;
            double paid = (draft != null && draft.paidAmount() != null) ? draft.paidAmount() : 0.0;
            double balance = (draft != null && draft.balanceAmount() != null) ? draft.balanceAmount() : 0.0;
            String statusUrl = (draft != null) ? draft.publicStatusUrl() : null;

            String ourBankName = org.example.config.ConfigManager.get("payment.bankName", org.example.config.ConfigManager.get("company.bank.name", "")).trim();
            String ourAccNo = org.example.config.ConfigManager.get("payment.accountNumber", org.example.config.ConfigManager.get("company.bank.account", "")).trim();
            String ourIfsc = org.example.config.ConfigManager.get("payment.ifsc", org.example.config.ConfigManager.get("company.bank.ifsc", "")).trim();
            boolean hasBankDetails = !ourBankName.isBlank() || !ourAccNo.isBlank() || !ourIfsc.isBlank();

            finalEmailBody = MessageTemplateService.formatExecutiveHtmlEmail(
                    docLabel,
                    statusBadge,
                    docNo,
                    BusinessClock.formatDate(BusinessClock.today()),
                    recipientEmail.isBlank() ? "Valued Client" : recipientEmail,
                    total,
                    paid,
                    balance,
                    emailBody,
                    hasBankDetails,
                    ourBankName,
                    ourAccNo,
                    ourIfsc,
                    "Live Electronic Settlement Verification",
                    "-",
                    statusUrl,
                    sigPath != null,
                    "cid:signatureImage"
            );
        }

        this.result = new TransmissionResult(
                sendEmail,
                sendWhatsapp,
                recipientEmail,
                emailSubject,
                finalEmailBody,
                recipientPhone,
                whatsappMsg,
                draft != null ? draft.attachmentPdf() : null,
                sigPath,
                profile
        );

        if (hostDialog != null) {
            hostDialog.setResult(this.result);
            hostDialog.close();
        }
    }

    @FXML
    public void cancel() {
        this.result = null;
        if (hostDialog != null) {
            hostDialog.setResult(null);
            hostDialog.close();
        }
    }

    private void showError(String message) {
        Alert alert = new OwnedAlert(Alert.AlertType.ERROR);
        alert.setTitle("Transmission Error");
        alert.setHeaderText("Validation Notification");
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static Optional<TransmissionResult> show(Node ownerNode, PaymentDraft draft) {
        try {
            FXMLLoader loader = new FXMLLoader(ResourceLocator.require("/fxml/pages/PaymentTransmissionDialog.fxml"));
            BorderPane content = loader.load();
            ProfessionalUiEnhancer.enhance(content);

            PaymentTransmissionDialog controller = loader.getController();

            Dialog<TransmissionResult> dialog = new OwnedDialog<>();
            Window owner = DialogOwnerResolver.resolve(ownerNode);
            if (owner != null) dialog.initOwner(owner);

            String title = draft != null && draft.title() != null ? draft.title() : "Transmit Payment Confirmation";
            dialog.setTitle(title);
            dialog.setResizable(true);

            DialogPane pane = dialog.getDialogPane();
            pane.getStyleClass().addAll("approved-dialog", "erp-screen-root", "approved-ui", "entity-dialog", "erp-document-inspection-dialog");
            pane.getProperties().put("dse.dialog.presented", true);
            pane.getProperties().put("dse.dialog.workspace", true);
            pane.setContent(content);

            // Responsive bounds calculation matching PartyDialog & Rule 12 (Zero Truncation Guarantee)
            javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
            double maxW = Math.max(880, screen.getWidth() - 32);
            double maxH = Math.max(600, screen.getHeight() - 32);
            double targetW = Math.min(maxW, Math.max(1040, Math.min(1240, screen.getWidth() * 0.88)));
            double targetH = Math.min(maxH, Math.max(680, Math.min(880, screen.getHeight() * 0.90)));

            pane.setPrefSize(targetW, targetH);
            pane.setMaxWidth(maxW);
            pane.setMaxHeight(maxH);
            pane.setMinWidth(Math.min(860, targetW));
            pane.setMinHeight(Math.min(540, targetH));

            pane.getButtonTypes().add(ButtonType.CLOSE);
            Node closeBtn = pane.lookupButton(ButtonType.CLOSE);
            if (closeBtn != null) {
                closeBtn.setVisible(false);
                closeBtn.setManaged(false);
            }
            dialog.setOnShowing(ev -> {
                Node defaultButtonBar = pane.lookup(".button-bar");
                if (defaultButtonBar != null) {
                    defaultButtonBar.setVisible(false);
                    defaultButtonBar.setManaged(false);
                }
                Node cb = pane.lookupButton(ButtonType.CLOSE);
                if (cb != null) {
                    cb.setVisible(false);
                    cb.setManaged(false);
                }
            });

            dialog.setOnShown(ev -> {
                if (pane.getScene() != null && pane.getScene().getWindow() instanceof javafx.stage.Stage stage) {
                    WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
                }
            });

            pane.sceneProperty().addListener((obs, oldScene, scene) -> {
                if (scene != null) {
                    scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                }
            });

            dialog.setResultConverter(buttonType -> controller.result);
            controller.setDraft(draft, dialog);

            return dialog.showAndWait();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open payment transmission dialog: " + e.getMessage(), e);
        }
    }
}
