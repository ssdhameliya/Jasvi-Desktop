package org.example.server.licensing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.itextpdf.barcodes.BarcodeQRCode;
import org.example.server.security.CurrentUser;
import org.example.shared.licensing.Ed25519CryptoUtil;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.shared.licensing.LicensePayload;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/license")
public class LicensingApiController {

    private final LicenseVerifierService verifier;
    private final ClientRegistryService registryService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public LicensingApiController(LicenseVerifierService verifier, ClientRegistryService registryService) {
        this.verifier = verifier;
        this.registryService = registryService;
    }

    @GetMapping("/status")
    public LicenseVerifierService.LicenseStatus getStatus() {
        return verifier.checkLicense();
    }

    @GetMapping("/catalog")
    public Map<String, Object> getCatalog() {
        Map<String, Object> settings = registryService.getCommercialSettings();
        List<Map<String, Object>> plans = registryService.getPlans();
        return Map.of(
                "supportPhone", settings.getOrDefault("supportPhone", "9537544008"),
                "upiVpa", settings.getOrDefault("upiVpa", "jasviindustries@icici"),
                "payeeName", settings.getOrDefault("payeeName", "Jasvi Industries"),
                "gstRate", settings.getOrDefault("gstRate", "18.00"),
                "whatsappTemplateLead", settings.getOrDefault("whatsappTemplateLead", ""),
                "whatsappTemplateIssued", settings.getOrDefault("whatsappTemplateIssued", ""),
                "plans", plans
        );
    }

