package org.example.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.example.util.DialogOwnerResolver;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.UiTaskExecutor;
import org.example.util.WindowUtilsFx;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Enterprise WhatsApp Transmission Dialog for Jasvi ERP Desktop.
 * Provides complete visual inspection of recipient phone number, pre-formatted
 * message body, sender channel status, and attached document PDF preview before WhatsApp delivery.
 */
public class WhatsappTransmissionDialog {

    public record WhatsappDraft(
            String title,
            String subtitle,
            String documentLabel,
            String documentNo,
            String recipientPhone,
            String message,
            Path attachmentPdf,
            String senderInfo
    ) {}

    public record WhatsappTransmissionResult(
            String recipientPhone,
            String message,
            Path attachmentPdf
    ) {}

    @FXML private StackPane senderIconHolder;
    @FXML private Label lblSenderTitle;
    @FXML private Label lblSenderInfo;
    @FXML private Label lblChannelStatusBadge;

    @FXML private TextField txtRecipientPhone;
    @FXML private Label errRecipientPhone;

    @FXML private TextArea txtMessage;

    @FXML private HBox boxAttachment;
    @FXML private StackPane attachIconHolder;
    @FXML private Label lblAttachmentName;
    @FXML private Label lblAttachmentDesc;
    @FXML private Button btnPreviewPdf;

    @FXML private Button btnCancel;
    @FXML private Button btnSend;

    private WhatsappDraft draft;
    private Dialog<WhatsappTransmissionResult> hostDialog;
    private WhatsappTransmissionResult result;

    public void initialize() {
        if (senderIconHolder != null) {
            senderIconHolder.getChildren().setAll(IconFactory.icon("whatsapp", 22));
        }
        if (attachIconHolder != null) {
            attachIconHolder.getChildren().setAll(IconFactory.icon("file", 18));
        }
        if (btnPreviewPdf != null) {
            org.example.util.UiActionIcons.apply(btnPreviewPdf, "eye", "Preview PDF");
        }
        if (btnSend != null) {
            org.example.util.UiActionIcons.apply(btnSend, "whatsapp", "Send WhatsApp");
        }
        if (txtRecipientPhone != null) {
            txtRecipientPhone.textProperty().addListener((obs, old, val) -> {
                if (errRecipientPhone != null) {
                    errRecipientPhone.setVisible(false);
                    errRecipientPhone.setManaged(false);
                }
            });
        }
    }

    public void setDraft(WhatsappDraft draft, Dialog<WhatsappTransmissionResult> dialog) {
        this.draft = draft;
        this.hostDialog = dialog;

        if (lblSenderInfo != null && draft.senderInfo() != null && !draft.senderInfo().isBlank()) {
            lblSenderInfo.setText(draft.senderInfo());
        }

        if (txtRecipientPhone != null) {
            txtRecipientPhone.setText(draft.recipientPhone() == null ? "" : draft.recipientPhone().trim());
        }

        if (txtMessage != null) {
            txtMessage.setText(draft.message() == null ? "" : draft.message());
            javafx.application.Platform.runLater(() -> {
                txtMessage.positionCaret(0);
                txtMessage.deselect();
                txtMessage.requestLayout();
            });
        }

        // Attachment PDF verification
        boolean hasAttachment = draft.attachmentPdf() != null
                && Files.isRegularFile(draft.attachmentPdf())
                && Files.isReadable(draft.attachmentPdf());
        if (boxAttachment != null) {
            boxAttachment.setVisible(hasAttachment);
            boxAttachment.setManaged(hasAttachment);
            if (hasAttachment && lblAttachmentName != null) {
                lblAttachmentName.setText(draft.attachmentPdf().getFileName().toString());
            }
            if (hasAttachment && lblAttachmentDesc != null) {
                String docLabel = draft.documentLabel() == null ? "Document" : draft.documentLabel();
                lblAttachmentDesc.setText("Official " + docLabel + " PDF attached automatically");
            }
        }

        if (btnSend != null) {
            String label = draft.documentLabel() == null ? "Document" : draft.documentLabel();
            btnSend.setText("Send " + label + " via WhatsApp");
            org.example.util.UiActionIcons.apply(btnSend, "whatsapp", "Send " + label + " via WhatsApp");
        }
    }

