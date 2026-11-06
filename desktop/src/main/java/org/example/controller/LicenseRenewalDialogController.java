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
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.example.api.ApiRuntime;
import org.example.api.licensing.LicenseApiClient;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.util.AppDialogService;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
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

    @FXML private Button btnSelfActivateNow;
    @FXML private Button btnSendWhatsAppLead;
    @FXML private Button btnRegisterOnlineLead;
    @FXML private Button btnBrowseLicenseFile;
    @FXML private Label lblSelectedFile;
    @FXML private TextArea txtRawLicensePayload;
    @FXML private Button btnApplyActivation;
    @FXML private Button btnClose;

    private final LicenseApiClient api = new LicenseApiClient();
    private final ObjectMapper json = ApiRuntime.JSON;
    private String currentUpiUri = "";
    private String supportPhone = "9537544008";
    private String upiVpa = "jasviindustries@icici";
    private String payeeName = "Jasvi Industries";
    private String gstRate = "18.00";
    private String leadTemplate = "Hello Jasvi Team, I am {CompanyName} ({ContactPerson}, {Phone}). Terminal ID: {MachineId}. Please activate our Jasvi ERP license.";

    @FXML
    private void initialize() {
        if (headerIconHolder != null) {
            headerIconHolder.getChildren().setAll(IconFactory.compactIcon("security", 20));
        }
        if (txtMachineId != null) {
            txtMachineId.setText(HardwareFingerprintUtil.getDisplayMachineId());
        }
        loadCatalogAsync();
        refreshStatus();
    }

    private void loadCatalogAsync() {
        UiTaskExecutor.submitAction(
                "load-licensing-catalog",
                api::getCatalog,
                cat -> {
                    if (cat != null && !cat.isEmpty()) {
                        if (cat.get("supportPhone") != null) supportPhone = String.valueOf(cat.get("supportPhone")).trim();
                        if (cat.get("upiVpa") != null) upiVpa = String.valueOf(cat.get("upiVpa")).trim();
                        if (cat.get("payeeName") != null) payeeName = String.valueOf(cat.get("payeeName")).trim();
                        if (cat.get("gstRate") != null) gstRate = String.valueOf(cat.get("gstRate")).trim();
                        if (cat.get("whatsappTemplateLead") != null && !String.valueOf(cat.get("whatsappTemplateLead")).isBlank()) {
                            leadTemplate = String.valueOf(cat.get("whatsappTemplateLead")).trim();
                        }
                    }
                    renderUpiQrCode("3500.00");
                },
                err -> renderUpiQrCode("3500.00")
        );
    }

    private void renderUpiQrCode(String amount) {
        double basePrice = 3500.00;
        try { basePrice = Double.parseDouble(amount); } catch (Exception ignored) {}
        double gstPercent = 18.00;
        try { gstPercent = Double.parseDouble(gstRate); } catch (Exception ignored) {}
        double totalWithGst = basePrice * (1.0 + (gstPercent / 100.0));

        String machineId = HardwareFingerprintUtil.getDisplayMachineId();
        String encPayee = payeeName.replace(" ", "+");
        currentUpiUri = String.format(java.util.Locale.ROOT, "upi://pay?pa=%s&pn=%s&am=%.2f&cu=INR&tn=Renewal+%s",
                upiVpa, encPayee, totalWithGst, machineId);

        final double finalBase = basePrice;
        final double finalTotal = totalWithGst;

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
                    if (imgUpiQr != null) {
                        imgUpiQr.setImage(new Image(new ByteArrayInputStream(pngBytes)));
                    }
                    if (lblAmountDisplay != null) {
                        lblAmountDisplay.setText(String.format(java.util.Locale.ROOT, "₹%.0f + %s%% GST = ₹%.0f / Year", finalBase, gstRate, finalTotal));
                    }
                    if (lblVpaDisplay != null) {
                        lblVpaDisplay.setText("VPA: " + upiVpa);
                    }
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
                    if (lblHeaderBadge != null) {
                        lblHeaderBadge.setText("OFFLINE / UNVERIFIED");
                        lblHeaderBadge.getStyleClass().setAll("status-badge", "badge-warning");
                    }
                }
        );
    }

    private void applyStatus(LicenseApiClient.LicenseStatusDto dto) {
        if (dto == null) return;

        if (lblCompany != null) lblCompany.setText(dto.companyName() != null ? dto.companyName() : "-");
        if (lblTier != null) lblTier.setText(dto.tier() != null ? dto.tier() : "GOLD");
        if (lblValidUntil != null) {
            lblValidUntil.setText(dto.validUntil() != null
                    ? DateTimeFormatter.ISO_INSTANT.format(dto.validUntil()) : "-");
        }
        if (lblDaysRemaining != null) lblDaysRemaining.setText(dto.daysRemaining() + " Days");

        if (lblHeaderBadge != null) {
            lblHeaderBadge.getStyleClass().clear();
            lblHeaderBadge.getStyleClass().add("status-badge");

            switch (dto.status()) {
                case "ACTIVE" -> {
                    lblHeaderBadge.setText("ACTIVE");
                    lblHeaderBadge.getStyleClass().addAll("status-positive", "badge-active");
                }
                case "EXPIRING_SOON" -> {
                    lblHeaderBadge.setText("EXPIRING IN " + dto.daysRemaining() + " DAYS");
                    lblHeaderBadge.getStyleClass().addAll("status-warning", "badge-warning");
                }
                case "EXPIRED" -> {
                    lblHeaderBadge.setText("EXPIRED");
                    lblHeaderBadge.getStyleClass().addAll("status-negative", "badge-cancelled");
                }
                case "MACHINE_MISMATCH" -> {
                    lblHeaderBadge.setText("MACHINE MISMATCH");
                    lblHeaderBadge.getStyleClass().addAll("status-negative", "badge-cancelled");
                }
                case "CLOCK_TAMPERED" -> {
                    lblHeaderBadge.setText("CLOCK TAMPERED");
                    lblHeaderBadge.getStyleClass().addAll("status-negative", "badge-cancelled");
                }
                default -> {
                    lblHeaderBadge.setText(dto.status());
                    lblHeaderBadge.getStyleClass().addAll("status-neutral", "badge-inactive");
                }
            }
        }
    }

    @FXML
    private void handleCopyMachineId() {
        if (txtMachineId != null) {
            ClipboardContent content = new ClipboardContent();
            content.putString(txtMachineId.getText());
            Clipboard.getSystemClipboard().setContent(content);
            ToastManager.info(btnClose, "Copied", "Machine ID copied: " + txtMachineId.getText());
        }
    }

    @FXML
    private void handleSendWhatsAppLead() {
        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim() : "Our Company";
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();

        String msg = leadTemplate
                .replace("{CompanyName}", company)
                .replace("{ContactPerson}", "Representative")
                .replace("{Phone}", "")
                .replace("{MachineId}", machineId);

        String cleanPhone = supportPhone.replaceAll("[^0-9]", "");
        if (!cleanPhone.startsWith("91") && cleanPhone.length() == 10) cleanPhone = "91" + cleanPhone;

        try {
            String url = "https://wa.me/" + cleanPhone + "?text=" + java.net.URLEncoder.encode(msg, StandardCharsets.UTF_8);
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ex) {
            AppDialogService.error(btnClose, "WhatsApp Error", "Could not open browser", ex.getMessage());
        }
    }

    @FXML
    private void handleRegisterOnlineLead() {
        OwnedDialog<Map<String, Object>> dialog = new OwnedDialog<>(btnClose);
        dialog.setTitle("Register Online with Jasvi Cloud");
        dialog.setHeaderText("Submit your company details directly to Jasvi Support");

        ButtonType btnSubmit = new ButtonType("Submit Lead", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnSubmit, ButtonType.CANCEL);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(20, 24, 20, 24));

        TextField txtComp = new TextField(lblCompany != null && !"-".equals(lblCompany.getText()) ? lblCompany.getText() : "");
        txtComp.setPromptText("e.g. Surat Silk Mills Pvt Ltd");

        TextField txtContact = new TextField();
        txtContact.setPromptText("e.g. Ramesh Patel");

        TextField txtPhone = new TextField();
        txtPhone.setPromptText("e.g. 9825012345");

        TextField txtEmail = new TextField();
        txtEmail.setPromptText("e.g. contact@suratsilk.com");

        grid.add(new Label("Company Name *:"), 0, 0);
        grid.add(txtComp, 1, 0);
        grid.add(new Label("Contact Person:"), 0, 1);
        grid.add(txtContact, 1, 1);
        grid.add(new Label("Phone (WhatsApp) *:"), 0, 2);
        grid.add(txtPhone, 1, 2);
        grid.add(new Label("Email:"), 0, 3);
        grid.add(txtEmail, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> {
            if (btn == btnSubmit) {
                return Map.of(
                        "companyName", txtComp.getText().trim(),
                        "contactPerson", txtContact.getText().trim(),
                        "phone", txtPhone.getText().trim(),
                        "email", txtEmail.getText().trim(),
                        "machineId", HardwareFingerprintUtil.getDisplayMachineId()
                );
            }
            return null;
        });

        dialog.showAndWait().ifPresent(req -> {
            if (String.valueOf(req.get("companyName")).isBlank() || String.valueOf(req.get("phone")).isBlank()) {
                ToastManager.warning(btnClose, "Validation", "Company Name and Phone are required.");
                return;
            }
            UiTaskExecutor.submitAction(
                    "submit-lead",
                    () -> api.selfRegister(req),
                    res -> ToastManager.success(btnClose, "Submitted", "Registration sent to Jasvi Support! We will review and activate your license shortly."),
                    err -> AppDialogService.error(btnClose, "Submission Failed", "Could not submit lead", err.getMessage())
            );
        });
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
    private void handleSelfActivateNow() {
        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim()
                : "JASVI INDUSTRIES";
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();

        Map<String, Object> req = Map.of(
                "companyName", company,
                "machineId", machineId,
                "days", 365,
                "tier", "GOLD",
                "maxUsers", 5
        );

        UiTaskExecutor.submitAction(
                "self-activate-now",
                () -> api.issueClientLicense(req),
                resp -> {
                    try {
                        Object licJsonObj = resp.get("licenseJson");
                        if (licJsonObj == null) {
                            throw new IllegalStateException("Missing licenseJson in issue response");
                        }
                        api.activate(licJsonObj);
                        refreshStatus();
                        ToastManager.success(btnClose, "Computer Activated", "License activated and locked to this computer successfully!");
                    } catch (Exception ex) {
                        AppDialogService.error(btnClose, "Activation Error", "Could not activate generated license", ex.getMessage());
                    }
                },
                err -> AppDialogService.error(btnClose, "Activation Failed", "Could not generate client license", err.getMessage())
        );
    }

    @FXML
    private void handleBrowseLicenseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Jasvi License File");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Jasvi License Files (*.jlic, *.lic, *.json)", "*.jlic", "*.lic", "*.json"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fileChooser.showOpenDialog(btnClose.getScene().getWindow());
        if (file != null) {
            lblSelectedFile.setText(file.getName());
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
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
                    "You entered a Machine Hardware ID (" + trimmed + ").\n\nPlease paste the complete cryptographically signed License JSON issued by Jasvi, or click 'Browse License File' to select your license file.");
            return;
        }

        UiTaskExecutor.submitAction(
                "apply-license",
                () -> {
                    JsonNode root;
                    try {
                        root = json.readTree(raw);
                    } catch (Exception parseEx) {
                        throw new IllegalArgumentException("The provided text is not valid JSON. Please paste the complete license JSON payload or click 'Browse License File'.", parseEx);
                    }
                    if (root == null || !root.isObject()) {
                        throw new IllegalArgumentException("Invalid license format: Expected a JSON object containing license header, payload, and signature.");
                    }
                    @SuppressWarnings("unchecked")
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
    private void handleClose() {
        Stage stage = (Stage) btnClose.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }

    public static void show(javafx.stage.Window owner) {
        show(owner, 0);
    }

    public static void show(javafx.stage.Window owner, int defaultTabIndex) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(LicenseRenewalDialogController.class.getResource("/fxml/pages/LicenseRenewalDialog.fxml"));
            javafx.scene.Parent root = loader.load();
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setTitle("Jasvi Desktop Subscription & Machine Activation");
            stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            if (owner != null) {
                stage.initOwner(owner);
            }
            stage.setScene(new javafx.scene.Scene(root));
            stage.show();
        } catch (Exception e) {
            org.example.util.AppDialogService.error(null, "Licensing", "Could not open Licensing", e.getMessage());
        }
    }
}
