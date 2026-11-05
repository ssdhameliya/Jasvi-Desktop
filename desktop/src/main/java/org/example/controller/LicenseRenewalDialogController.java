package org.example.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.barcodes.BarcodeQRCode;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.api.ApiRuntime;
import org.example.api.licensing.LicenseApiClient;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;
import org.example.util.ToastManager;
import org.example.util.UiTaskExecutor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public class LicenseRenewalDialogController {

    @FXML private StackPane headerIconHolder;
    @FXML private Label lblHeaderBadge;
    @FXML private ImageView imgUpiQr;
    @FXML private Label lblAmountDisplay;
    @FXML private Label lblVpaDisplay;
    @FXML private Button btnCopyUpiUri;

    @FXML private TextField txtMachineId;
    @FXML private Button btnCopyMachineId;
    @FXML private Label lblCompany;
    @FXML private Label lblTier;
    @FXML private Label lblValidUntil;
    @FXML private Label lblDaysRemaining;

    @FXML private Button btnBrowseLicenseFile;
    @FXML private Label lblSelectedFile;
    @FXML private TextArea txtRawLicensePayload;
    @FXML private Button btnApplyActivation;
    @FXML private Button btnClose;

    @FXML private TabPane tabPaneLicensing;
    @FXML private Tab tabSubscription;
    @FXML private Tab tabMasterLicensor;
    @FXML private TextField txtClientCompany;
    @FXML private TextField txtClientMachineId;
    @FXML private ComboBox<String> cmbGenTier;
    @FXML private ComboBox<String> cmbGenDuration;
    @FXML private ComboBox<String> cmbGenFormat;
    @FXML private Button btnGenerateClientLicense;
    @FXML private TextArea txtGeneratedPreview;
    @FXML private Button btnCopyGeneratedToken;

    private final LicenseApiClient api = new LicenseApiClient();
    private final ObjectMapper json = ApiRuntime.JSON;
    private String currentUpiUri = "";

    @FXML
    private void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.compactIcon("shield", 18));
        }
        txtMachineId.setText(HardwareFingerprintUtil.getDisplayMachineId());
        renderUpiQrCode("3500.00");
        refreshStatus();

        boolean isSuper = org.example.service.SessionService.isSuperAdmin();
        if (!isSuper && tabPaneLicensing != null && tabMasterLicensor != null) {
            tabPaneLicensing.getTabs().remove(tabMasterLicensor);
        }
        if (cmbGenTier != null) {
            cmbGenTier.getItems().setAll("GOLD (5 Users)", "SILVER (1 User)", "PLATINUM (20 Users)");
            cmbGenTier.getSelectionModel().selectFirst();
        }
        if (cmbGenDuration != null) {
            cmbGenDuration.getItems().setAll("365 Days (1 Year)", "730 Days (2 Years)", "30 Days (Evaluation Trial)", "1095 Days (3 Years)");
            cmbGenDuration.getSelectionModel().selectFirst();
        }
        if (cmbGenFormat != null) {
            cmbGenFormat.getItems().setAll("Jasvi Signed License (*.jlic)", "Enterprise License (*.lic)", "Universal JSON License (*.json)");
            cmbGenFormat.getSelectionModel().selectFirst();
        }
    }

    private void renderUpiQrCode(String amount) {
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();
        currentUpiUri = String.format("upi://pay?pa=jasviindustries@icici&pn=Jasvi+Industries&am=%s&cu=INR&tn=Renewal+%s",
                amount, machineId);

        UiTaskExecutor.submitAction(
                "render-upi-qr",
                () -> {
                    BarcodeQRCode qrcode = new BarcodeQRCode(currentUpiUri);
                    java.awt.Image awtImg = qrcode.createAwtImage(Color.BLACK, Color.WHITE);

                    int size = 260;
                    BufferedImage bufferedImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2d = bufferedImage.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2d.drawImage(awtImg, 0, 0, size, size, null);
                    g2d.dispose();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(bufferedImage, "PNG", baos);
                    return baos.toByteArray();
                },
                pngBytes -> {
                    imgUpiQr.setImage(new Image(new ByteArrayInputStream(pngBytes)));
                    lblAmountDisplay.setText("₹" + amount + " + GST = ₹4,130 / Year");
                    lblVpaDisplay.setText("VPA: jasviindustries@icici");
                },
                err -> AppDialogService.error(btnClose, "QR Render Error", "Failed to render UPI QR", err.getMessage())
        );
    }

    private void refreshStatus() {
        UiTaskExecutor.submitLatest(
                "license-status-load",
                api::getStatus,
                this::applyStatus,
                err -> {
                    lblHeaderBadge.setText("OFFLINE / UNVERIFIED");
                    lblHeaderBadge.getStyleClass().setAll("status-badge", "badge-warning");
                }
        );
    }

    private void applyStatus(LicenseApiClient.LicenseStatusDto dto) {
        if (dto == null) return;

        lblCompany.setText(dto.companyName() != null ? dto.companyName() : "-");
        lblTier.setText(dto.tier() != null ? dto.tier() : "SILVER");
        lblValidUntil.setText(dto.validUntil() != null
                ? DateTimeFormatter.ISO_INSTANT.format(dto.validUntil()) : "-");
        lblDaysRemaining.setText(dto.daysRemaining() + " Days");

        lblHeaderBadge.getStyleClass().clear();
        lblHeaderBadge.getStyleClass().add("status-badge");

        switch (dto.status()) {
            case "ACTIVE" -> {
                lblHeaderBadge.setText("ACTIVE");
                lblHeaderBadge.getStyleClass().add("badge-success");
            }
            case "EXPIRING_SOON" -> {
                lblHeaderBadge.setText("EXPIRING IN " + dto.daysRemaining() + " DAYS");
                lblHeaderBadge.getStyleClass().add("badge-warning");
            }
            case "EXPIRED" -> {
                lblHeaderBadge.setText("EXPIRED");
                lblHeaderBadge.getStyleClass().add("badge-danger");
            }
            case "CLOCK_TAMPERED" -> {
                lblHeaderBadge.setText("CLOCK TAMPERED");
                lblHeaderBadge.getStyleClass().add("badge-danger");
            }
            default -> {
                lblHeaderBadge.setText(dto.status());
                lblHeaderBadge.getStyleClass().add("badge-info");
            }
        }
    }

    @FXML
    private void handleCopyMachineId() {
        ClipboardContent content = new ClipboardContent();
        content.putString(txtMachineId.getText());
        Clipboard.getSystemClipboard().setContent(content);
        ToastManager.info(btnClose, "Copied", "Machine ID copied: " + txtMachineId.getText());
    }

    @FXML
    private void handleCopyUpiString() {
        if (!currentUpiUri.isBlank()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(currentUpiUri);
            Clipboard.getSystemClipboard().setContent(content);
            ToastManager.info(btnClose, "Copied", "UPI payment string copied to clipboard!");
        }
    }

    @FXML
    private void handleBrowseLicenseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select License File (.jlic, .lic, .json)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("All Supported License Files (*.jlic, *.lic, *.json)", "*.jlic", "*.lic", "*.json"),
                new FileChooser.ExtensionFilter("Jasvi License (*.jlic)", "*.jlic"),
                new FileChooser.ExtensionFilter("Enterprise License (*.lic)", "*.lic"),
                new FileChooser.ExtensionFilter("Universal JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("All Files (*.*)", "*.*")
        );
        File file = chooser.showOpenDialog(btnClose.getScene().getWindow());
        if (file != null) {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                lblSelectedFile.setText(file.getName());
                txtRawLicensePayload.setText(content);
            } catch (Exception e) {
                AppDialogService.error(btnClose, "File Error", "Could not read license file", e.getMessage());
            }
        }
    }

    @FXML
    private void handleApplyActivation() {
        String raw = txtRawLicensePayload.getText();
        if (raw == null || raw.isBlank()) {
            AppDialogService.warning(btnClose, "Missing Key", "No Activation Data", "Please select a license file or paste your license JSON.");
            return;
        }

        String trimmed = raw.trim();
        if (trimmed.length() <= 32 && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            AppDialogService.warning(btnClose, "Machine ID Detected", "Full License JSON Required",
                    "You entered a Machine Hardware ID (" + trimmed + ").\n\nPlease paste the complete cryptographically signed License JSON issued by Jasvi, or click 'Browse jasvi.lic File' to select your license file.");
            return;
        }

        UiTaskExecutor.submitAction(
                "apply-license",
                () -> {
                    JsonNode root;
                    try {
                        root = json.readTree(raw);
                    } catch (Exception parseEx) {
                        throw new IllegalArgumentException("The provided text is not valid JSON. Please paste the complete license JSON payload or click 'Browse jasvi.lic File'.", parseEx);
                    }
                    if (root == null || !root.isObject()) {
                        throw new IllegalArgumentException("Invalid license format: Expected a JSON object containing license header, payload, and signature.");
                    }
                    Map<String, Object> payloadMap = json.convertValue(root, Map.class);
                    return api.activate(payloadMap);
                },
                result -> {
                    ToastManager.success(btnClose, "License Activated", "License activated successfully!");
                    refreshStatus();
                },
                err -> AppDialogService.error(btnClose, "Activation Failed", "Invalid License File", err.getMessage())
        );
    }

    @FXML
    private void handleGenerateClientLicense() {
        String company = txtClientCompany == null || txtClientCompany.getText() == null ? "" : txtClientCompany.getText().trim();
        String machine = txtClientMachineId == null || txtClientMachineId.getText() == null ? "" : txtClientMachineId.getText().trim();
        if (company.isBlank() || machine.isBlank()) {
            AppDialogService.warning(btnClose, "Missing Information", "Company Name & Machine ID Required",
                    "Please enter both the Client Company Name and their 16-character Machine Hardware ID.");
            return;
        }

        String tierChoice = (cmbGenTier == null || cmbGenTier.getValue() == null) ? "GOLD" : cmbGenTier.getValue();
        String tier = tierChoice.startsWith("SILVER") ? "SILVER" : (tierChoice.startsWith("PLATINUM") ? "PLATINUM" : "GOLD");
        int maxUsers = "SILVER".equals(tier) ? 1 : ("PLATINUM".equals(tier) ? 20 : 5);

        String durChoice = (cmbGenDuration == null || cmbGenDuration.getValue() == null) ? "365" : cmbGenDuration.getValue();
        int days = durChoice.startsWith("30") ? 30 : (durChoice.startsWith("730") ? 730 : (durChoice.startsWith("1095") ? 1095 : 365));

        String formatChoice = (cmbGenFormat == null || cmbGenFormat.getValue() == null) ? ".jlic" : cmbGenFormat.getValue();
        String extension = formatChoice.contains(".lic") ? ".lic" : (formatChoice.contains(".json") ? ".json" : ".jlic");
        String extDesc = formatChoice.contains(".lic") ? "Enterprise License (*.lic)" : (formatChoice.contains(".json") ? "Universal JSON (*.json)" : "Jasvi License (*.jlic)");

        Map<String, Object> req = Map.of(
                "companyName", company,
                "machineId", machine,
                "days", days,
                "tier", tier,
                "maxUsers", maxUsers
        );

        UiTaskExecutor.submitAction(
                "issue-client-license",
                () -> api.issueClientLicense(req),
                resp -> {
                    try {
                        Object licObj = resp.get("licenseJson");
                        String licJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(licObj);
                        if (txtGeneratedPreview != null) {
                            txtGeneratedPreview.setText(licJson);
                        }
                        ClipboardContent cb = new ClipboardContent();
                        cb.putString(licJson);
                        Clipboard.getSystemClipboard().setContent(cb);

                        FileChooser chooser = new FileChooser();
                        chooser.setTitle("Save Client License (" + extension + ")");
                        chooser.setInitialFileName(company.replaceAll("[^a-zA-Z0-9_-]", "_") + extension);
                        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extDesc, "*" + extension));
                        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("All Files (*.*)", "*.*"));
                        File saveFile = chooser.showSaveDialog(btnClose.getScene().getWindow());
                        if (saveFile != null) {
                            Files.writeString(saveFile.toPath(), licJson, StandardCharsets.UTF_8);
                            ToastManager.success(btnClose, "License Created", "License saved to " + saveFile.getName() + " and copied to clipboard!");
                        } else {
                            ToastManager.info(btnClose, "License Copied", "License JSON copied to clipboard!");
                        }
                    } catch (Exception ex) {
                        AppDialogService.error(btnClose, "Save Error", "Could not export license", ex.getMessage());
                    }
                },
                err -> AppDialogService.error(btnClose, "Generation Failed", "Could not generate client license", err.getMessage())
        );
    }

    @FXML
    private void handleCopyGeneratedToken() {
        if (txtGeneratedPreview != null && !txtGeneratedPreview.getText().isBlank()) {
            ClipboardContent cb = new ClipboardContent();
            cb.putString(txtGeneratedPreview.getText());
            Clipboard.getSystemClipboard().setContent(cb);
            ToastManager.info(btnClose, "Copied", "Generated license token copied to clipboard!");
        }
    }

    @FXML
    private void handleClose() {
        Stage stage = (Stage) btnClose.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }
}
