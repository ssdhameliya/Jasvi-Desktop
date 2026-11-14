package org.example.server.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.shared.licensing.Ed25519CryptoUtil;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.shared.licensing.LicensePayload;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.example.server.auth.SmtpMailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.*;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.*;

@Service
public class ClientRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistryService.class);
    private static final String DEFAULT_PRIV_KEY = "MC4CAQAwBQYDK2VwBCIEIOzrtL2IsC8IKub6TbLVTvXk4kw/FmOmeA0UUEnHFvCA";

    private final JpaNativeRepository jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BusinessWhatsappService whatsappService;
    private final SmtpMailService mailService;

    public ClientRegistryService(JpaNativeRepository jdbc,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) BusinessWhatsappService whatsappService,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) SmtpMailService mailService) {
        this.jdbc = jdbc;
        this.whatsappService = whatsappService;
        this.mailService = mailService;
        initDefaultClientsIfEmpty();
    }

    private void initDefaultClientsIfEmpty() {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM licensed_client_registry", Integer.class);
            if (count == null || count == 0) {
                // Seed anchor client
                registerClient(Map.of(
                        "companyName", "JASVI INDUSTRIES",
                        "machineId", HardwareFingerprintUtil.getDisplayMachineId(),
                        "contactPerson", "Bipin Dhameliya",
                        "phone", "9597544008",
                        "email", "jasviindustries@gmail.com",
                        "tier", "GOLD",
                        "maxUsers", 5,
                        "annualFee", 3500.00,
                        "notes", "Anchor Headquarter Production Machine",
                        "days", 365
                ));
            }
        } catch (Exception e) {
            log.warn("Could not seed default client in licensed_client_registry: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getClients(String query, String status) {
        StringBuilder sql = new StringBuilder("SELECT id, company_name, machine_id, contact_person, phone, email, " +
                "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version, " +
                "remote_url, last_heartbeat_at, client_version, active_users_count, features, " +
                "COALESCE(is_purchased, true) AS is_purchased, last_alert_sent_at, kicked_at, kick_reason " +
                "FROM licensed_client_registry WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append("AND status = ? ");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }

        if (query != null && !query.isBlank()) {
            String q = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append("AND (LOWER(company_name) LIKE ? OR LOWER(machine_id) LIKE ? OR LOWER(contact_person) LIKE ? OR LOWER(phone) LIKE ?) ");
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }

        sql.append("ORDER BY id DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getClientById(Long id) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id, company_name, machine_id, contact_person, phone, email, " +
                        "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version, " +
                        "remote_url, last_heartbeat_at, client_version, active_users_count, features, " +
                        "COALESCE(is_purchased, true) AS is_purchased, last_alert_sent_at, kicked_at, kick_reason " +
                        "FROM licensed_client_registry WHERE id = ?", id
        );
        if (list.isEmpty()) {
            throw new NoSuchElementException("Client not found in registry with ID: " + id);
        }
        return list.getFirst();
    }

    @Transactional
    public Map<String, Object> registerClient(Map<String, Object> req) {
        String companyName = String.valueOf(req.getOrDefault("companyName", "New Client")).trim();
        String rawMachine = String.valueOf(req.getOrDefault("machineId", HardwareFingerprintUtil.getDisplayMachineId())).trim();
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        String contactPerson = req.get("contactPerson") != null ? String.valueOf(req.get("contactPerson")).trim() : "";
        String phone = req.get("phone") != null ? String.valueOf(req.get("phone")).trim() : "";
        String email = req.get("email") != null ? String.valueOf(req.get("email")).trim() : "";
        String tier = req.get("tier") != null ? String.valueOf(req.get("tier")).trim().toUpperCase(Locale.ROOT) : "GOLD";
        int maxUsers = req.get("maxUsers") instanceof Number n ? n.intValue() : 5;
        BigDecimal annualFee = req.get("annualFee") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal("3500.00");
        String notes = req.get("notes") != null ? String.valueOf(req.get("notes")) : "";
        String remoteUrl = req.get("remoteUrl") != null ? String.valueOf(req.get("remoteUrl")).trim() : null;
        if (remoteUrl != null && remoteUrl.isBlank()) remoteUrl = null;
        int days = req.get("days") instanceof Number n ? n.intValue() : 365;
        if (days <= 0) days = 365;

        Instant now = Instant.now();
        Instant validUntil = now.plus(days, ChronoUnit.DAYS);

        List<String> features = extractFeatures(req, tier);
        String featuresStr = String.join(",", features);

        // Generate signed license payload
        String licenseKey = "JASVI-" + tier + "-" + (System.currentTimeMillis() % 1000000);
        LicensePayload payload = new LicensePayload(
                licenseKey,
                companyName,
                machineId,
                tier,
                now.minus(1, ChronoUnit.DAYS),
                validUntil,
                14,
                maxUsers,
                features
        );

        String signature = signPayload(payload);
        String payloadJson;
        try {
            Map<String, Object> tokenMap = Map.of(
                    "licenseKey", payload.licenseKey(),
                    "companyName", payload.companyName(),
                    "machineHash", payload.machineHash(),
                    "tier", payload.tier(),
                    "validFrom", payload.validFrom().toString(),
                    "validUntil", payload.validUntil().toString(),
                    "gracePeriodDays", payload.gracePeriodDays(),
                    "maxUsers", payload.maxUsers(),
                    "features", payload.features(),
                    "signature", signature
            );
            payloadJson = objectMapper.writeValueAsString(tokenMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize license payload JSON", e);
        }

        jdbc.update(
                "INSERT INTO licensed_client_registry (company_name, machine_id, contact_person, phone, email, " +
                        "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version, remote_url, features) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 'ACTIVE', ?, ?, 1, ?, ?) " +
                        "ON CONFLICT (machine_id) DO UPDATE SET " +
                        "company_name = EXCLUDED.company_name, " +
                        "contact_person = EXCLUDED.contact_person, " +
                        "phone = EXCLUDED.phone, " +
                        "email = EXCLUDED.email, " +
                        "tier = EXCLUDED.tier, " +
                        "max_users = EXCLUDED.max_users, " +
                        "annual_fee = EXCLUDED.annual_fee, " +
                        "valid_until = EXCLUDED.valid_until, " +
                        "status = 'ACTIVE', " +
                        "license_payload_enc = EXCLUDED.license_payload_enc, " +
                        "notes = EXCLUDED.notes, " +
                        "remote_url = COALESCE(EXCLUDED.remote_url, licensed_client_registry.remote_url), " +
                        "features = EXCLUDED.features, " +
                        "row_version = licensed_client_registry.row_version + 1",
                companyName,
                machineId,
                contactPerson,
                phone,
                email,
                tier,
                maxUsers,
                annualFee,
                java.util.Date.from(validUntil),
                payloadJson,
                notes,
                remoteUrl,
                featuresStr
        );

        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            if (machineId.equalsIgnoreCase(hostClean) || "JASVI INDUSTRIES".equalsIgnoreCase(companyName)) {
                jdbc.update(
                        "UPDATE app_license SET tier = ?, max_users = ?, valid_until = ?, features = ?, signature = ?, canonical_payload = ?, " +
                                "last_verified_at = CURRENT_TIMESTAMP, status = 'ACTIVE' WHERE UPPER(TRIM(company_name)) = ?",
                        tier, maxUsers, java.util.Date.from(validUntil), featuresStr, signature, payload.toCanonicalString(),
                        companyName.toUpperCase(Locale.ROOT)
                );
            }
        } catch (Exception ignored) {}

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM licensed_client_registry WHERE machine_id = ?", machineId
        );
        return rows.getFirst();
    }

    @Transactional
    public Map<String, Object> renewClient(Long id, int additionalDays) {
        Map<String, Object> client = getClientById(id);
        int days = additionalDays <= 0 ? 365 : additionalDays;

        Instant currentExpiry = toInstant(client.get("valid_until"));

        Instant now = Instant.now();
        Instant base = (currentExpiry != null && currentExpiry.isAfter(now)) ? currentExpiry : now;
        Instant newExpiry = base.plus(days, ChronoUnit.DAYS);

        String companyName = String.valueOf(client.get("company_name"));
        String machineId = String.valueOf(client.get("machine_id"));
        String tier = String.valueOf(client.get("tier"));
        int maxUsers = client.get("max_users") instanceof Number n ? n.intValue() : 5;

        String licenseKey = "JASVI-" + tier + "-" + (System.currentTimeMillis() % 1000000);
        List<String> features = extractFeatures(client, tier);
        String featuresStr = String.join(",", features);
        LicensePayload payload = new LicensePayload(
                licenseKey,
                companyName,
                machineId,
                tier,
                now.minus(1, ChronoUnit.DAYS),
                newExpiry,
                14,
                maxUsers,
                features
        );

        String signature = signPayload(payload);
        String payloadJson;
        try {
            Map<String, Object> tokenMap = Map.of(
                    "licenseKey", payload.licenseKey(),
                    "companyName", payload.companyName(),
                    "machineHash", payload.machineHash(),
                    "tier", payload.tier(),
                    "validFrom", payload.validFrom().toString(),
                    "validUntil", payload.validUntil().toString(),
                    "gracePeriodDays", payload.gracePeriodDays(),
                    "maxUsers", payload.maxUsers(),
                    "features", payload.features(),
                    "signature", signature
            );
            payloadJson = objectMapper.writeValueAsString(tokenMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize license payload JSON", e);
        }

        jdbc.update(
                "UPDATE licensed_client_registry SET " +
                        "valid_until = ?, " +
                        "issued_at = CURRENT_TIMESTAMP, " +
                        "status = 'ACTIVE', " +
                        "license_payload_enc = ?, " +
                        "features = ?, " +
                        "row_version = row_version + 1 " +
                        "WHERE id = ?",
                java.util.Date.from(newExpiry),
                payloadJson,
                featuresStr,
                id
        );

        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            if (machineId.equalsIgnoreCase(hostClean) || "JASVI INDUSTRIES".equalsIgnoreCase(companyName)) {
                jdbc.update(
                        "UPDATE app_license SET tier = ?, max_users = ?, valid_until = ?, features = ?, signature = ?, canonical_payload = ?, " +
                                "last_verified_at = CURRENT_TIMESTAMP, status = 'ACTIVE' WHERE UPPER(TRIM(company_name)) = ?",
                        tier, maxUsers, java.util.Date.from(newExpiry), featuresStr, signature, payload.toCanonicalString(),
                        companyName.toUpperCase(Locale.ROOT)
                );
            }
        } catch (Exception ignored) {
            log.warn("Could not sync app_license on renewal for host: {}", ignored.getMessage());
        }

        return getClientById(id);
    }

    private List<String> extractFeatures(Map<String, Object> req, String tier) {
        if (req != null) {
            Object featObj = req.get("features");
            if (featObj instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        result.add(String.valueOf(item).trim().toUpperCase(Locale.ROOT));
                    }
                }
                if (!result.isEmpty()) return Collections.unmodifiableList(result);
            } else if (featObj instanceof String str && !str.isBlank()) {
                String[] parts = str.split("[,;\\s]+");
                List<String> result = new ArrayList<>();
                for (String p : parts) {
                    String c = p.replace("\"", "").replace("'", "").trim().toUpperCase(Locale.ROOT);
                    if (!c.isEmpty()) result.add(c);
                }
                if (!result.isEmpty()) return Collections.unmodifiableList(result);
            }
        }
        return resolveFeaturesForTier(tier);
    }

    public List<String> resolveFeaturesForTier(String tier) {
        String t = tier == null ? "GOLD" : tier.trim().toUpperCase(Locale.ROOT);
        try {
            List<String> dbFeatures = jdbc.query(
                    "SELECT features FROM licensing_plan WHERE UPPER(TRIM(tier_code)) = ? AND is_active = 1 LIMIT 1",
                    (rs, rowNum) -> rs.getString("features"),
                    t
            );
            if (!dbFeatures.isEmpty() && dbFeatures.getFirst() != null && !dbFeatures.getFirst().isBlank()) {
                List<String> parsed = LicenseVerifierService.parseFeatures(dbFeatures.getFirst());
                if (!parsed.isEmpty()) return List.copyOf(org.example.shared.PermissionCatalog.expandFeatureTokens(parsed));
            }
        } catch (Exception ignored) {}
        return defaultFeaturesForTier(t);
    }

    public static List<String> defaultFeaturesForTier(String tier) {
        List<String> all = new ArrayList<>();
        for (var m : org.example.shared.PermissionCatalog.subscriptionModules()) {
            all.add(m.key());
        }
        return List.copyOf(org.example.shared.PermissionCatalog.expandFeatureTokens(all));
    }

    static Instant toInstant(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Instant i) return i;
        if (obj instanceof java.util.Date d) return d.toInstant();
        if (obj instanceof ZonedDateTime zdt) return zdt.toInstant();
        if (obj instanceof OffsetDateTime odt) return odt.toInstant();
        if (obj instanceof LocalDateTime ldt) return ldt.atZone(ZoneId.systemDefault()).toInstant();
        if (obj instanceof LocalDate ld) return ld.atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (obj instanceof Number num) return Instant.ofEpochMilli(num.longValue());
        if (obj instanceof String str) {
            String s = str.trim();
            if (s.isEmpty()) return null;
            try { return Instant.parse(s); } catch (Exception ignored) {}
            try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
            try { return LocalDateTime.parse(s.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
            try { return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay(ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        }
        if (obj instanceof TemporalAccessor ta) {
            if (ta.isSupported(ChronoField.INSTANT_SECONDS)) {
                return Instant.from(ta);
            }
            if (ta.isSupported(ChronoField.EPOCH_DAY)) {
                return LocalDate.from(ta).atStartOfDay(ZoneId.systemDefault()).toInstant();
            }
        }
        return null;
    }

    @Transactional
    public boolean revokeClient(Long id) {
        int updated = jdbc.update(
                "UPDATE licensed_client_registry SET status = 'REVOKED', row_version = row_version + 1 WHERE id = ?",
                id
        );
        return updated > 0;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStats() {
        Integer totalClients = jdbc.queryForObject("SELECT COUNT(*) FROM licensed_client_registry", Integer.class);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM licensed_client_registry WHERE status = 'ACTIVE' AND valid_until > CURRENT_TIMESTAMP",
                Integer.class
        );
        Integer expiring = jdbc.queryForObject(
                "SELECT COUNT(*) FROM licensed_client_registry WHERE status = 'ACTIVE' AND valid_until BETWEEN CURRENT_TIMESTAMP AND (CURRENT_TIMESTAMP + INTERVAL '30 days')",
                Integer.class
        );
        BigDecimal arr = jdbc.queryForObject(
                "SELECT COALESCE(SUM(annual_fee), 0) FROM licensed_client_registry WHERE status = 'ACTIVE'",
                BigDecimal.class
        );

        return Map.of(
                "totalClients", totalClients != null ? totalClients : 0,
                "activeClients", active != null ? active : 0,
                "expiringClients", expiring != null ? expiring : 0,
                "arr", arr != null ? arr : BigDecimal.ZERO
        );
    }

    private String signPayload(LicensePayload payload) {
        try {
            String envKey = System.getenv("JASVI_LICENSE_PRIVATE_KEY");
            String keyBase64 = (envKey != null && !envKey.isBlank()) ? envKey.trim() : DEFAULT_PRIV_KEY;
            PrivateKey privateKey = Ed25519CryptoUtil.loadPrivateKey(keyBase64);
            return Ed25519CryptoUtil.signString(payload.toCanonicalString(), privateKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign license with master private key", e);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCommercialSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportPhone", getSetting("licensing.support_phone", "9537544008"));
        result.put("upiVpa", getSetting("licensing.upi_vpa", "jasviindustries@icici"));
        result.put("payeeName", getSetting("licensing.payee_name", "Jasvi Industries"));
        result.put("gstRate", getSetting("licensing.gst_rate", "18.00"));
        result.put("whatsappTemplateLead", getSetting("licensing.whatsapp_template_lead",
                "Hello Jasvi Team, I am {CompanyName} ({ContactPerson}, {Phone}). Terminal ID: {MachineId}. Please activate our Jasvi ERP license."));
        result.put("whatsappTemplateIssued", getSetting("licensing.whatsapp_template_issued",
                "Dear {CompanyName}, your Jasvi ERP {Tier} license is approved! Terminal ID: {MachineId}. Valid Until: {ValidUntil}. Thank you for choosing Jasvi ERP."));
        return result;
    }

    @Transactional
    public void saveCommercialSettings(Map<String, Object> settings) {
        if (settings == null) return;
        saveSetting("licensing.support_phone", settings.get("supportPhone"));
        saveSetting("licensing.upi_vpa", settings.get("upiVpa"));
        saveSetting("licensing.payee_name", settings.get("payeeName"));
        saveSetting("licensing.gst_rate", settings.get("gstRate"));
        saveSetting("licensing.whatsapp_template_lead", settings.get("whatsappTemplateLead"));
        saveSetting("licensing.whatsapp_template_issued", settings.get("whatsappTemplateIssued"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlans() {
        return jdbc.queryForList(
                "SELECT id, tier_code, plan_name, max_users, annual_fee, validity_days, features, display_order, is_active " +
                        "FROM licensing_plan ORDER BY display_order ASC, id ASC"
        );
    }

    @Transactional
    public Map<String, Object> savePlan(Map<String, Object> req) {
        if (req == null) throw new IllegalArgumentException("Plan details required");
        String tierCode = String.valueOf(req.getOrDefault("tierCode", "CUSTOM")).trim().toUpperCase(Locale.ROOT);
        String planName = String.valueOf(req.getOrDefault("planName", tierCode)).trim();
        int maxUsers = req.get("maxUsers") instanceof Number n ? n.intValue() : 5;
        BigDecimal fee = req.get("annualFee") instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : new BigDecimal("3500.00");
        int days = req.get("validityDays") instanceof Number n ? n.intValue() : 365;
        Object featObj = req.get("features");
        String features;
        if (featObj instanceof Collection<?> col) {
            List<String> list = new ArrayList<>();
            for (Object item : col) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    list.add(String.valueOf(item).trim().toUpperCase(Locale.ROOT));
                }
            }
            features = String.join(",", list);
        } else if (featObj != null) {
            features = String.valueOf(featObj).trim().toUpperCase(Locale.ROOT);
        } else {
            features = String.join(",", defaultFeaturesForTier(tierCode));
        }
        int order = req.get("displayOrder") instanceof Number n ? n.intValue() : 1;
        int active = Boolean.FALSE.equals(req.get("isActive")) || Integer.valueOf(0).equals(req.get("isActive")) ? 0 : 1;

        jdbc.update("""
                INSERT INTO licensing_plan (tier_code, plan_name, max_users, annual_fee, validity_days, features, display_order, is_active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tier_code) DO UPDATE SET
                    plan_name = EXCLUDED.plan_name,
                    max_users = EXCLUDED.max_users,
                    annual_fee = EXCLUDED.annual_fee,
                    validity_days = EXCLUDED.validity_days,
                    features = EXCLUDED.features,
                    display_order = EXCLUDED.display_order,
                    is_active = EXCLUDED.is_active,
                    updated_at = CURRENT_TIMESTAMP
                """, tierCode, planName, maxUsers, fee, days, features, order, active);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM licensing_plan WHERE tier_code = ?", tierCode);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    @Transactional
    public Map<String, Object> deletePlan(Long id) {
        if (id == null) throw new IllegalArgumentException("Plan ID is required");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM licensing_plan WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Subscription plan not found");
        }
        Map<String, Object> plan = rows.getFirst();
        String tierCode = String.valueOf(plan.getOrDefault("tier_code", "")).trim().toUpperCase(Locale.ROOT);
        Integer activeClients = jdbc.queryForObject(
                "SELECT COUNT(*) FROM licensed_client_registry WHERE UPPER(tier) = ? AND UPPER(status) = 'ACTIVE'",
                Integer.class,
                tierCode
        );
        if (activeClients != null && activeClients > 0) {
            throw new IllegalStateException("Cannot delete plan '" + tierCode + "' while " + activeClients + " active client(s) are assigned to it.");
        }
        jdbc.update("DELETE FROM licensing_plan WHERE id = ?", id);
        return Map.of("deleted", true, "id", id, "tierCode", tierCode);
    }

    @Transactional
    public Map<String, Object> registerLead(Map<String, Object> req) {
        if (req == null) throw new IllegalArgumentException("Registration lead required");
        String company = String.valueOf(req.getOrDefault("companyName", "New Client")).trim();
        String rawMachine = String.valueOf(req.getOrDefault("machineId", "")).trim();
        if (rawMachine.isBlank()) rawMachine = HardwareFingerprintUtil.getDisplayMachineId();
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        String contact = req.get("contactPerson") != null ? String.valueOf(req.get("contactPerson")).trim() : "";
        String phone = req.get("phone") != null ? String.valueOf(req.get("phone")).trim() : "";
        String email = req.get("email") != null ? String.valueOf(req.get("email")).trim() : "";
        String tier = req.get("requestedTier") != null ? String.valueOf(req.get("requestedTier")).trim().toUpperCase(Locale.ROOT) : "GOLD";
        String notes = req.get("notes") != null ? String.valueOf(req.get("notes")).trim() : "Pre-login desktop auto-registration";
        String remoteUrl = req.get("remoteUrl") != null ? String.valueOf(req.get("remoteUrl")).trim() : null;
        if (remoteUrl != null && remoteUrl.isBlank()) remoteUrl = null;

        List<Map<String, Object>> existingPending = jdbc.queryForList(
                "SELECT id FROM licensing_registration_lead WHERE machine_id = ? AND UPPER(status) = 'PENDING' ORDER BY id DESC LIMIT 1",
                machineId);
        if (!existingPending.isEmpty()) {
            Long existingId = ((Number) existingPending.getFirst().get("id")).longValue();
            jdbc.update("""
                    UPDATE licensing_registration_lead SET
                        company_name = ?,
                        contact_person = ?,
                        phone = ?,
                        email = ?,
                        requested_tier = ?,
                        notes = ?,
                        updated_at = CURRENT_TIMESTAMP,
                        remote_url = COALESCE(?, remote_url)
                    WHERE id = ?
                    """, company, contact, phone, email, tier, notes, remoteUrl, existingId);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM licensing_registration_lead WHERE id = ?", existingId);
            return rows.isEmpty() ? Map.of() : rows.getFirst();
        }

        jdbc.update("""
                INSERT INTO licensing_registration_lead (company_name, machine_id, contact_person, phone, email, requested_tier, status, notes, updated_at, remote_url)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP, ?)
                """, company, machineId, contact, phone, email, tier, notes, remoteUrl);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM licensing_registration_lead WHERE machine_id = ? ORDER BY id DESC LIMIT 1", machineId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLeads() {
        return jdbc.queryForList("SELECT * FROM licensing_registration_lead ORDER BY id DESC");
    }

    @Transactional
    public Map<String, Object> approveLead(Long leadId, String tierOverride, Integer daysOverride) {
        List<Map<String, Object>> leads = jdbc.queryForList("SELECT * FROM licensing_registration_lead WHERE id = ?", leadId);
        if (leads.isEmpty()) throw new NoSuchElementException("Lead not found: " + leadId);
        Map<String, Object> lead = leads.getFirst();

        String tier = (tierOverride != null && !tierOverride.isBlank())
                ? tierOverride.trim().toUpperCase(Locale.ROOT)
                : String.valueOf(lead.getOrDefault("requested_tier", "GOLD")).trim().toUpperCase(Locale.ROOT);
        int days = (daysOverride != null && daysOverride > 0) ? daysOverride : 365;

        // Fetch plan properties if available
        List<Map<String, Object>> planRows = jdbc.queryForList("SELECT * FROM licensing_plan WHERE tier_code = ?", tier);
        int maxUsers = 5;
        BigDecimal fee = new BigDecimal("3500.00");
        if (!planRows.isEmpty()) {
            Map<String, Object> p = planRows.getFirst();
            if (p.get("max_users") instanceof Number nu) maxUsers = nu.intValue();
            if (p.get("annual_fee") instanceof Number nf) fee = BigDecimal.valueOf(nf.doubleValue());
            if (daysOverride == null && p.get("validity_days") instanceof Number nd) days = nd.intValue();
        }

        Map<String, Object> regReq = new LinkedHashMap<>();
        regReq.put("companyName", lead.get("company_name"));
        regReq.put("machineId", lead.get("machine_id"));
        regReq.put("contactPerson", lead.get("contact_person"));
        regReq.put("phone", lead.get("phone"));
        regReq.put("email", lead.get("email"));
        regReq.put("tier", tier);
        regReq.put("maxUsers", maxUsers);
        regReq.put("annualFee", fee);
        regReq.put("days", days);
        regReq.put("remoteUrl", lead.get("remote_url"));
        regReq.put("notes", "Approved from Lead #" + leadId);

        Map<String, Object> client = registerClient(regReq);
        jdbc.update("UPDATE licensing_registration_lead SET status = 'APPROVED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", leadId);
        return client;
    }

    @Transactional
    public Map<String, Object> rejectLead(Long leadId) {
        if (leadId == null) throw new IllegalArgumentException("Lead ID is required");
        jdbc.update("UPDATE licensing_registration_lead SET status = 'REJECTED', updated_at = CURRENT_TIMESTAMP WHERE id = ?", leadId);
        return Map.of("success", true, "leadId", leadId, "status", "REJECTED");
    }

    @Transactional
    public Map<String, Object> deleteLead(Long leadId) {
        if (leadId == null) throw new IllegalArgumentException("Lead ID is required");
        jdbc.update("DELETE FROM licensing_registration_lead WHERE id = ?", leadId);
        return Map.of("success", true, "leadId", leadId, "deleted", true);
    }

    @Transactional
    public Map<String, Object> recordHeartbeat(String rawMachine, String clientVersion, Integer activeUsers, String remoteUrl) {
        return recordHeartbeat(rawMachine, clientVersion, activeUsers, remoteUrl, null, null, null);
    }

    @Transactional
    public Map<String, Object> recordHeartbeat(String rawMachine, String clientVersion, Integer activeUsers, String remoteUrl,
                                               String companyName, String clientIp, List<Map<String, Object>> users) {
        if (rawMachine == null || rawMachine.isBlank()) return Map.of("status", "UNKNOWN_MACHINE");
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);

        // 30-day automated telemetry retention purge
        purgeExpiredTelemetry();

        // 1. Process multi-user session & work hours telemetry
        if (users != null && !users.isEmpty()) {
            for (Map<String, Object> user : users) {
                recordSingleUserTelemetry(machineId, companyName, user);
            }
        }

        int resolvedActiveUsers = 1;
        if (activeUsers != null && activeUsers > 1) {
            resolvedActiveUsers = activeUsers;
        } else {
            try {
                Long liveCount = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT user_id) FROM auth_session WHERE expires_at > CURRENT_TIMESTAMP",
                        Long.class
                );
                resolvedActiveUsers = (liveCount != null && liveCount > 0) ? liveCount.intValue() : 1;
            } catch (Exception ignored) {
                resolvedActiveUsers = activeUsers != null && activeUsers > 0 ? activeUsers : 1;
            }
        }

        StringBuilder updateSql = new StringBuilder("UPDATE licensed_client_registry SET " +
                "last_heartbeat_at = CURRENT_TIMESTAMP, " +
                "client_version = COALESCE(?, client_version), " +
                "active_users_count = ? ");
        List<Object> params = new ArrayList<>();
        params.add(clientVersion != null && !clientVersion.isBlank() ? clientVersion.trim() : null);
        params.add(resolvedActiveUsers);

        if (remoteUrl != null && !remoteUrl.isBlank()) {
            updateSql.append(", remote_url = ? ");
            params.add(remoteUrl.trim());
        }
        updateSql.append("WHERE machine_id = ?");
        params.add(machineId);

        int updated = jdbc.update(updateSql.toString(), params.toArray());
        if (updated == 0) {
            // Check if there is an un-bound host license ('AUTO_BIND_HOST') authorized for dynamic binding
            List<Map<String, Object>> autoBindRows = jdbc.queryForList(
                    "SELECT id, company_name FROM licensed_client_registry WHERE machine_id = 'AUTO_BIND_HOST' LIMIT 1"
            );
            if (!autoBindRows.isEmpty()) {
                Long autoBindId = ((Number) autoBindRows.getFirst().get("id")).longValue();
                StringBuilder autoBindSql = new StringBuilder("UPDATE licensed_client_registry SET " +
                        "machine_id = ?, " +
                        "last_heartbeat_at = CURRENT_TIMESTAMP, " +
                        "client_version = COALESCE(?, client_version), " +
                        "active_users_count = ? ");
                List<Object> autoParams = new ArrayList<>();
                autoParams.add(machineId);
                autoParams.add(clientVersion != null && !clientVersion.isBlank() ? clientVersion.trim() : null);
                autoParams.add(resolvedActiveUsers);
                if (remoteUrl != null && !remoteUrl.isBlank()) {
                    autoBindSql.append(", remote_url = ? ");
                    autoParams.add(remoteUrl.trim());
                }
                autoBindSql.append("WHERE id = ?");
                autoParams.add(autoBindId);
                updated = jdbc.update(autoBindSql.toString(), autoParams.toArray());
            }
        }

        if (updated == 0) {
            // Unregistered / Rogue instance detected! Trigger Super Admin security alert
            sendRogueClientSecurityAlerts(machineId, companyName, clientVersion, clientIp, users);
            return Map.of("status", "UNREGISTERED", "machineId", machineId, "isPurchased", false);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, company_name, tier, status, valid_until, remote_url, last_heartbeat_at, client_version, " +
                        "COALESCE(is_purchased, true) AS is_purchased, kicked_at, kick_reason " +
                        "FROM licensed_client_registry WHERE machine_id = ?",
                machineId
        );
        Map<String, Object> client = rows.getFirst();
        String currentStatus = String.valueOf(client.get("status"));

        // If client is marked BLOCKED or KICKED, immediately alert the client
        if ("BLOCKED".equalsIgnoreCase(currentStatus) || "KICKED".equalsIgnoreCase(currentStatus) || "REVOKED".equalsIgnoreCase(currentStatus)) {
            return Map.of(
                    "status", "BLOCKED",
                    "machineId", machineId,
                    "companyName", client.get("company_name"),
                    "kickReason", client.getOrDefault("kick_reason", "Access revoked by administrator"),
                    "isPurchased", false
            );
        }

        boolean isPurchased = Boolean.TRUE.equals(client.get("is_purchased"));
        // If not purchased or plan is NONE/TRIAL expired, trigger alert
        if (!isPurchased || "NONE".equalsIgnoreCase(String.valueOf(client.get("tier")))) {
            sendRogueClientSecurityAlerts(machineId, companyName, clientVersion, clientIp, users);
        }

        return Map.of(
                "status", currentStatus,
                "companyName", client.get("company_name"),
                "tier", client.get("tier"),
                "validUntil", String.valueOf(client.get("valid_until")),
                "remoteUrl", client.get("remote_url") != null ? client.get("remote_url") : "",
                "clientVersion", client.get("client_version") != null ? client.get("client_version") : "",
                "isPurchased", isPurchased
        );
    }

    private void recordSingleUserTelemetry(String machineId, String companyName, Map<String, Object> user) {
        if (user == null || user.isEmpty()) return;
        String username = String.valueOf(user.getOrDefault("username", "")).trim();
        if (username.isBlank()) return;
        String roleCode = String.valueOf(user.getOrDefault("roleCode", "USER")).trim();
        long durationInc = user.get("durationSeconds") instanceof Number n ? n.longValue() : 30L;
        boolean isLogout = Boolean.TRUE.equals(user.get("isLogout"));
        boolean isActive = !isLogout && Boolean.TRUE.equals(user.getOrDefault("isActive", true));

        try {
            jdbc.update("""
                INSERT INTO client_user_telemetry (
                    machine_id, company_name, username, role_code, work_date,
                    first_login_today, last_logout_today, last_heartbeat,
                    total_seconds_today, is_active_now, updated_at
                ) VALUES (
                    ?, ?, ?, ?, CURRENT_DATE,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP,
                    ?, ?, CURRENT_TIMESTAMP
                ) ON CONFLICT (machine_id, username, work_date) DO UPDATE SET
                    last_heartbeat = CURRENT_TIMESTAMP,
                    total_seconds_today = client_user_telemetry.total_seconds_today + EXCLUDED.total_seconds_today,
                    is_active_now = EXCLUDED.is_active_now,
                    last_logout_today = CASE WHEN EXCLUDED.is_active_now = false THEN CURRENT_TIMESTAMP ELSE client_user_telemetry.last_logout_today END,
                    updated_at = CURRENT_TIMESTAMP
            """, machineId, companyName != null ? companyName : "Client Workspace", username, roleCode,
                    isLogout ? Instant.now() : null,
                    durationInc, isActive);
        } catch (Exception ex) {
            log.warn("Could not record client user telemetry for {}: {}", username, ex.getMessage());
        }
    }

    private void purgeExpiredTelemetry() {
        try {
            jdbc.update("DELETE FROM client_user_telemetry WHERE work_date < CURRENT_DATE - INTERVAL '30 days'");
        } catch (Exception e) {
            log.warn("Could not purge expired telemetry: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine) {
        return getClientUserTelemetry(rawMachine, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine, String targetDateStr) {
        if (rawMachine == null || rawMachine.isBlank()) return List.of();
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);

        String dateCondition = "t.work_date = CURRENT_DATE";
        List<Object> params = new ArrayList<>();
        params.add(machineId);

        if (targetDateStr != null && !targetDateStr.isBlank() && !"TODAY".equalsIgnoreCase(targetDateStr)) {
            dateCondition = "t.work_date = CAST(? AS date)";
            params.add(targetDateStr.trim());
        }

        return jdbc.queryForList(
                "SELECT t.id, t.machine_id, t.company_name, t.username, t.role_code, t.work_date, " +
                        "t.first_login_today, t.last_logout_today, t.last_heartbeat, " +
                        "CASE " +
                        "  WHEN t.is_active_now = true AND t.first_login_today IS NOT NULL THEN " +
                        "    GREATEST(COALESCE(t.total_seconds_today, 0), EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - t.first_login_today))::bigint) " +
                        "  ELSE COALESCE(t.total_seconds_today, 0) " +
                        "END AS total_seconds_today, " +
                        "t.is_active_now, " +
                        "COALESCE(u.locked, 0) AS is_locked, COALESCE(u.active, 1) AS is_user_active " +
                        "FROM client_user_telemetry t " +
                        "LEFT JOIN users u ON LOWER(u.username) = LOWER(t.username) " +
                        "WHERE t.machine_id = ? AND " + dateCondition + " " +
                        "ORDER BY t.is_active_now DESC, t.last_heartbeat DESC",
                params.toArray()
        );
    }

    @Transactional
    public Map<String, Object> releaseWorkstation(String rawMachine) {
        if (rawMachine == null || rawMachine.isBlank()) {
            return Map.of("success", false, "message", "Invalid machine ID");
        }
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        int updated = jdbc.update(
                "UPDATE licensed_client_registry SET status = 'RELEASED', " +
                        "notes = CONCAT(COALESCE(notes, ''), ' [Workstation released by SuperAdmin on ', CURRENT_TIMESTAMP, ']') " +
                        "WHERE machine_id = ?",
                machineId
        );
        jdbc.update("UPDATE client_user_telemetry SET is_active_now = false, last_logout_today = CURRENT_TIMESTAMP WHERE machine_id = ?", machineId);
        try {
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT DISTINCT u.id FROM users u JOIN client_user_telemetry t ON LOWER(u.username) = LOWER(t.username) WHERE t.machine_id = ?)", machineId);
        } catch (Exception ignored) {}
        return Map.of("success", updated > 0, "machineId", machineId, "status", "RELEASED");
    }

    @Transactional
    public Map<String, Object> kickClientUserSession(String rawMachine, String username) {
        if (rawMachine == null || rawMachine.isBlank() || username == null || username.isBlank()) {
            return Map.of("success", false, "message", "Invalid machine or username");
        }
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        jdbc.update(
                "UPDATE client_user_telemetry SET is_active_now = false, last_logout_today = CURRENT_TIMESTAMP " +
                        "WHERE machine_id = ? AND username = ? AND work_date = CURRENT_DATE",
                machineId, username
        );
        try {
            jdbc.update("DELETE FROM auth_session WHERE username = ?", username);
        } catch (Exception ignored) {}
        return Map.of("success", true, "machineId", machineId, "username", username, "status", "KICKED");
    }

    @Transactional
    public Map<String, Object> lockClientUser(String rawMachine, String username) {
        if (username == null || username.isBlank()) {
            return Map.of("success", false, "message", "Invalid username");
        }
        jdbc.update("UPDATE users SET locked = 1, lock_reason = 'LICENSOR_ADMIN', row_version = row_version + 1 WHERE username = ?", username);
        try {
            jdbc.update("DELETE FROM auth_session WHERE username = ?", username);
        } catch (Exception ignored) {}
        if (rawMachine != null && !rawMachine.isBlank()) {
            String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
            jdbc.update(
                    "UPDATE client_user_telemetry SET is_active_now = false, last_logout_today = CURRENT_TIMESTAMP " +
                            "WHERE machine_id = ? AND username = ? AND work_date = CURRENT_DATE",
                    machineId, username
            );
        }
        return Map.of("success", true, "username", username, "status", "LOCKED");
    }

    @Transactional
    public Map<String, Object> unlockClientUser(String rawMachine, String username) {
        if (username == null || username.isBlank()) {
            return Map.of("success", false, "message", "Invalid username");
        }
        jdbc.update("UPDATE users SET locked = 0, lock_reason = 'NONE', failed_attempts = 0, mfa_failed_attempts = 0, row_version = row_version + 1 WHERE username = ?", username);
        return Map.of("success", true, "username", username, "status", "UNLOCKED");
    }

    @Transactional
    public Map<String, Object> setUserActiveStatus(String rawMachine, String username, boolean active) {
        if (username == null || username.isBlank()) {
            return Map.of("success", false, "message", "Invalid username");
        }
        jdbc.update("UPDATE users SET active = ?, row_version = row_version + 1 WHERE username = ?", active ? 1 : 0, username);
        if (!active) {
            try {
                jdbc.update("DELETE FROM auth_session WHERE username = ?", username);
            } catch (Exception ignored) {}
            if (rawMachine != null && !rawMachine.isBlank()) {
                String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
                jdbc.update(
                        "UPDATE client_user_telemetry SET is_active_now = false, last_logout_today = CURRENT_TIMESTAMP " +
                                "WHERE machine_id = ? AND username = ? AND work_date = CURRENT_DATE",
                        machineId, username
                );
            }
        }
        return Map.of("success", true, "username", username, "status", active ? "ACTIVE" : "INACTIVE");
    }

    public void sendRogueClientSecurityAlerts(String machineId, String companyName, String clientVersion, String clientIp, List<Map<String, Object>> users) {
        // Debounce check: only send 1 alert per machine per 24 hours
        try {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT last_alert_sent_at FROM licensed_client_registry WHERE machine_id = ?", machineId
            );
            if (!existing.isEmpty() && existing.getFirst().get("last_alert_sent_at") != null) {
                Instant lastAlert = toInstant(existing.getFirst().get("last_alert_sent_at"));
                if (lastAlert != null && lastAlert.isAfter(Instant.now().minus(24, ChronoUnit.HOURS))) {
                    return; // debounced within 24h
                }
            }
        } catch (Exception ignored) {}

        String token = java.util.UUID.randomUUID().toString().replace("-", "") + Long.toHexString(System.currentTimeMillis());
        Instant expiresAt = Instant.now().plus(72, ChronoUnit.HOURS);

        try {
            jdbc.update(
                    "INSERT INTO license_revocation_token (token, machine_id, company_name, client_ip, expires_at) VALUES (?, ?, ?, ?, ?)",
                    token, machineId, (companyName != null && !companyName.isBlank()) ? companyName : "Unregistered Client",
                    clientIp != null ? clientIp : "Unknown IP", expiresAt
            );
            jdbc.update("UPDATE licensed_client_registry SET last_alert_sent_at = CURRENT_TIMESTAMP WHERE machine_id = ?", machineId);
        } catch (Exception ex) {
            log.warn("Could not record revocation token: {}", ex.getMessage());
        }

        String baseUrl = getSetting("licensing.server_public_url", "https://erp.jasviindustries.in");
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        String kickUrl = baseUrl + "/api/license/super/kick-client?token=" + token + "&machineId=" + machineId;

        String safeCompany = (companyName != null && !companyName.isBlank()) ? companyName : "Unlicensed Local Instance";
        String safeIp = (clientIp != null && !clientIp.isBlank()) ? clientIp : "Unknown Remote IP";
        String safeVer = (clientVersion != null && !clientVersion.isBlank()) ? clientVersion : org.example.shared.RuntimeContract.appVersion();

        StringBuilder userNames = new StringBuilder();
        if (users != null && !users.isEmpty()) {
            for (Map<String, Object> u : users) {
                if (!userNames.isEmpty()) userNames.append(", ");
                userNames.append(u.getOrDefault("username", "user"));
            }
        } else {
            userNames.append("admin (offline local admin)");
        }

        final String finalUsers = userNames.toString();
        // Virtual thread async execution (Rule 10: Outbound HTTP / SMTP decoupled from DB transactions)
        Thread.ofVirtual().name("rogue-client-alert-dispatcher").start(() -> {
            dispatchSuperAdminWhatsAppAlert(machineId, safeCompany, safeIp, safeVer, finalUsers, kickUrl);
            dispatchSuperAdminEmailAlert(machineId, safeCompany, safeIp, safeVer, finalUsers, kickUrl);
        });
    }

    private void dispatchSuperAdminWhatsAppAlert(String machineId, String company, String ip, String version, String users, String kickUrl) {
        if (whatsappService == null) return;
        String superAdminPhone = getSetting("licensing.superadmin_whatsapp", "");
        if (superAdminPhone.isBlank()) {
            try {
                superAdminPhone = jdbc.queryForObject(
                        "SELECT phone FROM users WHERE UPPER(COALESCE(role,'')) IN ('SUPER_ADMIN','ADMIN') AND phone IS NOT NULL AND BTRIM(phone) <> '' ORDER BY id ASC LIMIT 1",
                        String.class
                );
            } catch (Exception ignored) {}
        }
        if (superAdminPhone == null || superAdminPhone.isBlank()) {
            superAdminPhone = getSetting("licensing.support_phone", "9537544008");
        }
        if (superAdminPhone.isBlank()) return;

        String text = "🚨 *JASVI SECURITY ALERT: UNLICENSED INSTANCE*\n\n" +
                "An unauthorized local workstation has connected to the system without an active plan:\n\n" +
                "🏢 *Company:* " + company + "\n" +
                "💻 *Machine ID:* " + machineId + "\n" +
                "🌐 *IP Address:* " + ip + "\n" +
                "👤 *Reported Users:* " + users + "\n" +
                "📦 *Version:* Jasvi-Desktop " + version + "\n" +
                "⚠️ *Plan Status:* NO PLAN / UNLICENSED\n\n" +
                "🛑 *INSTANT 1-CLICK KICK & BLOCK:*\n" +
                "Tap below to immediately terminate sessions and permanently lock this workstation:\n" +
                kickUrl;

        try {
            whatsappService.sendText(new org.example.server.whatsapp.WahaDtos.TextSendRequest(superAdminPhone, text, null));
            log.info("Rogue instance security alert dispatched to Super Admin WhatsApp ({}) for machine {}", superAdminPhone, machineId);
        } catch (Exception e) {
            log.warn("Failed to dispatch rogue instance WhatsApp alert: {}", e.getMessage());
        }
    }

    private void dispatchSuperAdminEmailAlert(String machineId, String company, String ip, String version, String users, String kickUrl) {
        if (mailService == null || !mailService.isConfigured()) return;
        String superAdminEmail = getSetting("licensing.superadmin_email", "");
        if (superAdminEmail.isBlank()) {
            try {
                superAdminEmail = jdbc.queryForObject(
                        "SELECT email FROM users WHERE UPPER(COALESCE(role,'')) IN ('SUPER_ADMIN','ADMIN') AND email IS NOT NULL AND BTRIM(email) <> '' ORDER BY id ASC LIMIT 1",
                        String.class
                );
            } catch (Exception ignored) {}
        }
        if (superAdminEmail == null || superAdminEmail.isBlank()) return;

        String subject = "🚨 Security Alert: Unlicensed Local Instance Detected - " + company;
        String html = """
                <!DOCTYPE html>
                <html>
                <body style="font-family: Arial, sans-serif; background-color: #0f172a; color: #f8fafc; margin: 0; padding: 24px;">
                  <div style="max-width: 600px; margin: 0 auto; background: #1e293b; border-radius: 12px; border: 1px solid #334155; padding: 24px;">
                    <div style="background: #dc2626; color: #ffffff; padding: 12px 18px; border-radius: 8px; font-weight: bold; font-size: 16px;">
                      🚨 SECURITY ALERT: UNLICENSED LOCAL WORKSTATION DETECTED
                    </div>
                    <p style="color: #cbd5e1; font-size: 14px; margin-top: 16px;">
                      An unauthorized or unlicensed local workstation has connected to the Jasvi ecosystem without an active plan or purchase.
                    </p>
                    <table style="width: 100%; border-collapse: collapse; margin-top: 16px; font-size: 13px;">
                      <tr style="border-bottom: 1px solid #334155;"><td style="padding: 8px; color: #94a3b8;">Organization / Company:</td><td style="padding: 8px; font-weight: bold; color: #f8fafc;">%s</td></tr>
                      <tr style="border-bottom: 1px solid #334155;"><td style="padding: 8px; color: #94a3b8;">Terminal / Machine ID:</td><td style="padding: 8px; font-family: monospace; color: #38bdf8;">%s</td></tr>
                      <tr style="border-bottom: 1px solid #334155;"><td style="padding: 8px; color: #94a3b8;">Public IP Address:</td><td style="padding: 8px; font-family: monospace; color: #cbd5e1;">%s</td></tr>
                      <tr style="border-bottom: 1px solid #334155;"><td style="padding: 8px; color: #94a3b8;">Reported Active Users:</td><td style="padding: 8px; color: #f8fafc;">%s</td></tr>
                      <tr style="border-bottom: 1px solid #334155;"><td style="padding: 8px; color: #94a3b8;">Client Software Version:</td><td style="padding: 8px; color: #cbd5e1;">Jasvi-Desktop %s</td></tr>
                      <tr><td style="padding: 8px; color: #94a3b8;">Plan Status:</td><td style="padding: 8px; font-weight: bold; color: #ef4444;">UNLICENSED / NO PLAN</td></tr>
                    </table>
                    <div style="text-align: center; margin-top: 28px; margin-bottom: 20px;">
                      <a href="%s" style="background-color: #dc2626; color: #ffffff; text-decoration: none; padding: 14px 28px; font-size: 15px; font-weight: bold; border-radius: 8px; display: inline-block;">
                        🛑 INSTANT KICK & BLOCK THIS INSTANCE
                      </a>
                    </div>
                    <p style="font-size: 11px; color: #64748b; text-align: center; margin-top: 16px;">
                      Clicking this button will immediately terminate all active sessions and suspend access on the target workstation without requiring login.
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(company, machineId, ip, users, version, kickUrl);

        try {
            mailService.sendBusiness(superAdminEmail, subject, html);
            log.info("Rogue instance security alert emailed to Super Admin ({}) for machine {}", superAdminEmail, machineId);
        } catch (Exception e) {
            log.warn("Failed to dispatch rogue instance email alert: {}", e.getMessage());
        }
    }

    public String executeOneClickKick(String token, String rawMachine, String clientIp) {
        if (token == null || token.isBlank()) {
            return renderKickHtmlResponse(false, "Invalid Token", "Revocation token is missing.", null);
        }
        String machineId = rawMachine != null ? rawMachine.replace("-", "").toUpperCase(Locale.ROOT) : "";

        List<Map<String, Object>> tokenRows = jdbc.queryForList(
                "SELECT * FROM license_revocation_token WHERE token = ?", token
        );
        if (tokenRows.isEmpty()) {
            return renderKickHtmlResponse(false, "Invalid Token", "This revocation token does not exist or has expired.", null);
        }

        Map<String, Object> tok = tokenRows.getFirst();
        if (tok.get("used_at") != null) {
            return renderKickHtmlResponse(false, "Already Executed", "This instance was already kicked on " + tok.get("used_at"), null);
        }

        Instant expiresAt = toInstant(tok.get("expires_at"));
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            return renderKickHtmlResponse(false, "Token Expired", "This revocation token has expired.", null);
        }

        String targetMachine = String.valueOf(tok.get("machine_id"));
        if (machineId.isBlank()) machineId = targetMachine;

        // Mark token used
        jdbc.update("UPDATE license_revocation_token SET used_at = CURRENT_TIMESTAMP, used_by_ip = ? WHERE token = ?", clientIp, token);

        // Update or insert client registry record as BLOCKED
        int updated = jdbc.update(
                "UPDATE licensed_client_registry SET status = 'BLOCKED', is_purchased = false, kicked_at = CURRENT_TIMESTAMP, " +
                        "kick_reason = 'Terminated by Super Admin via Email/WhatsApp 1-click alert' WHERE machine_id = ?",
                targetMachine
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO licensed_client_registry (company_name, machine_id, tier, max_users, annual_fee, valid_until, status, license_payload_enc, notes, is_purchased, kicked_at, kick_reason) " +
                            "VALUES (?, ?, 'NONE', 0, 0, CURRENT_TIMESTAMP, 'BLOCKED', 'BLOCKED', 'Blocked via Rogue Alert', false, CURRENT_TIMESTAMP, 'Terminated by Super Admin via 1-click alert')",
                    tok.getOrDefault("company_name", "Unlicensed Client"), targetMachine
            );
        }

        // Invalidate active auth sessions
        try {
            jdbc.update("DELETE FROM auth_session WHERE user_id IN (SELECT id FROM users WHERE role <> 'SUPER_ADMIN')");
        } catch (Exception ignored) {}

        return renderKickHtmlResponse(true, "Workstation Successfully Kicked & Blacklisted",
                "The target workstation has been remotely terminated and blacklisted. Any further attempt to run or connect will be rejected with an administrative lock.",
                targetMachine);
    }

    private String renderKickHtmlResponse(boolean success, String title, String message, String machineId) {
        String badgeColor = success ? "#10b981" : "#ef4444";
        String icon = success ? "✅" : "⚠️";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Jasvi ERP - Remote Revocation Authority</title>
                </head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b1120; color: #f8fafc; margin: 0; padding: 32px 16px; display: flex; justify-content: center; align-items: center; min-height: 80vh;">
                  <div style="max-width: 540px; width: 100%%; background: #1e293b; border-radius: 16px; border: 1px solid #334155; padding: 36px 28px; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5); text-align: center;">
                    <div style="font-size: 48px; margin-bottom: 16px;">%s</div>
                    <h2 style="color: %s; margin: 0 0 12px 0; font-size: 22px;">%s</h2>
                    <p style="color: #94a3b8; font-size: 14px; line-height: 1.6; margin: 0 0 24px 0;">%s</p>
                    %s
                    <div style="margin-top: 32px; padding-top: 20px; border-top: 1px solid #334155; font-size: 12px; color: #64748b;">
                      Jasvi Industries • Master Licensor Security Node • Antigravity Ecosystem
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(icon, badgeColor, title, message,
                machineId != null ? "<div style=\"background: #0f172a; padding: 12px; border-radius: 8px; font-family: monospace; color: #38bdf8; font-size: 14px;\">Terminal ID: " + machineId + "</div>" : "");
    }

    /**
     * Decoupled from database transactions (Rule 10):
     * Dispatches the activation key automatically via WhatsApp (WAHA) and Email (SMTP).
     * If external networks or servers are unavailable, this will not roll back the license.
     */
    public Map<String, Object> dispatchLicenseChannels(Map<String, Object> client) {
        Map<String, Object> em = dispatchEmailChannel(client);
        Map<String, Object> wa = dispatchWhatsAppChannel(client);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("whatsappSent", wa.get("whatsappSent"));
        res.put("whatsappError", wa.get("whatsappError"));
        res.put("emailSent", em.get("emailSent"));
        res.put("emailError", em.get("emailError"));
        res.put("licenseKey", client.getOrDefault("license_payload_enc", client.getOrDefault("licensePayloadEnc", "")));
        return res;
    }

    public Map<String, Object> dispatchEmailChannel(Map<String, Object> client) {
        String email = client.get("email") != null ? String.valueOf(client.get("email")).trim() : "";
        String company = String.valueOf(client.getOrDefault("company_name", client.getOrDefault("companyName", "Valued Client")));
        String tier = String.valueOf(client.getOrDefault("tier", "ENTERPRISE"));
        String machineId = String.valueOf(client.getOrDefault("machine_id", client.getOrDefault("machineId", "")));
        String validUntil = String.valueOf(client.getOrDefault("valid_until", client.getOrDefault("validUntil", "")));
        String payload = String.valueOf(client.getOrDefault("license_payload_enc", client.getOrDefault("licensePayloadEnc", "")));

        boolean emailSent = false;
        String emailError = null;

        if (mailService != null && !email.isBlank()) {
            try {
                String subject = "Official Jasvi ERP Enterprise License Activation - " + company;
                String baseName = company.replaceAll("[^a-zA-Z0-9_-]", "_");
                String filenameJlic = baseName + "-license.jlic";
                String filenameJson = baseName + "-license.json";
                byte[] jlicBytes = payload.getBytes(StandardCharsets.UTF_8);

                String emailHtml = """
                        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 620px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 8px; background-color: #ffffff; color: #1e293b;">
                            <div style="border-bottom: 2px solid #0284c7; padding-bottom: 16px; margin-bottom: 20px;">
                                <h2 style="margin: 0; color: #0f172a; font-size: 22px;">Jasvi ERP Enterprise License Activation</h2>
                                <p style="margin: 4px 0 0; color: #64748b; font-size: 14px;">Official Digital License Delivery</p>
                            </div>
                            <p style="font-size: 15px; line-height: 1.5;">Dear <strong>{{company}}</strong>,</p>
                            <p style="font-size: 14px; line-height: 1.5; color: #334155;">
                                Your Jasvi ERP production license has been issued and approved. Both your official digital license file (<strong>{{filenameJlic}}</strong>) and readable IT certificate copy (<strong>{{filenameJson}}</strong>) are attached to this email.
                            </p>
                            <div style="background-color: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 16px; margin: 20px 0;">
                                <table style="width: 100%; border-collapse: collapse; font-size: 14px;">
                                    <tr><td style="padding: 6px 0; color: #64748b; width: 40%;"><strong>Registered Client:</strong></td><td style="padding: 6px 0; color: #0f172a;"><strong>{{company}}</strong></td></tr>
                                    <tr><td style="padding: 6px 0; color: #64748b;"><strong>Subscription Tier:</strong></td><td style="padding: 6px 0; color: #0369a1; font-weight: 600;">{{tier}}</td></tr>
                                    <tr><td style="padding: 6px 0; color: #64748b;"><strong>Hardware Terminal ID:</strong></td><td style="padding: 6px 0; font-family: monospace; color: #0f172a;">{{machineId}}</td></tr>
                                    <tr><td style="padding: 6px 0; color: #64748b;"><strong>Validity Period:</strong></td><td style="padding: 6px 0; color: #15803d; font-weight: 600;">Until {{validUntil}}</td></tr>
                                </table>
                            </div>
                            <h3 style="font-size: 16px; color: #0f172a; margin-top: 24px; margin-bottom: 12px;">How to Activate:</h3>
                            <ol style="font-size: 14px; line-height: 1.6; padding-left: 20px; color: #334155;">
                                <li>Download and save the attached <strong>{{filenameJlic}}</strong> (or <strong>{{filenameJson}}</strong>) file on your workstation.</li>
                                <li>Open <strong>Jasvi ERP Desktop</strong>.</li>
                                <li>On the startup prompt or in <em>Settings &gt; License</em>, click <strong>[Import .jlic File]</strong> (both formats are supported).</li>
                                <li>Select the downloaded file. Your system will immediately activate with full enterprise capabilities.</li>
                            </ol>
                            <div style="margin-top: 28px; padding-top: 16px; border-top: 1px solid #e2e8f0; font-size: 12px; color: #94a3b8; text-align: center;">
                                Jasvi Industries &bull; Enterprise ERP Systems &bull; Support: support@jasviindustries.in
                            </div>
                        </div>
                        """
                        .replace("{{company}}", company)
                        .replace("{{filenameJlic}}", filenameJlic)
                        .replace("{{filenameJson}}", filenameJson)
                        .replace("{{tier}}", tier)
                        .replace("{{machineId}}", machineId)
                        .replace("{{validUntil}}", validUntil);

                var attJlic = new org.example.server.auth.SmtpMailService.Attachment(filenameJlic, "application/octet-stream", jlicBytes);
                var attJson = new org.example.server.auth.SmtpMailService.Attachment(filenameJson, "application/json", jlicBytes);
                mailService.sendBusiness(email, subject, emailHtml, List.of(attJlic, attJson));
                emailSent = true;
            } catch (Exception ex) {
                emailError = ex.getMessage();
                log.warn("Automated Email dispatch failed for client {}: {}", company, ex.getMessage());
            }
        } else if (email.isBlank()) {
            emailError = "No email address registered for client";
        } else {
            emailError = "SMTP Mail service not active on server";
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("emailSent", emailSent);
        res.put("emailError", emailError != null ? emailError : "");
        return res;
    }

    public Map<String, Object> dispatchWhatsAppChannel(Map<String, Object> client) {
        String phone = client.get("phone") != null ? String.valueOf(client.get("phone")).replaceAll("[^0-9]", "") : "";
        String email = client.get("email") != null ? String.valueOf(client.get("email")).trim() : "";
        String company = String.valueOf(client.getOrDefault("company_name", client.getOrDefault("companyName", "Valued Client")));
        String tier = String.valueOf(client.getOrDefault("tier", "ENTERPRISE"));
        String machineId = String.valueOf(client.getOrDefault("machine_id", client.getOrDefault("machineId", "")));
        String validUntil = String.valueOf(client.getOrDefault("valid_until", client.getOrDefault("validUntil", "")));
        String payload = String.valueOf(client.getOrDefault("license_payload_enc", client.getOrDefault("licensePayloadEnc", "")));

        boolean whatsappSent = false;
        String whatsappError = null;

        String baseName = company.replaceAll("[^a-zA-Z0-9_-]", "_");
        String filenameJlic = baseName + "-license.jlic";
        String filenameJson = baseName + "-license.json";
        byte[] jlicBytes = payload.getBytes(StandardCharsets.UTF_8);

        String caption = "Dear " + company + ",\n\n" +
                "🎉 Your Jasvi ERP Production License is ACTIVE!\n\n" +
                "🏢 Company: " + company + "\n" +
                "💻 Machine ID: " + machineId + "\n" +
                "💎 Plan Tier: " + tier + "\n" +
                "⏳ Valid Until: " + validUntil + "\n\n" +
                "📄 Attached: Official Jasvi License (" + filenameJlic + ") & readable IT certificate copy (" + filenameJson + ").\n" +
                "To activate: Open Jasvi ERP Desktop > Import License File > Select either file.\n\n" +
                "Thank you for choosing Jasvi ERP!";

        if (whatsappService != null && !phone.isBlank()) {
            try {
                // Send primary .jlic file via WhatsApp
                var resp1 = whatsappService.sendRawFile(phone, filenameJlic, "application/octet-stream", jlicBytes, caption);
                // Follow up with readable .json certificate
                var resp2 = whatsappService.sendRawFile(phone, filenameJson, "application/json", jlicBytes, "📄 Attached: Readable JSON license certificate copy for your IT records (" + filenameJson + ")");
                whatsappSent = (resp1 != null && resp1.success()) || (resp2 != null && resp2.success());
                if (!whatsappSent) {
                    // Fall back to clean text message if file sending fails
                    String fallbackText = caption + (!email.isBlank() ? "\n\n(Official license files have also been emailed to " + email + ")" : "");
                    var textResp = whatsappService.sendText(new WahaDtos.TextSendRequest(phone, fallbackText, null));
                    whatsappSent = textResp != null && textResp.success();
                    if (!whatsappSent && textResp != null) whatsappError = textResp.message();
                }
            } catch (Exception ex) {
                whatsappError = ex.getMessage();
                log.warn("Automated WhatsApp dispatch failed for client {}: {}", company, ex.getMessage());
            }
        } else if (phone.isBlank()) {
            whatsappError = "No phone number provided";
        } else {
            whatsappError = "WhatsApp service not active on server";
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("whatsappSent", whatsappSent);
        res.put("whatsappError", whatsappError != null ? whatsappError : "");
        return res;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> checkLeadStatus(String rawMachine) {
        if (rawMachine == null || rawMachine.isBlank()) return Map.of("status", "NONE");
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> clients = jdbc.queryForList(
                "SELECT id, company_name, machine_id, tier, valid_until, status, license_payload_enc " +
                        "FROM licensed_client_registry WHERE machine_id = ?", machineId);
        if (!clients.isEmpty()) {
            Map<String, Object> c = clients.getFirst();
            return Map.of(
                    "status", c.get("status"),
                    "companyName", c.get("company_name"),
                    "licensePayload", c.get("license_payload_enc"),
                    "validUntil", String.valueOf(c.get("valid_until"))
            );
        }
        List<Map<String, Object>> leads = jdbc.queryForList(
                "SELECT id, company_name, status, requested_tier, created_at " +
                        "FROM licensing_registration_lead WHERE machine_id = ? ORDER BY id DESC LIMIT 1", machineId);
        if (!leads.isEmpty()) {
            Map<String, Object> l = leads.getFirst();
            return Map.of(
                    "status", l.get("status"),
                    "companyName", l.get("company_name"),
                    "requestedTier", l.get("requested_tier")
            );
        }
        return Map.of("status", "UNREGISTERED");
    }

    private String getSetting(String key, String fallback) {
        try {
            List<String> values = jdbc.query("SELECT setting_value FROM application_setting WHERE setting_key = ?",
                    (row, idx) -> row.getString(1), key);
            return values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()
                    ? fallback : values.getFirst().trim();
        } catch (Exception e) {
            return fallback;
        }
    }

    private void saveSetting(String key, Object value) {
        if (value == null) return;
        String str = String.valueOf(value).trim();
        jdbc.update("""
                INSERT INTO application_setting (setting_key, setting_value)
                VALUES (?, ?)
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value
                """, key, str);
    }
}