    @PostMapping("/self-register")
    public ResponseEntity<?> selfRegister(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty registration request"));
        }
        Map<String, Object> lead = registryService.registerLead(body);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Lead submitted successfully. SuperAdmin will review and issue your license.",
                "leadId", lead.get("id"),
                "status", lead.get("status")
        ));
    }

    @GetMapping("/check-lead")
    public ResponseEntity<?> checkLead(@RequestParam String machineId) {
        Map<String, Object> status = registryService.checkLeadStatus(machineId);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty heartbeat request"));
        }
        String machineId = String.valueOf(body.getOrDefault("machineId", ""));
        String clientVersion = body.get("clientVersion") != null ? String.valueOf(body.get("clientVersion")) : null;
        Integer activeUsers = body.get("activeUsers") instanceof Number n ? n.intValue() : 1;
        String remoteUrl = body.get("remoteUrl") != null ? String.valueOf(body.get("remoteUrl")) : null;
        Map<String, Object> result = registryService.recordHeartbeat(machineId, clientVersion, activeUsers, remoteUrl);
        return ResponseEntity.ok(result);
    }

    public record ActivateRequest(
            String licenseKey,
            String companyName,
            String machineHash,
            String tier,
            Instant validFrom,
            Instant validUntil,
            Integer gracePeriodDays,
            Integer maxUsers,
            List<String> features,
            String signature
    ) {}

    @PostMapping("/activate")
    public ResponseEntity<?> activate(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty license activation payload"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (body.get("payload") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m
                : body;

        if (payloadMap == body && body.get("licensePayload") != null) {
            Object lp = body.get("licensePayload");
            if (lp instanceof Map<?, ?> m) {
                payloadMap = (Map<String, Object>) m;
            } else if (lp instanceof String s && s.trim().startsWith("{")) {
                try {
                    payloadMap = objectMapper.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception ignored) {}
            }
        }

        String signature = (String) body.get("signature");
        if (signature == null && body.get("header") instanceof Map<?, ?> h) {
            signature = (String) h.get("signature");
        }
        if (signature == null && payloadMap.get("signature") != null) {
            signature = String.valueOf(payloadMap.get("signature"));
        }

        String licenseKey = (String) payloadMap.get("licenseKey");
        if (licenseKey == null) licenseKey = (String) payloadMap.get("key");
        if (licenseKey == null && body.get("header") instanceof Map<?, ?> h) {
            licenseKey = (String) h.get("key");
        }
        if (licenseKey == null && body.get("licenseKey") != null) {
            licenseKey = String.valueOf(body.get("licenseKey"));
        }

        if (licenseKey == null || signature == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing license key or cryptographic signature"));
        }

        String companyName = payloadMap.get("companyName") != null ? String.valueOf(payloadMap.get("companyName")) : "Jasvi Client";
        String machineHash = null;
        if (payloadMap.get("machineHash") != null && !String.valueOf(payloadMap.get("machineHash")).isBlank()) {
            machineHash = String.valueOf(payloadMap.get("machineHash")).trim();
        } else if (payloadMap.get("machineId") != null && !String.valueOf(payloadMap.get("machineId")).isBlank()) {
            machineHash = String.valueOf(payloadMap.get("machineId")).trim();
        } else if (body.get("machineHash") != null && !String.valueOf(body.get("machineHash")).isBlank()) {
            machineHash = String.valueOf(body.get("machineHash")).trim();
        } else if (body.get("machineId") != null && !String.valueOf(body.get("machineId")).isBlank()) {
            machineHash = String.valueOf(body.get("machineId")).trim();
        } else {
            machineHash = HardwareFingerprintUtil.getMachineHash();
        }
        String tier = payloadMap.get("tier") != null ? String.valueOf(payloadMap.get("tier")) : "GOLD";
        Instant validFrom = parseInstant(payloadMap.get("validFrom"), Instant.now());
        Instant validUntil = parseInstant(payloadMap.get("validUntil"), Instant.now().plus(365, ChronoUnit.DAYS));
        int gracePeriodDays = payloadMap.get("gracePeriodDays") instanceof Number n ? n.intValue() : 14;
        int maxUsers = payloadMap.get("maxUsers") instanceof Number n ? n.intValue() : 5;
        @SuppressWarnings("unchecked")
        List<String> features = payloadMap.get("features") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP");

        LicensePayload payload = new LicensePayload(
                licenseKey, companyName, machineHash, tier, validFrom, validUntil,
                gracePeriodDays, maxUsers, features
        );

        try {
            boolean ok = verifier.activateLicense(payload, signature);
            return ResponseEntity.ok(Map.of("success", ok, "status", verifier.checkLicense()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    private static Instant parseInstant(Object val, Instant defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Instant i) return i;
        try {
            return Instant.parse(String.valueOf(val));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    @GetMapping("/upi-payload")
    public Map<String, Object> getUpiPayload(@RequestParam(defaultValue = "3500.00") String amount) {
        String machineId = HardwareFingerprintUtil.getDisplayMachineId();
        String vpa = "jasviindustries@icici";
        String name = "Jasvi Industries";
        String note = "License Renewal " + machineId;
        String upiUri = String.format("upi://pay?pa=%s&pn=%s&am=%s&cu=INR&tn=%s",
                vpa,
                name.replace(" ", "+"),
                amount,
                note.replace(" ", "+")
        );

        return Map.of(
                "upiUri", upiUri,
                "vpa", vpa,
                "payeeName", name,
                "amount", amount,
                "machineId", machineId,
                "note", note
        );
    }

    @GetMapping(value = "/upi-qr-image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getUpiQrImage(@RequestParam(defaultValue = "3500.00") String amount) {
        try {
            String machineId = HardwareFingerprintUtil.getDisplayMachineId();
            String upiUri = String.format("upi://pay?pa=jasviindustries@icici&pn=Jasvi+Industries&am=%s&cu=INR&tn=Renewal+%s",
                    amount,
                    machineId
            );

            BarcodeQRCode qrcode = new BarcodeQRCode(upiUri);
            java.awt.Image awtImg = qrcode.createAwtImage(Color.BLACK, Color.WHITE);

            int size = 280;
            BufferedImage bufferedImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = bufferedImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(awtImg, 0, 0, size, size, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"upi-qr.png\"")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(imageBytes);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public record SuperGenerateRequest(
            int days,
            String companyName,
            String tier,
            String privateKeyBase64
    ) {}

    @PostMapping("/super/generate-test")
    public ResponseEntity<?> superGenerateTest(@RequestBody SuperGenerateRequest req) {
        var user = CurrentUser.require();
        if (!"SUPER_ADMIN".equalsIgnoreCase(user.role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access restricted to Platform Super Admin"));
        }

        try {
            int days = req.days() == 0 ? 365 : req.days();
            String company = (req.companyName() == null || req.companyName().isBlank()) ? "JASVI INDUSTRIES" : req.companyName();
            String tier = (req.tier() == null || req.tier().isBlank()) ? "GOLD" : req.tier();

            Instant now = Instant.now();
            Instant validUntil = days < 0 ? now.minus(Math.abs(days), ChronoUnit.DAYS) : now.plus(days, ChronoUnit.DAYS);

            LicensePayload payload = new LicensePayload(
                    "JASVI-TEST-" + System.currentTimeMillis() % 100000,
                    company,
                    HardwareFingerprintUtil.getMachineHash(),
                    tier,
                    now.minus(1, ChronoUnit.DAYS),
                    validUntil,
                    14,
                    5,
                    List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP")
            );

            // If private key provided in request, sign it directly; else if in development, we load from key
            String privKeyB64 = (req.privateKeyBase64() != null && !req.privateKeyBase64().isBlank())
                    ? req.privateKeyBase64()
                    : "MC4CAQAwBQYDK2VwBCIEIOzrtL2IsC8IKub6TbLVTvXk4kw/FmOmeA0UUEnHFvCA";

            PrivateKey privateKey = Ed25519CryptoUtil.loadPrivateKey(privKeyB64);
            String sig = Ed25519CryptoUtil.signString(payload.toCanonicalString(), privateKey);

            verifier.activateLicense(payload, sig);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "payload", payload,
                    "signature", sig,
                    "status", verifier.checkLicense()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    public record GenerateClientLicenseRequest(
            @JsonProperty("companyName") String companyName,
            @JsonProperty("machineId") String machineId,
            @JsonProperty("machineHash") String machineHash,
            @JsonProperty("days") int days,
            @JsonProperty("tier") String tier,
            @JsonProperty("maxUsers") int maxUsers
    ) {
        public String effectiveMachineId() {
            if (machineId != null && !machineId.isBlank()) return machineId.trim();
            if (machineHash != null && !machineHash.isBlank()) return machineHash.trim();
            return null;
        }
    }

    @PostMapping("/super/issue")
    public ResponseEntity<?> issueClientLicense(@RequestBody GenerateClientLicenseRequest req) {
        var user = CurrentUser.require();
        if (!"SUPER_ADMIN".equalsIgnoreCase(user.role())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access restricted to Platform Super Admin"));
        }

        try {
            String company = (req.companyName() == null || req.companyName().isBlank()) ? "JASVI INDUSTRIES" : req.companyName().trim();
            String targetMachine = req.effectiveMachineId();
            String machine = (targetMachine == null || targetMachine.isBlank()) ? HardwareFingerprintUtil.getMachineHash() : targetMachine;
            int days = req.days() <= 0 ? 365 : req.days();
            String tier = (req.tier() == null || req.tier().isBlank()) ? "GOLD" : req.tier().trim().toUpperCase(java.util.Locale.ROOT);
            int users = req.maxUsers() <= 0 ? ("GOLD".equalsIgnoreCase(tier) ? 5 : 1) : req.maxUsers();

            Instant now = Instant.now();
            Instant validUntil = now.plus(days, ChronoUnit.DAYS);

            LicensePayload payload = new LicensePayload(
                    "JASVI-" + tier + "-" + (System.currentTimeMillis() % 1000000),
                    company,
                    machine,
                    tier,
                    now.minus(1, ChronoUnit.DAYS),
                    validUntil,
                    14,
                    users,
                    List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP")
            );

            String privKeyB64 = "MC4CAQAwBQYDK2VwBCIEIOzrtL2IsC8IKub6TbLVTvXk4kw/FmOmeA0UUEnHFvCA";
            PrivateKey privateKey = Ed25519CryptoUtil.loadPrivateKey(privKeyB64);
            String sig = Ed25519CryptoUtil.signString(payload.toCanonicalString(), privateKey);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "payload", payload,
                    "signature", sig,
                    "licenseJson", Map.of(
                            "licenseKey", payload.licenseKey(),
                            "companyName", payload.companyName(),
                            "machineHash", payload.machineHash(),
                            "tier", payload.tier(),
                            "validFrom", payload.validFrom().toString(),
                            "validUntil", payload.validUntil().toString(),
                            "gracePeriodDays", payload.gracePeriodDays(),
                            "maxUsers", payload.maxUsers(),
                            "features", payload.features(),
                            "signature", sig
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