    @FXML
    private void previewPdf() {
        if (draft == null || draft.attachmentPdf() == null || !Files.isRegularFile(draft.attachmentPdf())) {
            showError("Document PDF is not yet available for preview.");
            return;
        }
        UiTaskExecutor.submitAction("preview-whatsapp-pdf", () -> {
            File pdfFile = draft.attachmentPdf().toFile();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(pdfFile);
            } else {
                new ProcessBuilder("cmd", "/c", "start", "\"" + pdfFile.getAbsolutePath() + "\"").start();
            }
            return Boolean.TRUE;
        }, done -> {}, failure -> {
            showError("Could not launch system PDF viewer:\n\n" + failure.getMessage());
        });
    }

    @FXML
    private void cancel() {
        if (hostDialog != null) {
            hostDialog.setResult(null);
            hostDialog.close();
        }
    }

    @FXML
    private void send() {
        String rawPhone = txtRecipientPhone.getText() == null ? "" : txtRecipientPhone.getText().trim();
        String digits = rawPhone.replaceAll("\\D", "");

        if (digits.length() < 10) {
            if (errRecipientPhone != null) {
                errRecipientPhone.setText("Please enter a valid mobile number with at least 10 digits");
                errRecipientPhone.setVisible(true);
                errRecipientPhone.setManaged(true);
            }
            txtRecipientPhone.requestFocus();
            return;
        }

        String finalPhone = digits.length() == 10 ? "91" + digits : digits;
        String messageBody = txtMessage.getText() == null ? "" : txtMessage.getText().trim();
        Path attachment = draft != null ? draft.attachmentPdf() : null;

        this.result = new WhatsappTransmissionResult(finalPhone, messageBody, attachment);

        if (hostDialog != null) {
            hostDialog.setResult(result);
            hostDialog.close();
        }
    }

    private void showError(String message) {
        Alert alert = new org.example.util.OwnedAlert(Alert.AlertType.ERROR);
        alert.setTitle("WhatsApp Transmission");
        alert.setHeaderText("PDF Preview Notification");
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static Optional<WhatsappTransmissionResult> show(Node ownerNode, WhatsappDraft draft) {
        try {
            FXMLLoader loader = new FXMLLoader(org.example.util.ResourceLocator.require("/fxml/pages/WhatsappTransmissionDialog.fxml"));
            BorderPane content = loader.load();
            org.example.util.ProfessionalUiEnhancer.enhance(content);

            WhatsappTransmissionDialog controller = loader.getController();

            Dialog<WhatsappTransmissionResult> dialog = new OwnedDialog<>();
            Window owner = DialogOwnerResolver.resolve(ownerNode);
            if (owner != null) dialog.initOwner(owner);

            String title = draft.title() != null ? draft.title() : "Transmit via WhatsApp";
            String subtitle = draft.subtitle() != null ? draft.subtitle() : "Direct delivery via WhatsApp Business Engine to customer registered mobile";

            dialog.setTitle(title);
            org.example.util.AppDialogRenderer.configureWorkspace(dialog, "info",
                    title, title, subtitle, "");
            dialog.setResizable(true);

            DialogPane pane = dialog.getDialogPane();
            pane.getStyleClass().add("erp-document-inspection-dialog");
            pane.getProperties().put("dse.dialog.presented", true);
            pane.getProperties().put("dse.dialog.workspace", true);
            pane.setContent(content);
            javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
            double maxW = Math.max(760, screen.getWidth() - 48);
            double maxH = Math.max(520, screen.getHeight() - 48);
            double targetW = Math.min(maxW, Math.max(760, Math.min(860, screen.getWidth() * 0.75)));
            double targetH = Math.min(maxH, Math.max(540, Math.min(740, screen.getHeight() * 0.82)));
            pane.setPrefSize(targetW, targetH);
            pane.setMaxWidth(maxW);
            pane.setMaxHeight(maxH);
            pane.setMinWidth(Math.min(720, targetW));
            pane.setMinHeight(Math.min(480, targetH));
            pane.getButtonTypes().add(ButtonType.CLOSE);
            Node closeBtn = pane.lookupButton(ButtonType.CLOSE);
            if (closeBtn != null) {
                closeBtn.setVisible(false);
                closeBtn.setManaged(false);
            }
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
            throw new IllegalStateException("Failed to open WhatsApp transmission dialog: " + e.getMessage(), e);
        }
    }
}
