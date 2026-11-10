package org.example.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.barcodes.BarcodeQRCode;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
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
import org.example.api.ApiSession;
import org.example.api.licensing.LicenseApiClient;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.util.AppDialogRenderer;
import org.example.util.AppDialogService;
import org.example.util.DocumentInspectionScaffold;
import org.example.util.IconFactory;
import org.example.util.OwnedDialog;
import org.example.util.UiActionIcons;
import org.example.util.UiTaskExecutor;
import org.example.util.WindowUtilsFx;

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
    private volatile LicenseApiClient.LicenseStatusDto currentStatusDto;
    private Dialog<?> hostDialog;
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
        if (btnApplyActivation != null) UiActionIcons.apply(btnApplyActivation, "complete", "Apply & Activate License");
        if (btnClose != null) UiActionIcons.apply(btnClose, "cancel", "Close");
        if (btnSendWhatsAppLead != null) UiActionIcons.apply(btnSendWhatsAppLead, "communication", "Send Machine ID via WhatsApp");
        if (btnRegisterOnlineLead != null) UiActionIcons.apply(btnRegisterOnlineLead, "link", "Register Online");
        if (btnSelfActivateNow != null) UiActionIcons.apply(btnSelfActivateNow, "security", "Activate Locally");
        if (btnBrowseLicenseFile != null) UiActionIcons.apply(btnBrowseLicenseFile, "document", "Browse License File");
        if (btnCopyUpiUri != null) UiActionIcons.apply(btnCopyUpiUri, "copy", "Copy UPI Payment String");
        if (btnCopyMachineId != null) UiActionIcons.apply(btnCopyMachineId, "copy", "Copy ID");
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
                err -> AppDialogService.error(btnApplyActivation != null ? btnApplyActivation : txtMachineId, "QR Render Error", "Failed to render UPI QR", err.getMessage())
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
        this.currentStatusDto = dto;

        if (lblCompany != null) lblCompany.setText(dto.companyName() != null ? dto.companyName() : "-");
        if (lblTier != null) lblTier.setText(dto.tier() != null ? dto.tier() : "GOLD");
        if (lblValidUntil != null) {
            String formattedDate = "-";
            if (dto.validUntil() != null) {
                try {
                    java.time.LocalDate date = dto.validUntil().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    formattedDate = date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"));
                } catch (Exception ignored) {
                    formattedDate = dto.validUntil().toString();
                }
            }
            lblValidUntil.setText(formattedDate);
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
            applyCopiedFeedback(btnCopyMachineId, "Copied!");
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
                AppDialogService.warning(btnClose, "Validation", "Company Name and Phone are required.");
                return;
            }
            UiTaskExecutor.submitAction(
                    "submit-lead",
                    () -> api.selfRegister(req),
                    res -> AppDialogService.success(btnClose, "Submitted", "Registration sent to Jasvi Support! We will review and activate your license shortly."),
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
            applyCopiedFeedback(btnCopyUpiUri, "Copied UPI!");
        }
    }

    private void applyCopiedFeedback(Button btn, String msg) {
        if (btn == null) return;
        String orig = btn.getText();
        javafx.scene.Node graphic = btn.getGraphic();
        btn.setText(msg);
        btn.setGraphic(org.example.util.IconFactory.compactIcon("complete", 14));
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(1500));
        pause.setOnFinished(e -> {
            btn.setText(orig);
            btn.setGraphic(graphic);
        });
        pause.play();
    }

    @FXML
    private void handleSelfActivateNow() {
        LicenseApiClient.LicenseStatusDto cur = currentStatusDto;
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();

        if (cur != null && "ACTIVE".equalsIgnoreCase(cur.status()) && cur.daysRemaining() > 0) {
            String expDate = (lblValidUntil != null && !lblValidUntil.getText().isBlank() && !"-".equals(lblValidUntil.getText()))
                    ? lblValidUntil.getText()
                    : (cur.validUntil() != null ? cur.validUntil().atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")) : "N/A");
            AppDialogService.info(btnClose, "Terminal Already Activated",
                    "This computer is already activated with an active " + cur.tier() +
                    " subscription valid until " + expDate + " (" + cur.daysRemaining() + " days remaining).\n\nNo further activation is required.");
            return;
        }

        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim()
                : (cur != null && cur.companyName() != null && !cur.companyName().isBlank() ? cur.companyName() : "JASVI INDUSTRIES");

        String tier = (lblTier != null && !lblTier.getText().isBlank() && !"-".equals(lblTier.getText()))
                ? lblTier.getText().trim().toUpperCase(java.util.Locale.ROOT)
                : (cur != null && cur.tier() != null && !cur.tier().isBlank() ? cur.tier().toUpperCase(java.util.Locale.ROOT) : "PLATINUM");

        int days = (cur != null && cur.daysRemaining() > 365)
                ? (int) cur.daysRemaining()
                : 365;

        int maxUsers = "PLATINUM".equalsIgnoreCase(tier) ? 20 : ("GOLD".equalsIgnoreCase(tier) ? 5 : 1);

        Map<String, Object> req = Map.of(
                "companyName", company,
                "machineId", machineId,
                "machineHash", machineId,
                "days", days,
                "tier", tier,
                "maxUsers", maxUsers
        );

        if (!ApiSession.isEstablished()) {
            UiTaskExecutor.submitAction(
                    "check-lead-activation",
                    () -> api.checkLead(machineId),
                    leadStatus -> {
                        String payload = leadStatus != null ? String.valueOf(leadStatus.getOrDefault("licensePayload", "")) : "";
                        if (!payload.isBlank() && !"null".equalsIgnoreCase(payload)) {
                            try {
                                Object activationTarget;
                                if (payload.trim().startsWith("{")) {
                                    activationTarget = json.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                                } else {
                                    activationTarget = Map.of("licensePayload", payload);
                                }
                                api.activate(activationTarget);
                                refreshStatus();
                                AppDialogService.success(btnClose, "Computer Activated",
                                        "Approved license downloaded and activated successfully for Terminal " + machineId + "!");
                            } catch (Exception ex) {
                                AppDialogService.error(btnClose, "Activation Error", "Could not apply approved license", ex.getMessage());
                            }
                        } else {
                            String leadState = leadStatus != null ? String.valueOf(leadStatus.getOrDefault("status", "NONE")) : "NONE";
                            if ("PENDING".equalsIgnoreCase(leadState)) {
                                AppDialogService.info(btnClose, "Registration Pending",
                                        "Your registration request has been submitted and is awaiting Super Admin approval.");
                            } else {
                                AppDialogService.info(btnClose, "Activation Required",
                                        "No approved license found for this terminal. Please click 'Register Online' to submit an activation request.");
                            }
                        }
                    },
                    err -> AppDialogService.error(btnClose, "Check Status Error", "Could not check registration status", err.getMessage())
            );
            return;
        }

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
                        AppDialogService.success(btnClose, "Computer Activated",
                                "License activated and locked to this computer (" + machineId + ") successfully!");
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
                    AppDialogService.success(btnApplyActivation != null ? btnApplyActivation : txtMachineId, "License Activated", "License activated successfully!");
                    refreshStatus();
                },
                err -> AppDialogService.error(btnApplyActivation != null ? btnApplyActivation : txtMachineId, "Activation Failed", "Invalid License File", err.getMessage())
        );
    }

    public void setHostDialog(Dialog<?> hostDialog) {
        this.hostDialog = hostDialog;
    }

    @FXML
    private void handleClose() {
        if (hostDialog != null) {
            hostDialog.close();
            return;
        }
        if (btnApplyActivation != null && btnApplyActivation.getScene() != null && btnApplyActivation.getScene().getWindow() != null) {
            btnApplyActivation.getScene().getWindow().hide();
        }
    }

    public static void show(javafx.stage.Window owner) {
        show(owner, 0);
    }

    public static void show(javafx.stage.Window owner, int defaultTabIndex) {
        try {
            javafx.scene.Node ownerNode = owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null;
            var shell = DocumentInspectionScaffold.createShell(ownerNode,
                    "Subscription & Machine Activation",
                    "license-renewal-dialog");

            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(LicenseRenewalDialogController.class.getResource("/fxml/pages/LicenseRenewalDialog.fxml"));
            javafx.scene.Parent root = loader.load();
            LicenseRenewalDialogController controller = loader.getController();

            if (controller != null) {
                controller.setHostDialog(shell.dialog());
            }

            shell.host().getChildren().setAll(root);

            javafx.geometry.Rectangle2D screen = WindowUtilsFx.visualBoundsFor(owner);
            double maxW = Math.max(760, screen.getWidth() - 48);
            double maxH = Math.max(540, screen.getHeight() - 48);
            double targetW = Math.min(maxW, Math.max(880, Math.min(1040, screen.getWidth() * 0.82)));
            double targetH = Math.min(maxH, Math.max(620, Math.min(780, screen.getHeight() * 0.85)));

            shell.pane().setPrefSize(targetW, targetH);
            shell.pane().setMaxWidth(maxW);
            shell.pane().setMaxHeight(maxH);
            shell.pane().setMinWidth(Math.min(840, targetW));
            shell.pane().setMinHeight(Math.min(560, targetH));

            shell.dialog().setOnShown(ev -> {
                if (shell.pane().getScene() != null && shell.pane().getScene().getWindow() instanceof javafx.stage.Stage stage) {
                    WindowUtilsFx.fitDialogToOwnerScreen(stage, owner);
                }
            });

            shell.dialog().showAndWait();
        } catch (Exception e) {
            org.example.util.AppDialogService.error(owner, "Licensing", "Could not open Licensing", e.getMessage());
        }
    }
}
