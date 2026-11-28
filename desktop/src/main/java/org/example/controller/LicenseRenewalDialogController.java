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
import org.example.util.BusinessClock;
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
    @FXML private ComboBox<PlanOption> cmbPlanChoice;
    @FXML private ImageView imgUpiQr;
    @FXML private Label lblAmountDisplay;
    @FXML private Label lblVpaDisplay;
    @FXML private Button btnCopyUpiUri;
    @FXML private TextField txtUtrReference;
    @FXML private Button btnSubmitPaymentProof;

    @FXML private TextField txtMachineId;
    @FXML private Button btnCopyMachineId;
    @FXML private Label lblCompany;
    @FXML private Label lblTier;
    @FXML private Label lblServerHost;
    @FXML private Label lblValidUntil;
    @FXML private Label lblDaysRemaining;

    @FXML private Button btnSelfActivateNow;
    @FXML private Button btnCloudRefresh;
    @FXML private Button btnSendEmailLead;
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
    private String supportPhone = "";
    private String supportEmail = "";
    private String upiVpa = "";
    private String payeeName = "";
    private String gstRate = "18.00";
    private String leadTemplate = "Hello Support Team, I am {CompanyName} ({ContactPerson}, {Phone}). Terminal ID: {MachineId}. Please activate our ERP license.";

    public record PlanOption(String planCode, String displayName, String tier, double priceInr, int validityDays) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s — ₹%.0f / %dd", displayName, priceInr, validityDays);
        }
    }

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
        if (btnSendEmailLead != null) UiActionIcons.apply(btnSendEmailLead, "email", "Send Machine ID via Email");
        if (btnSendWhatsAppLead != null) UiActionIcons.apply(btnSendWhatsAppLead, "communication", "Send via WhatsApp");
        if (btnRegisterOnlineLead != null) UiActionIcons.apply(btnRegisterOnlineLead, "link", "Register Online");
        if (btnCloudRefresh != null) UiActionIcons.apply(btnCloudRefresh, "refresh", "Sync Cloud License");
        if (btnSelfActivateNow != null) UiActionIcons.apply(btnSelfActivateNow, "security", "Activate Locally");
        if (btnBrowseLicenseFile != null) UiActionIcons.apply(btnBrowseLicenseFile, "folder", "Browse License File");
        if (btnCopyUpiUri != null) UiActionIcons.apply(btnCopyUpiUri, "copy", "Copy UPI Payment String");
        if (btnSubmitPaymentProof != null) UiActionIcons.apply(btnSubmitPaymentProof, "complete", "Submit UTR Payment Reference");
        if (btnCopyMachineId != null) UiActionIcons.apply(btnCopyMachineId, "copy", "Copy ID");
        if (cmbPlanChoice != null) {
            cmbPlanChoice.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    renderUpiQrCode(newVal);
                }
            });
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
                        if (cat.get("supportEmail") != null) supportEmail = String.valueOf(cat.get("supportEmail")).trim();
                        if (cat.get("upiVpa") != null) upiVpa = String.valueOf(cat.get("upiVpa")).trim();
                        if (cat.get("payeeName") != null) payeeName = String.valueOf(cat.get("payeeName")).trim();
                        if (cat.get("gstRate") != null) gstRate = String.valueOf(cat.get("gstRate")).trim();
                        if (cat.get("whatsappTemplateLead") != null && !String.valueOf(cat.get("whatsappTemplateLead")).isBlank()) {
                            leadTemplate = String.valueOf(cat.get("whatsappTemplateLead")).trim();
                        }
                    }
                    java.util.List<PlanOption> parsedPlans = parseCatalogPlans(cat != null ? cat.get("plans") : null);
                    if (cmbPlanChoice != null) {
                        cmbPlanChoice.setItems(javafx.collections.FXCollections.observableArrayList(parsedPlans));
                        if (!parsedPlans.isEmpty()) {
                            PlanOption matching = selectPreferredPlan(parsedPlans, currentStatusDto);
                            PlanOption previous = cmbPlanChoice.getValue();
                            cmbPlanChoice.setValue(matching);
                            if (java.util.Objects.equals(previous, matching)) {
                                renderUpiQrCode(matching);
                            }
                            return;
                        }
                    }
                    renderUpiQrCode(null);
                },
                err -> renderUpiQrCode(null)
        );
    }

    static java.util.List<PlanOption> parseCatalogPlans(Object plansObj) {
        java.util.List<PlanOption> parsedPlans = new java.util.ArrayList<>();
        if (!(plansObj instanceof java.util.List<?> rawPlans)) {
            return parsedPlans;
        }
        for (Object rowObj : rawPlans) {
            if (!(rowObj instanceof Map<?, ?> m)) {
                continue;
            }
            Object activeObj = m.get("is_active");
            if (activeObj instanceof Boolean b && !b) continue;
            if (activeObj instanceof Number n && n.intValue() == 0) continue;

            String tier = firstNonBlank(m.get("tier"), m.get("tier_code"), "GOLD");
            String code = firstNonBlank(m.get("plan_code"), tier, "PLAN");
            String name = firstNonBlank(m.get("display_name"), m.get("plan_name"), code);
            double price = parsePositiveNumber(m.get("price_inr"), parsePositiveNumber(m.get("annual_fee"), 0d));
            int days = (int) Math.round(parsePositiveNumber(m.get("validity_days"), 365d));
            if (days <= 0) days = 365;
            if (price > 0) {
                parsedPlans.add(new PlanOption(code, name, tier, price, days));
            }
        }
        return parsedPlans;
    }

    private static PlanOption selectPreferredPlan(java.util.List<PlanOption> plans, LicenseApiClient.LicenseStatusDto statusDto) {
        String activeTier = statusDto != null && statusDto.tier() != null ? statusDto.tier().trim() : "";
        return plans.stream()
                .filter(p -> p.tier().equalsIgnoreCase(activeTier))
                .findFirst()
                .orElse(plans.getFirst());
    }

    private static String firstNonBlank(Object first, Object second, String fallback) {
        for (Object candidate : new Object[]{first, second}) {
            if (candidate != null) {
                String s = String.valueOf(candidate).trim();
                if (!s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
            }
        }
        return fallback;
    }

    private static double parsePositiveNumber(Object raw, double fallback) {
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            return Double.isFinite(v) && v > 0 ? v : fallback;
        }
        if (raw != null) {
            try {
                double v = Double.parseDouble(String.valueOf(raw).trim());
                return Double.isFinite(v) && v > 0 ? v : fallback;
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private void renderUpiQrCode(PlanOption plan) {
        if (upiVpa == null || upiVpa.isBlank()) {
            currentUpiUri = "";
            if (imgUpiQr != null) imgUpiQr.setImage(null);
            if (lblAmountDisplay != null) {
                lblAmountDisplay.setText(plan != null
                        ? String.format(java.util.Locale.ROOT, "₹%.0f + %s%% GST (%s)", plan.priceInr(), gstRate, plan.displayName())
                        : "Select a commercial plan or contact administrator");
            }
            if (lblVpaDisplay != null) lblVpaDisplay.setText("UPI VPA not configured on server");
            if (btnCopyUpiUri != null) btnCopyUpiUri.setDisable(true);
            return;
        }
        double basePrice = plan != null ? plan.priceInr() : 0d;
        if (basePrice <= 0d) {
            currentUpiUri = "";
            if (imgUpiQr != null) imgUpiQr.setImage(null);
            if (lblAmountDisplay != null) lblAmountDisplay.setText("No billable plan selected");
            if (lblVpaDisplay != null) lblVpaDisplay.setText("VPA: " + upiVpa);
            if (btnCopyUpiUri != null) btnCopyUpiUri.setDisable(true);
            return;
        }
        if (btnCopyUpiUri != null) btnCopyUpiUri.setDisable(false);
        double gstPercent = 18.00;
        try { gstPercent = Double.parseDouble(gstRate); } catch (Exception ignored) {}
        double totalWithGst = basePrice * (1.0 + (gstPercent / 100.0));

        String machineId = HardwareFingerprintUtil.getDisplayMachineId();
        String safePayee = (payeeName == null || payeeName.isBlank()) ? "ERP Subscription" : payeeName;
        String encPayee = safePayee.replace(" ", "+");
        currentUpiUri = String.format(java.util.Locale.ROOT, "upi://pay?pa=%s&pn=%s&am=%.2f&cu=INR&tn=Renewal+%s",
                upiVpa, encPayee, totalWithGst, machineId);

        final double finalBase = basePrice;
        final double finalTotal = totalWithGst;
        final String periodSuffix = plan != null && plan.validityDays() != 365 ? (" / " + plan.validityDays() + "d") : " / Year";

        UiTaskExecutor.submitLatest(
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
                        lblAmountDisplay.setText(String.format(java.util.Locale.ROOT, "₹%.0f + %s%% GST = ₹%.0f%s", finalBase, gstRate, finalTotal, periodSuffix));
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

        if (cmbPlanChoice != null && cmbPlanChoice.getItems() != null && !cmbPlanChoice.getItems().isEmpty()
                && dto.tier() != null && !dto.tier().isBlank()) {
            PlanOption current = cmbPlanChoice.getValue();
            if (current == null || !current.tier().equalsIgnoreCase(dto.tier().trim())) {
                PlanOption preferred = selectPreferredPlan(cmbPlanChoice.getItems(), dto);
                cmbPlanChoice.setValue(preferred);
            }
        }

        if (lblCompany != null) lblCompany.setText(dto.companyName() != null ? dto.companyName() : "-");
        if (lblTier != null) lblTier.setText(dto.tier() != null ? dto.tier() : "GOLD");
        if (lblServerHost != null) {
            lblServerHost.setText(resolveServerHost());
        }
        if (lblValidUntil != null) {
            String formattedDate = "-";
            if (dto.validUntil() != null) {
                try {
                    java.time.LocalDate date = dto.validUntil().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    formattedDate = BusinessClock.formatDate(date);
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

    private String resolveServerHost() {
        try {
            String url = org.example.config.ConfigManager.getDataApiBaseUrl();
            if (url != null && !url.isBlank()) {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                int port = uri.getPort();
                if (host != null && !host.isBlank()) {
                    if (host.contains("jasviindustries.in")) {
                        return host.contains("uat") ? host + " (Oracle Cloud UAT)" : host + " (Oracle Cloud)";
                    }
                    if (port > 0 && port != 80 && port != 443) {
                        return host + ":" + port;
                    }
                    return host;
                }
            }
        } catch (Exception ignored) {}
        return "Oracle Cloud Host";
    }

    @FXML
    private void handleSendEmailLead() {
        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim() : "Our Company";
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();
        String host = resolveServerHost();

        String subject = "Jasvi ERP License Activation Request — Terminal [" + machineId + "]";
        String body = "Hello Jasvi Support Team,\n\n"
                + "Please activate our workstation license for " + company + ".\n\n"
                + "Company Name: " + company + "\n"
                + "Machine Hardware ID: " + machineId + "\n"
                + "Connected Host: " + host + "\n\n"
                + "Thank you,\n"
                + company + " Team";

        String targetEmail = supportEmail != null ? supportEmail.trim() : "";
        if (targetEmail.isBlank()) {
            AppDialogService.warning(btnSendEmailLead != null ? btnSendEmailLead : txtMachineId,
                    "Support Email Unavailable",
                    "Support email is not configured on the cloud catalog yet. Please use Register Online or contact your administrator.");
            return;
        }

        try {
            String mailto = String.format("mailto:%s?subject=%s&body=%s",
                    java.net.URLEncoder.encode(targetEmail, StandardCharsets.UTF_8).replace("+", "%20"),
                    java.net.URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20"),
                    java.net.URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20")
            );
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MAIL)) {
                java.awt.Desktop.getDesktop().mail(new java.net.URI(mailto));
            } else if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(mailto));
            } else {
                throw new UnsupportedOperationException("Desktop mail and browse actions are unsupported.");
            }
        } catch (Exception ex) {
            ClipboardContent content = new ClipboardContent();
            content.putString("To: " + targetEmail + "\nSubject: " + subject + "\n\n" + body);
            Clipboard.getSystemClipboard().setContent(content);
            AppDialogService.info(btnSendEmailLead != null ? btnSendEmailLead : txtMachineId,
                    "Email Draft Copied",
                    "License request copied to clipboard!\n\nPlease email this request to: " + targetEmail);
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

    @FXML
    private void handleSubmitPaymentProof() {
        String utr = txtUtrReference == null || txtUtrReference.getText() == null ? "" : txtUtrReference.getText().trim();
        if (utr.length() < 6) {
            AppDialogService.warning(btnSubmitPaymentProof != null ? btnSubmitPaymentProof : btnClose,
                    "Invalid UTR Reference",
                    "Please enter a valid 6+ character UPI UTR / transaction reference number.");
            return;
        }
        PlanOption selectedPlan = cmbPlanChoice != null ? cmbPlanChoice.getValue() : null;
        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim()
                : (currentStatusDto != null && currentStatusDto.companyName() != null && !currentStatusDto.companyName().isBlank()
                        ? currentStatusDto.companyName().trim() : "Registered Client");
        String tier = selectedPlan != null ? selectedPlan.tier()
                : (lblTier != null && !lblTier.getText().isBlank() && !"-".equals(lblTier.getText()) ? lblTier.getText().trim() : "GOLD");
        int days = selectedPlan != null ? selectedPlan.validityDays() : 365;
        double gstPct = 18.0;
        try { gstPct = Double.parseDouble(gstRate); } catch (Exception ignored) {}
        double paidAmount = selectedPlan != null ? selectedPlan.priceInr() * (1.0 + gstPct / 100.0) : 0.0;

        Map<String, Object> req = new java.util.LinkedHashMap<>();
        req.put("companyName", company);
        req.put("contactPerson", "Self-Service UTR Renewal");
        req.put("phone", "N/A");
        req.put("email", "");
        req.put("machineId", HardwareFingerprintUtil.getDisplayMachineId());
        req.put("requestedTier", tier);
        req.put("utrReference", utr);
        req.put("paymentAmount", paidAmount);
        req.put("requestedDays", days);
        req.put("notes", "In-app UPI payment submission | Plan: " + (selectedPlan != null ? selectedPlan.planCode() : tier) + " | UTR: " + utr);

        UiTaskExecutor.submitActionWithOverlay(
                btnSubmitPaymentProof != null ? btnSubmitPaymentProof : btnClose,
                "Submitting UTR Payment Proof...",
                "submit-utr-proof",
                () -> api.selfRegister(req),
                res -> {
                    if (txtUtrReference != null) txtUtrReference.clear();
                    AppDialogService.success(btnClose, "UTR Submitted",
                            "Payment reference " + utr + " recorded! Your renewal request has been logged for cloud verification.");
                },
                err -> AppDialogService.error(btnClose, "UTR Submission Failed", "Could not submit payment reference", err.getMessage())
        );
    }

    @FXML
    private void handleCloudRefresh() {
        UiTaskExecutor.submitActionWithOverlay(
                btnCloudRefresh != null ? btnCloudRefresh : btnClose,
                "Syncing Latest License from Cloud...",
                "cloud-refresh-license",
                api::cloudRefresh,
                res -> {
                    boolean refreshed = res != null && Boolean.TRUE.equals(res.get("refreshed"));
                    refreshStatus();
                    if (refreshed) {
                        AppDialogService.success(btnClose, "Cloud License Synced",
                                String.valueOf(res.getOrDefault("message", "Latest cloud license pulled and activated on this workstation.")));
                    } else {
                        AppDialogService.info(btnClose, "Cloud Sync Checked",
                                res != null ? String.valueOf(res.getOrDefault("message", "No newer license found on cloud registry.")) : "No updates available.");
                    }
                },
                err -> AppDialogService.error(btnClose, "Cloud Sync Failed", "Could not pull latest license from cloud", err.getMessage())
        );
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
                    : (cur.validUntil() != null ? BusinessClock.formatDate(cur.validUntil().atZone(java.time.ZoneId.systemDefault()).toLocalDate()) : "N/A");
            AppDialogService.info(btnClose, "Terminal Already Activated",
                    "This computer is already activated with an active " + cur.tier() +
                    " subscription valid until " + expDate + " (" + cur.daysRemaining() + " days remaining).\n\nNo further activation is required.");
            return;
        }

        String company = (lblCompany != null && !lblCompany.getText().isBlank() && !"-".equals(lblCompany.getText()))
                ? lblCompany.getText().trim()
                : (cur != null && cur.companyName() != null && !cur.companyName().isBlank() ? cur.companyName() : "Registered Company");

        PlanOption selectedPlan = cmbPlanChoice != null ? cmbPlanChoice.getValue() : null;
        String tier = selectedPlan != null ? selectedPlan.tier()
                : ((lblTier != null && !lblTier.getText().isBlank() && !"-".equals(lblTier.getText()))
                        ? lblTier.getText().trim().toUpperCase(java.util.Locale.ROOT)
                        : (cur != null && cur.tier() != null && !cur.tier().isBlank() ? cur.tier().toUpperCase(java.util.Locale.ROOT) : "GOLD"));

        int days = selectedPlan != null ? selectedPlan.validityDays()
                : ((cur != null && cur.daysRemaining() > 365)
                        ? (int) cur.daysRemaining()
                        : 365);

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

        UiTaskExecutor.submitActionWithOverlay(
                btnApplyActivation != null ? btnApplyActivation : btnClose,
                "Cryptographically Validating & Activating License...",
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
            shell.pane().getStyleClass().addAll("dse-dialog-no-button-bar", "dse-dialog-auto-height");

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
            double targetW = Math.min(maxW, Math.max(920, Math.min(1080, screen.getWidth() * 0.86)));
            double targetH = Math.min(maxH, Math.max(660, Math.min(820, screen.getHeight() * 0.88)));

            shell.pane().setPrefSize(targetW, targetH);
            shell.pane().setMaxWidth(maxW);
            shell.pane().setMaxHeight(maxH);
            shell.pane().setMinWidth(Math.min(860, targetW));
            shell.pane().setMinHeight(Math.min(580, targetH));

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
