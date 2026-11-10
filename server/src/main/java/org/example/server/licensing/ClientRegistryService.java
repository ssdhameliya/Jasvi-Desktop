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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
                "remote_url, last_heartbeat_at, client_version, active_users_count, features " +
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
                        "remote_url, last_heartbeat_at, client_version, active_users_count, features " +
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

    private static Instant toInstant(Object obj) {
        if (obj instanceof Instant i) return i;
        if (obj instanceof java.util.Date d) return d.toInstant();
        if (obj instanceof Number num) return Instant.ofEpochMilli(num.longValue());
        if (obj instanceof java.time.temporal.TemporalAccessor ta) return Instant.from(ta);
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
                "SELECT COUNT(*) FROM licensed_client WHERE UPPER(tier) = ? AND UPPER(status) = 'ACTIVE'",
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
    public Map<String, Object> recordHeartbeat(String rawMachine, String clientVersion, Integer activeUsers, String remoteUrl) {
        if (rawMachine == null || rawMachine.isBlank()) return Map.of("status", "UNKNOWN_MACHINE");
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);

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
            return Map.of("status", "UNREGISTERED", "machineId", machineId);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, company_name, tier, status, valid_until, remote_url, last_heartbeat_at, client_version FROM licensed_client_registry WHERE machine_id = ?",
                machineId
        );
        Map<String, Object> client = rows.getFirst();
        return Map.of(
                "status", client.get("status"),
                "companyName", client.get("company_name"),
                "tier", client.get("tier"),
                "validUntil", String.valueOf(client.get("valid_until")),
                "remoteUrl", client.get("remote_url") != null ? client.get("remote_url") : "",
                "clientVersion", client.get("client_version") != null ? client.get("client_version") : ""
        );
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
