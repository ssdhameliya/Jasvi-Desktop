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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClientRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistryService.class);
    private static final String DEFAULT_PRIV_KEY = "MC4CAQAwBQYDK2VwBCIEIOzrtL2IsC8IKub6TbLVTvXk4kw/FmOmeA0UUEnHFvCA";

    @Value("${dse.deployment.environment:LOCAL}")
    private String deploymentEnv = "LOCAL";

    @Value("${dse.public-url.local:http://localhost:58080}")
    private String localPublicUrl = "http://localhost:58080";

    @Value("${dse.public-url.uat:https://api-uat.jasviindustries.in}")
    private String uatPublicUrl = "https://api-uat.jasviindustries.in";

    @Value("${dse.public-url.prod:https://erp.jasviindustries.in}")
    private String prodPublicUrl = "https://erp.jasviindustries.in";

    private final Map<String, Instant> rogueAlertDebounceMap = new ConcurrentHashMap<>();

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
            jdbc.update("""
                CREATE TABLE IF NOT EXISTS client_user_session_log (
                    id BIGSERIAL PRIMARY KEY,
                    machine_id VARCHAR(64) NOT NULL,
                    username VARCHAR(120) NOT NULL,
                    role_code VARCHAR(64) DEFAULT 'USER',
                    work_date DATE NOT NULL DEFAULT CURRENT_DATE,
                    session_start TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    session_end TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    duration_seconds BIGINT NOT NULL DEFAULT 0,
                    is_active BOOLEAN NOT NULL DEFAULT TRUE
                )
            """);
            jdbc.update("CREATE INDEX IF NOT EXISTS idx_client_user_session_lookup ON client_user_session_log(machine_id, username, work_date)");
        } catch (Exception e) {
            log.debug("Could not initialize client_user_session_log table: {}", e.getMessage());
        }
        try {
            // Deduplicate primary organization records on startup
            jdbc.update(
                    "DELETE FROM licensed_client_registry WHERE UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' " +
                            "AND id NOT IN (SELECT id FROM licensed_client_registry WHERE UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' ORDER BY valid_until DESC, id DESC LIMIT 1)"
            );
        } catch (Exception ignored) {}
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM licensed_client_registry", Integer.class);
            if (count == null || count == 0) {
                // Dynamically seed anchor client from users table (Rule 17: Zero Mock Data, Zero hardcoded strings)
                String seedContact = "Platform Administrator";
                String seedPhone = "";
                String seedEmail = "";
                try {
                    Map<String, Object> u = jdbc.queryForMap(
                            "SELECT full_name, email, phone FROM users WHERE LOWER(username) = 'superadmin' OR UPPER(COALESCE(role, '')) = 'SUPER_ADMIN' ORDER BY id ASC LIMIT 1"
                    );
                    if (u.get("full_name") != null && !String.valueOf(u.get("full_name")).isBlank()) seedContact = String.valueOf(u.get("full_name")).trim();
                    if (u.get("phone") != null && !String.valueOf(u.get("phone")).isBlank()) seedPhone = String.valueOf(u.get("phone")).trim();
                    if (u.get("email") != null && !String.valueOf(u.get("email")).isBlank()) seedEmail = String.valueOf(u.get("email")).trim();
                } catch (Exception ignored) {}

                registerClient(Map.of(
                        "companyName", "JASVI INDUSTRIES",
                        "machineId", HardwareFingerprintUtil.getDisplayMachineId(),
                        "contactPerson", seedContact,
                        "phone", seedPhone,
                        "email", seedEmail,
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
        StringBuilder sql = new StringBuilder("SELECT r.id, r.company_name, r.machine_id, r.contact_person, r.phone, r.email, " +
                "r.tier, r.max_users, r.annual_fee, r.issued_at, r.valid_until, r.status, r.license_payload_enc, r.notes, r.row_version, " +
                "r.remote_url, r.last_heartbeat_at, r.client_version, " +
                "COALESCE((SELECT COUNT(DISTINCT LOWER(t.username)) FROM client_user_telemetry t " +
                "WHERE (t.machine_id = r.machine_id OR UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(COALESCE(r.company_name, '')))) " +
                "AND t.work_date = CAST((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Kolkata') AS date) " +
                "AND COALESCE(t.is_active_now, false) = true " +
                "AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes'), 0) AS active_users_count, " +
                "COALESCE((SELECT SUM(COALESCE(t.total_seconds_today, 0)) FROM client_user_telemetry t " +
                "WHERE (t.machine_id = r.machine_id OR UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(COALESCE(r.company_name, '')))) " +
                "AND t.work_date = CAST((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Kolkata') AS date)), 0) AS today_total_seconds, " +
                "COALESCE((SELECT COUNT(DISTINCT LOWER(t.username)) FROM client_user_telemetry t " +
                "WHERE (t.machine_id = r.machine_id OR UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(COALESCE(r.company_name, '')))) " +
                "AND t.work_date = CAST((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Kolkata') AS date) " +
                "AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL)), 0) AS today_users_count, " +
                "r.features, COALESCE(r.is_purchased, true) AS is_purchased, r.last_alert_sent_at, r.kicked_at, r.kick_reason " +
                "FROM licensed_client_registry r WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            sql.append("AND r.status = ? ");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }

        if (query != null && !query.isBlank()) {
            String q = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append("AND (LOWER(r.company_name) LIKE ? OR LOWER(r.machine_id) LIKE ? OR LOWER(r.contact_person) LIKE ? OR LOWER(r.phone) LIKE ?) ");
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }

        sql.append("ORDER BY r.id DESC");
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

        Long existingId = null;
        if (req.get("id") != null) {
            try {
                existingId = Long.parseLong(String.valueOf(req.get("id")).trim());
            } catch (Exception ignored) {}
        }

        if (existingId != null) {
            jdbc.update(
                    "UPDATE licensed_client_registry SET " +
                            "company_name = ?, " +
                            "machine_id = ?, " +
                            "contact_person = ?, " +
                            "phone = ?, " +
                            "email = ?, " +
                            "tier = ?, " +
                            "max_users = ?, " +
                            "annual_fee = ?, " +
                            "valid_until = ?, " +
                            "status = 'ACTIVE', " +
                            "license_payload_enc = ?, " +
                            "notes = ?, " +
                            "remote_url = COALESCE(?, licensed_client_registry.remote_url), " +
                            "features = ?, " +
                            "row_version = licensed_client_registry.row_version + 1 " +
                            "WHERE id = ?",
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
                    featuresStr,
                    existingId
            );
        } else {
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
        }

        try {
            // Deduplicate primary organization records after registration or edit
            jdbc.update(
                    "DELETE FROM licensed_client_registry WHERE UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' " +
                            "AND id NOT IN (SELECT id FROM licensed_client_registry WHERE UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' ORDER BY valid_until DESC, id DESC LIMIT 1)"
            );
        } catch (Exception ignored) {}

        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            if (machineId.equalsIgnoreCase(hostClean) || "JASVI INDUSTRIES".equalsIgnoreCase(companyName)) {
                jdbc.update(
                        "UPDATE app_license SET tier = ?, max_users = ?, valid_until = ?, features = ?, signature = ?, canonical_payload = ?, " +
                                "last_verified_at = CURRENT_TIMESTAMP, status = 'ACTIVE' WHERE UPPER(TRIM(company_name)) = ?",
                        tier, maxUsers, java.util.Date.from(validUntil), featuresStr, signature, payload.toCanonicalString(),
                        companyName.toUpperCase(Locale.ROOT)
                );

                // Rule 17 (Zero Mock Data): Dynamically cascade Master Organization Contact from Tab 1 to SuperAdmin identity & alerts
                if (email != null && !email.isBlank()) {
                    jdbc.update(
                            "UPDATE users SET email = ?, full_name = COALESCE(NULLIF(?, ''), full_name) " +
                                    "WHERE LOWER(username) = 'superadmin' OR UPPER(COALESCE(role, '')) = 'SUPER_ADMIN'",
                            email.trim(), contactPerson != null ? contactPerson.trim() : null
                    );
                    saveSetting("licensing.superadmin_email", email.trim());
                }
                if (phone != null && !phone.isBlank()) {
                    jdbc.update(
                            "UPDATE users SET phone = ? " +
                                    "WHERE LOWER(username) = 'superadmin' OR UPPER(COALESCE(role, '')) = 'SUPER_ADMIN'",
                            phone.trim()
                    );
                    saveSetting("licensing.superadmin_whatsapp", phone.trim());
                }
            }
        } catch (Exception ignored) {}

        List<Map<String, Object>> rows;
        if (existingId != null) {
            rows = jdbc.queryForList("SELECT * FROM licensed_client_registry WHERE id = ?", existingId);
        } else {
            rows = jdbc.queryForList("SELECT * FROM licensed_client_registry WHERE machine_id = ?", machineId);
        }
        return rows.isEmpty() ? getClients(null, "ALL").getFirst() : rows.getFirst();
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

    public String resolveDefaultServerPublicUrl() {
        String env = deploymentEnv != null ? deploymentEnv.trim().toUpperCase(Locale.ROOT) : "LOCAL";
        return switch (env) {
            case "PROD", "PRODUCTION" -> prodPublicUrl != null && !prodPublicUrl.isBlank() ? prodPublicUrl.trim() : "";
            case "UAT", "STAGING" -> uatPublicUrl != null && !uatPublicUrl.isBlank() ? uatPublicUrl.trim() : "";
            default -> {
                String tunnelUrl = getSetting("remote_access.last_tunnel_url", null);
                if (tunnelUrl != null && !tunnelUrl.isBlank()) yield tunnelUrl.trim();
                yield localPublicUrl != null && !localPublicUrl.isBlank() ? localPublicUrl.trim() : "http://localhost:58080";
            }
        };
    }

    public String resolveEffectiveServerPublicUrl() {
        String env = deploymentEnv != null ? deploymentEnv.trim().toUpperCase(Locale.ROOT) : "LOCAL";
        String savedUrl = getSetting("licensing.server_public_url", null);

        // In LOCAL / DEV mode, ignore saved cloud domains (they cannot route to local machine)
        if ("LOCAL".equals(env) || "DEV".equals(env) || "TEST".equals(env)) {
            if (savedUrl != null && !savedUrl.isBlank()
                    && (prodPublicUrl.isBlank() || !savedUrl.contains(prodPublicUrl.replaceFirst("^https?://", "")))
                    && (uatPublicUrl.isBlank() || !savedUrl.contains(uatPublicUrl.replaceFirst("^https?://", "")))) {
                return savedUrl.trim();
            }
            String tunnelUrl = getSetting("remote_access.last_tunnel_url", null);
            if (tunnelUrl != null && !tunnelUrl.isBlank()) return tunnelUrl.trim();
            return localPublicUrl != null && !localPublicUrl.isBlank() ? localPublicUrl.trim() : "http://localhost:58080";
        }

        return (savedUrl != null && !savedUrl.isBlank()) ? savedUrl.trim() : resolveDefaultServerPublicUrl();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCommercialSettings() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Dynamically resolve Master Organization Contact from Tab 1 (primary client in licensed_client_registry)
        String masterEmail = null;
        String masterPhone = null;
        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            List<Map<String, Object>> masterClients = jdbc.queryForList(
                    "SELECT email, phone FROM licensed_client_registry " +
                            "WHERE UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' OR UPPER(REPLACE(machine_id, '-', '')) = ? " +
                            "ORDER BY id ASC LIMIT 1",
                    hostClean
            );
            if (!masterClients.isEmpty()) {
                Map<String, Object> mc = masterClients.getFirst();
                if (mc.get("email") != null && !String.valueOf(mc.get("email")).isBlank()) {
                    masterEmail = String.valueOf(mc.get("email")).trim();
                }
                if (mc.get("phone") != null && !String.valueOf(mc.get("phone")).isBlank()) {
                    masterPhone = String.valueOf(mc.get("phone")).trim();
                }
            }
        } catch (Exception ignored) {}

        // Fallback dynamically to users table where username = 'superadmin'
        if (masterEmail == null || masterEmail.isBlank()) {
            try {
                masterEmail = jdbc.queryForObject(
                        "SELECT email FROM users WHERE LOWER(username) = 'superadmin' OR UPPER(COALESCE(role, '')) = 'SUPER_ADMIN' ORDER BY id ASC LIMIT 1",
                        String.class
                );
            } catch (Exception ignored) {}
        }
        if (masterPhone == null || masterPhone.isBlank()) {
            try {
                masterPhone = jdbc.queryForObject(
                        "SELECT phone FROM users WHERE LOWER(username) = 'superadmin' OR UPPER(COALESCE(role, '')) = 'SUPER_ADMIN' ORDER BY id ASC LIMIT 1",
                        String.class
                );
            } catch (Exception ignored) {}
        }

        String defaultEmail = (masterEmail != null && !masterEmail.isBlank()) ? masterEmail : getSetting("licensing.superadmin_email", "");
        String defaultPhone = (masterPhone != null && !masterPhone.isBlank()) ? masterPhone : getSetting("licensing.superadmin_whatsapp", "");

        String templatesCompany = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc).companyName();
        String supportBrand = getSetting("licensing.issuer_brand", templatesCompany);
        String supportEmail = getSetting("licensing.support_email", defaultEmail != null ? defaultEmail : getSetting("company.email", ""));
        result.put("supportPhone", getSetting("licensing.support_phone", (defaultPhone != null && !defaultPhone.isBlank()) ? defaultPhone : ""));
        result.put("supportEmail", supportEmail);
        result.put("issuerBrand", supportBrand);
        result.put("upiVpa", getSetting("licensing.upi_vpa", ""));
        result.put("payeeName", getSetting("licensing.payee_name", templatesCompany));
        result.put("gstRate", getSetting("licensing.gst_rate", "18.00"));
        result.put("whatsappTemplateLead", getSetting("licensing.whatsapp_template_lead",
                "Hello " + supportBrand + " Team, I am {CompanyName} ({ContactPerson}, {Phone}). Terminal ID: {MachineId}. Please activate our " + supportBrand + " license."));
        result.put("whatsappTemplateIssued", getSetting("licensing.whatsapp_template_issued",
                getSetting("whatsapp.template.license_activation.body",
                        "Dear {CompanyName}, your {IssuerBrand} {Tier} license is approved! Terminal ID: {MachineId}. Valid Until: {ValidUntil}. Thank you for choosing {IssuerBrand}.")));
        result.put("superAdminEmail", defaultEmail != null ? defaultEmail : "");
        result.put("superAdminWhatsapp", defaultPhone != null ? defaultPhone : "");
        result.put("serverPublicUrl", resolveEffectiveServerPublicUrl());
        return result;
    }

    @Transactional
    public void saveCommercialSettings(Map<String, Object> settings) {
        if (settings == null) return;
        saveSetting("licensing.support_phone", settings.get("supportPhone"));
        saveSetting("licensing.support_email", settings.get("supportEmail"));
        saveSetting("licensing.issuer_brand", settings.get("issuerBrand"));
        saveSetting("licensing.upi_vpa", settings.get("upiVpa"));
        saveSetting("licensing.payee_name", settings.get("payeeName"));
        saveSetting("licensing.gst_rate", settings.get("gstRate"));
        saveSetting("licensing.whatsapp_template_lead", settings.get("whatsappTemplateLead"));
        saveSetting("licensing.whatsapp_template_issued", settings.get("whatsappTemplateIssued"));
        saveSetting("licensing.superadmin_email", settings.get("superAdminEmail"));
        saveSetting("licensing.superadmin_whatsapp", settings.get("superAdminWhatsapp"));
        saveSetting("licensing.server_public_url", settings.get("serverPublicUrl"));
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
        syncLocalWorkstationUsersIfHost(machineId, companyName);
        relayHeartbeatToCentralCloudAsync(machineId, clientVersion, activeUsers, remoteUrl, companyName, users);

        int resolvedActiveUsers;
        if (users != null && !users.isEmpty()) {
            try {
                Long liveCount = jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT LOWER(username)) FROM client_user_telemetry " +
                                "WHERE machine_id = ? " +
                                "AND work_date = CAST((CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Kolkata') AS date) " +
                                "AND COALESCE(is_active_now, false) = true " +
                                "AND last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes'",
                        Long.class,
                        machineId
                );
                int telemetryCount = liveCount != null ? liveCount.intValue() : 0;
                resolvedActiveUsers = (activeUsers != null && activeUsers > telemetryCount) ? activeUsers : telemetryCount;
            } catch (Exception ignored) {
                resolvedActiveUsers = activeUsers != null ? Math.max(0, activeUsers) : 0;
            }
        } else {
            resolvedActiveUsers = activeUsers != null ? Math.max(0, activeUsers) : 1;
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

        if (updated == 0 && companyName != null && !companyName.isBlank()) {
            // Company-wide authorized workstation seat recognition (Cloud Gateway / Multi-seat license)
            List<Map<String, Object>> companyLicenses = jdbc.queryForList(
                    "SELECT id, company_name, tier, max_users, status FROM licensed_client_registry " +
                            "WHERE (UPPER(TRIM(company_name)) = UPPER(TRIM(?)) OR UPPER(TRIM(company_name)) LIKE UPPER(TRIM(?))) " +
                            "AND status = 'ACTIVE' LIMIT 1",
                    companyName.trim(), "%" + companyName.trim() + "%"
            );
            if (!companyLicenses.isEmpty()) {
                Long companyId = ((Number) companyLicenses.getFirst().get("id")).longValue();
                jdbc.update("UPDATE licensed_client_registry SET last_heartbeat_at = CURRENT_TIMESTAMP, " +
                        "client_version = COALESCE(?, client_version), active_users_count = ? WHERE id = ?",
                        clientVersion != null && !clientVersion.isBlank() ? clientVersion.trim() : null,
                        resolvedActiveUsers, companyId);
                updated = 1;
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
                        "FROM licensed_client_registry WHERE machine_id = ? " +
                        "OR UPPER(REPLACE(machine_id, '-', '')) = UPPER(REPLACE(?, '-', '')) " +
                        "ORDER BY (CASE WHEN UPPER(REPLACE(machine_id, '-', '')) = UPPER(REPLACE(?, '-', '')) THEN 0 ELSE 1 END), id ASC LIMIT 1",
                machineId, machineId, machineId
        );
        if (rows.isEmpty() && companyName != null && !companyName.isBlank()) {
            rows = jdbc.queryForList(
                    "SELECT id, company_name, tier, status, valid_until, remote_url, last_heartbeat_at, client_version, " +
                            "COALESCE(is_purchased, true) AS is_purchased, kicked_at, kick_reason " +
                            "FROM licensed_client_registry WHERE status = 'ACTIVE' AND UPPER(TRIM(company_name)) = UPPER(TRIM(?)) " +
                            "ORDER BY id ASC LIMIT 1",
                    companyName.trim()
            );
        }
        if (rows.isEmpty()) {
            return Map.of("status", "UNREGISTERED", "machineId", machineId, "isPurchased", false);
        }
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
        boolean isNewLogin = Boolean.TRUE.equals(user.get("isNewLogin"));
        boolean isActive = !isLogout && Boolean.TRUE.equals(user.getOrDefault("isActive", true));

        String workDateStr = String.valueOf(user.getOrDefault("workDate", "")).trim();
        java.time.LocalDate workDate;
        try {
            workDate = (!workDateStr.isBlank())
                    ? java.time.LocalDate.parse(workDateStr)
                    : java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        } catch (Exception ignored) {
            workDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        }

        String depMode = String.valueOf(user.getOrDefault("deploymentMode", user.getOrDefault("deployment_mode", "LOCAL"))).trim();
        if (depMode.isBlank()) depMode = "LOCAL";

        try {
            jdbc.update("""
                INSERT INTO client_user_telemetry (
                    machine_id, company_name, username, role_code, work_date,
                    first_login_today, last_logout_today, last_heartbeat,
                    total_seconds_today, is_active_now, deployment_mode, updated_at
                ) VALUES (
                    ?, ?, ?, ?, CAST(? AS date),
                    CURRENT_TIMESTAMP, CASE WHEN ? = true THEN CURRENT_TIMESTAMP ELSE NULL END, CURRENT_TIMESTAMP,
                    ?, ?, ?, CURRENT_TIMESTAMP
                ) ON CONFLICT (machine_id, username, work_date) DO UPDATE SET
                    first_login_today = COALESCE(client_user_telemetry.first_login_today, CURRENT_TIMESTAMP),
                    last_heartbeat = CURRENT_TIMESTAMP,
                    total_seconds_today = client_user_telemetry.total_seconds_today + EXCLUDED.total_seconds_today,
                    is_active_now = EXCLUDED.is_active_now,
                    deployment_mode = EXCLUDED.deployment_mode,
                    last_logout_today = CASE WHEN EXCLUDED.is_active_now = false THEN CURRENT_TIMESTAMP ELSE client_user_telemetry.last_logout_today END,
                    updated_at = CURRENT_TIMESTAMP
            """, machineId, companyName != null ? companyName : "Client Workspace", username, roleCode,
                    workDate.toString(),
                    isLogout,
                    durationInc, isActive, depMode);

            recordUserSessionSegment(machineId, companyName != null ? companyName : "Client Workspace", username, roleCode, workDate.toString(), durationInc, isActive, isLogout, isNewLogin, depMode);
        } catch (Exception ex) {
            log.warn("Could not record client user telemetry for {}: {}", username, ex.getMessage());
        }
    }

    private boolean hasSessionLogTable() {
        try {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = CURRENT_SCHEMA() AND table_name = 'client_user_session_log')",
                    Boolean.class
            );
            return Boolean.TRUE.equals(exists);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordUserSessionSegment(String machineId, String companyName, String username, String roleCode,
                                          String workDateStr, long durationInc, boolean isActive, boolean isLogout, boolean isNewLogin, String depMode) {
        if (!hasSessionLogTable()) {
            return;
        }
        try {
            jdbc.update("""
                UPDATE client_user_session_log
                SET is_active = FALSE
                WHERE machine_id = ? AND LOWER(username) = LOWER(?) AND work_date = CAST(? AS date)
                  AND is_active = TRUE
                  AND (? = TRUE OR session_end < CURRENT_TIMESTAMP - INTERVAL '5 minutes')
            """, machineId, username, workDateStr, isNewLogin);

            if (isNewLogin) {
                jdbc.update("""
                    INSERT INTO client_user_session_log (
                        machine_id, company_name, username, role_code, work_date,
                        session_start, session_end, duration_seconds, is_active, deployment_mode
                    ) VALUES (?, ?, ?, ?, CAST(? AS date), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                """, machineId, companyName != null ? companyName : "Client Workspace", username, roleCode, workDateStr, Math.max(0L, durationInc), isActive && !isLogout, depMode);
                return;
            }

            List<Map<String, Object>> openRows = jdbc.queryForList("""
                SELECT id FROM client_user_session_log
                WHERE machine_id = ? AND LOWER(username) = LOWER(?) AND work_date = CAST(? AS date)
                  AND is_active = TRUE
                  AND session_end >= CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                ORDER BY session_end DESC LIMIT 1
            """, machineId, username, workDateStr);

            if (!openRows.isEmpty() && openRows.getFirst().get("id") instanceof Number idNum) {
                jdbc.update("""
                    UPDATE client_user_session_log
                    SET session_end = CURRENT_TIMESTAMP,
                        duration_seconds = GREATEST(
                            COALESCE(duration_seconds, 0) + ?,
                            CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - session_start)) AS BIGINT)
                        ),
                        is_active = ?
                    WHERE id = ?
                """, Math.max(0L, durationInc), isActive && !isLogout, idNum.longValue());
            } else {
                jdbc.update("""
                    INSERT INTO client_user_session_log (
                        machine_id, company_name, username, role_code, work_date,
                        session_start, session_end, duration_seconds, is_active, deployment_mode
                    ) VALUES (?, ?, ?, ?, CAST(? AS date), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                """, machineId, companyName != null ? companyName : "Client Workspace", username, roleCode, workDateStr, Math.max(0L, durationInc), isActive && !isLogout, depMode);
            }
        } catch (Exception ex) {
            log.warn("Could not record user session segment for {}: {}", username, ex.getMessage());
        }
    }

    private void syncLocalWorkstationUsersIfHost(String machineId, String companyName) {
        try {
            String hostId = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            if (!hostId.equalsIgnoreCase(machineId)) {
                return;
            }
            String workDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
            String resolvedCompany = (companyName != null && !companyName.isBlank()) ? companyName : "Jasvi Client Workspace";
            jdbc.update("""
                INSERT INTO client_user_telemetry (
                    machine_id, company_name, username, role_code, work_date,
                    first_login_today, last_logout_today, last_heartbeat,
                    total_seconds_today, is_active_now, updated_at
                )
                SELECT ?, ?, u.username, COALESCE(u.role, 'USER'), CAST(? AS date),
                       NULL, NULL, CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                       0, false, CURRENT_TIMESTAMP
                FROM users u
                WHERE COALESCE(u.active, 1) = 1
                ON CONFLICT (machine_id, username, work_date) DO NOTHING
            """, machineId, resolvedCompany, workDate);
        } catch (Exception ignored) {
        }
    }

    private void relayHeartbeatToCentralCloudAsync(String machineId, String clientVersion, Integer activeUsers,
                                                   String remoteUrl, String companyName, List<Map<String, Object>> users) {
        if (users != null && users.stream().anyMatch(u -> Boolean.TRUE.equals(u.get("relayedFromLocal")))) {
            return;
        }
        String masterUrl = System.getProperty("jasvi.licensing.master-url",
                System.getenv().getOrDefault("JASVI_LICENSING_MASTER_URL", "https://erp.jasviindustries.in")).trim();
        if (masterUrl.isBlank()) return;
        while (masterUrl.endsWith("/")) {
            masterUrl = masterUrl.substring(0, masterUrl.length() - 1);
        }
        String hostId = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
        if (!hostId.equalsIgnoreCase(machineId)) {
            return;
        }
        final String targetMasterUrl = masterUrl;
        Thread.ofVirtual().name("central-licensor-heartbeat-relay").start(() -> {
            try {
                List<Map<String, Object>> enrichedUsers = new ArrayList<>();
                if (users != null) {
                    for (Map<String, Object> u : users) {
                        Map<String, Object> copy = new LinkedHashMap<>(u);
                        copy.put("relayedFromLocal", true);
                        enrichedUsers.add(copy);
                    }
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("machineId", machineId);
                if (clientVersion != null) payload.put("clientVersion", clientVersion);
                if (activeUsers != null) payload.put("activeUsers", activeUsers);
                if (remoteUrl != null) payload.put("remoteUrl", remoteUrl);
                if (companyName != null) payload.put("companyName", companyName);
                payload.put("users", enrichedUsers);

                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(targetMasterUrl + "/api/license/heartbeat"))
                        .timeout(Duration.ofSeconds(4))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                        .build();
                http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        });
    }

    private void purgeExpiredTelemetry() {
        try {
            jdbc.update("DELETE FROM client_user_telemetry WHERE work_date < CURRENT_DATE - INTERVAL '30 days'");
        } catch (Exception e) {
            log.warn("Could not purge expired telemetry: {}", e.getMessage());
        }
    }

    @Transactional
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine) {
        return getClientUserTelemetry(rawMachine, null, null, null, "DAILY");
    }

    @Transactional
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine, String targetDateStr) {
        return getClientUserTelemetry(rawMachine, targetDateStr, null, null, "DAILY");
    }

    @Transactional
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine, String targetDateStr, String fromStr, String toStr) {
        return getClientUserTelemetry(rawMachine, targetDateStr, fromStr, toStr, "DAILY");
    }

    @Transactional
    public List<Map<String, Object>> getClientUserTelemetry(String rawMachine, String targetDateStr, String fromStr, String toStr, String viewMode) {
        if (rawMachine == null || rawMachine.isBlank()) return List.of();
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        syncLocalWorkstationUsersIfHost(machineId, null);

        String resolvedCompany = null;
        try {
            List<String> compNames = jdbc.query(
                    "SELECT company_name FROM licensed_client_registry WHERE machine_id = ? OR machine_id = ? LIMIT 1",
                    (rs, idx) -> rs.getString(1),
                    machineId, rawMachine
            );
            if (!compNames.isEmpty() && compNames.getFirst() != null && !compNames.getFirst().isBlank()) {
                resolvedCompany = compNames.getFirst().trim();
            }
        } catch (Exception ignored) {}

        java.time.LocalDate businessToday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String todayIst = businessToday.toString();
        String from = fromStr != null && !fromStr.isBlank()
                ? fromStr.trim()
                : (targetDateStr != null && !targetDateStr.isBlank() && !"TODAY".equalsIgnoreCase(targetDateStr) ? targetDateStr.trim() : todayIst);
        String to = toStr != null && !toStr.isBlank() ? toStr.trim() : from;
        if (from.compareTo(to) > 0) {
            String tmp = from;
            from = to;
            to = tmp;
        }

        boolean summaryMode = "SUMMARY".equalsIgnoreCase(viewMode != null ? viewMode.trim() : "");
        boolean hasSessionLog = hasSessionLogTable();

        if (summaryMode) {
            if (hasSessionLog) {
                return jdbc.queryForList("""
                    SELECT t.username,
                           t.role_code,
                           MIN(t.first_login_today) AS first_login_today,
                           MAX(COALESCE(t.last_logout_today, t.last_heartbeat)) AS last_logout_today,
                           MAX(t.last_heartbeat) AS last_heartbeat,
                           SUM(COALESCE(t.total_seconds_today, 0)) AS total_seconds_today,
                           BOOL_OR(t.work_date = CAST(? AS date) AND COALESCE(t.is_active_now, false) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active_now,
                           COUNT(DISTINCT CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN t.work_date END) AS days_active,
                           COALESCE(MAX(sl.total_sessions), COUNT(DISTINCT CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN t.work_date END)) AS session_count,
                           COALESCE(u.locked, 0) AS is_locked,
                           COALESCE(u.active, 1) AS is_user_active,
                           MAX(COALESCE(t.deployment_mode, 'LOCAL')) AS deployment_mode
                    FROM client_user_telemetry t
                    LEFT JOIN users u ON LOWER(u.username) = LOWER(t.username)
                    LEFT JOIN (
                        SELECT machine_id, LOWER(username) AS uname, COUNT(*) AS total_sessions
                        FROM client_user_session_log
                        WHERE (machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                          AND work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                        GROUP BY machine_id, LOWER(username)
                    ) sl ON sl.machine_id = t.machine_id AND sl.uname = LOWER(t.username)
                    WHERE (t.machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                      AND t.work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                      AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL OR t.work_date = CAST(? AS date))
                    GROUP BY t.username, t.role_code, u.locked, u.active
                    ORDER BY is_active_now DESC, total_seconds_today DESC, last_heartbeat DESC, t.username ASC
                """, todayIst, machineId, resolvedCompany, resolvedCompany, from, to, machineId, resolvedCompany, resolvedCompany, from, to, todayIst);
            }
            return jdbc.queryForList("""
                SELECT t.username,
                       t.role_code,
                       MIN(t.first_login_today) AS first_login_today,
                       MAX(COALESCE(t.last_logout_today, t.last_heartbeat)) AS last_logout_today,
                       MAX(t.last_heartbeat) AS last_heartbeat,
                       SUM(COALESCE(t.total_seconds_today, 0)) AS total_seconds_today,
                       BOOL_OR(t.work_date = CAST(? AS date) AND COALESCE(t.is_active_now, false) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active_now,
                       COUNT(DISTINCT CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN t.work_date END) AS days_active,
                       COUNT(DISTINCT CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN t.work_date END) AS session_count,
                       COALESCE(u.locked, 0) AS is_locked,
                       COALESCE(u.active, 1) AS is_user_active,
                       MAX(COALESCE(t.deployment_mode, 'LOCAL')) AS deployment_mode
                FROM client_user_telemetry t
                LEFT JOIN users u ON LOWER(u.username) = LOWER(t.username)
                WHERE (t.machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                  AND t.work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                  AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL OR t.work_date = CAST(? AS date))
                GROUP BY t.username, t.role_code, u.locked, u.active
                ORDER BY is_active_now DESC, total_seconds_today DESC, last_heartbeat DESC, t.username ASC
            """, todayIst, machineId, resolvedCompany, resolvedCompany, from, to, todayIst);
        }

        if (hasSessionLog) {
            return jdbc.queryForList("""
                SELECT t.id, t.machine_id, t.company_name, t.username, t.role_code, t.work_date,
                       t.first_login_today,
                       COALESCE(t.last_logout_today, t.last_heartbeat) AS last_logout_today,
                       t.last_heartbeat,
                       COALESCE(t.total_seconds_today, 0) AS total_seconds_today,
                       (t.work_date = CAST(? AS date) AND COALESCE(t.is_active_now, false) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active_now,
                       CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN 1 ELSE 0 END AS days_active,
                       GREATEST(COALESCE(sl.session_count, 0), CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN 1 ELSE 0 END) AS session_count,
                       COALESCE(u.locked, 0) AS is_locked,
                       COALESCE(u.active, 1) AS is_user_active,
                       COALESCE(t.deployment_mode, 'LOCAL') AS deployment_mode
                FROM client_user_telemetry t
                LEFT JOIN users u ON LOWER(u.username) = LOWER(t.username)
                LEFT JOIN (
                    SELECT machine_id, LOWER(username) AS uname, work_date, COUNT(*) AS session_count
                    FROM client_user_session_log
                    WHERE (machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                      AND work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                    GROUP BY machine_id, LOWER(username), work_date
                ) sl ON sl.machine_id = t.machine_id AND sl.uname = LOWER(t.username) AND sl.work_date = t.work_date
                WHERE (t.machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                  AND t.work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                  AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL OR t.work_date = CAST(? AS date))
                ORDER BY t.work_date DESC, is_active_now DESC, COALESCE(t.total_seconds_today, 0) DESC, t.last_heartbeat DESC, t.username ASC
            """, todayIst, machineId, resolvedCompany, resolvedCompany, from, to, machineId, resolvedCompany, resolvedCompany, from, to, todayIst);
        }

        return jdbc.queryForList("""
            SELECT t.id, t.machine_id, t.company_name, t.username, t.role_code, t.work_date,
                   t.first_login_today,
                   COALESCE(t.last_logout_today, t.last_heartbeat) AS last_logout_today,
                   t.last_heartbeat,
                   COALESCE(t.total_seconds_today, 0) AS total_seconds_today,
                   (t.work_date = CAST(? AS date) AND COALESCE(t.is_active_now, false) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active_now,
                   CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN 1 ELSE 0 END AS days_active,
                   CASE WHEN COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL THEN 1 ELSE 0 END AS session_count,
                   COALESCE(u.locked, 0) AS is_locked,
                   COALESCE(u.active, 1) AS is_user_active,
                   COALESCE(t.deployment_mode, 'LOCAL') AS deployment_mode
            FROM client_user_telemetry t
            LEFT JOIN users u ON LOWER(u.username) = LOWER(t.username)
            WHERE (t.machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
              AND t.work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
              AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL OR t.work_date = CAST(? AS date))
            ORDER BY t.work_date DESC, is_active_now DESC, COALESCE(t.total_seconds_today, 0) DESC, t.last_heartbeat DESC, t.username ASC
        """, todayIst, machineId, resolvedCompany, resolvedCompany, from, to, todayIst);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getClientUserDailyBreakdown(String rawMachine, String username, String fromStr, String toStr) {
        if (rawMachine == null || rawMachine.isBlank() || username == null || username.isBlank()) {
            return List.of();
        }
        String machineId = rawMachine.replace("-", "").toUpperCase(Locale.ROOT);
        String cleanUsername = username.trim();

        String resolvedCompany = null;
        try {
            List<String> compNames = jdbc.query(
                    "SELECT company_name FROM licensed_client_registry WHERE machine_id = ? OR machine_id = ? LIMIT 1",
                    (rs, idx) -> rs.getString(1),
                    machineId, rawMachine
            );
            if (!compNames.isEmpty() && compNames.getFirst() != null && !compNames.getFirst().isBlank()) {
                resolvedCompany = compNames.getFirst().trim();
            }
        } catch (Exception ignored) {}

        java.time.LocalDate businessToday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String todayIst = businessToday.toString();
        String from = (fromStr != null && !fromStr.isBlank()) ? fromStr.trim() : businessToday.minusDays(30).toString();
        String to = (toStr != null && !toStr.isBlank()) ? toStr.trim() : todayIst;
        if (from.compareTo(to) > 0) {
            String tmp = from;
            from = to;
            to = tmp;
        }

        List<Map<String, Object>> dailyRows = jdbc.queryForList("""
            SELECT t.work_date,
                   t.username,
                   t.role_code,
                   t.first_login_today,
                   t.last_logout_today,
                   t.last_heartbeat,
                   COALESCE(t.last_logout_today, t.last_heartbeat) AS effective_end_time,
                   COALESCE(t.total_seconds_today, 0) AS total_seconds_today,
                   (t.work_date = CAST(? AS date) AND COALESCE(t.is_active_now, false) AND t.last_heartbeat >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active_now
            FROM client_user_telemetry t
            WHERE (t.machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(t.company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
              AND LOWER(t.username) = LOWER(?)
              AND t.work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
              AND (COALESCE(t.total_seconds_today, 0) > 0 OR t.first_login_today IS NOT NULL)
            ORDER BY t.work_date DESC
        """, todayIst, machineId, resolvedCompany, resolvedCompany, cleanUsername, from, to);

        Map<String, List<Map<String, Object>>> segmentsByDate = new LinkedHashMap<>();
        if (hasSessionLogTable()) {
            List<Map<String, Object>> allSegments = jdbc.queryForList("""
                SELECT work_date,
                       session_start,
                       session_end,
                       COALESCE(duration_seconds, 0) AS duration_seconds,
                       (work_date = CAST(? AS date) AND COALESCE(is_active, false) AND session_end >= CURRENT_TIMESTAMP - INTERVAL '3 minutes') AS is_active
                FROM client_user_session_log
                WHERE (machine_id = ? OR (CAST(? AS varchar) IS NOT NULL AND UPPER(TRIM(COALESCE(company_name, ''))) = UPPER(TRIM(CAST(? AS varchar)))))
                  AND LOWER(username) = LOWER(?)
                  AND work_date BETWEEN CAST(? AS date) AND CAST(? AS date)
                ORDER BY work_date DESC, session_start ASC
            """, todayIst, machineId, resolvedCompany, resolvedCompany, cleanUsername, from, to);

            for (Map<String, Object> seg : allSegments) {
                String dateKey = String.valueOf(seg.get("work_date"));
                segmentsByDate.computeIfAbsent(dateKey, k -> new ArrayList<>()).add(seg);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> dayRow : dailyRows) {
            String workDate = String.valueOf(dayRow.get("work_date"));
            List<Map<String, Object>> segments = segmentsByDate.getOrDefault(workDate, List.of());
            if (!segments.isEmpty()) {
                int sessionNum = 1;
                for (Map<String, Object> seg : segments) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("session_number", sessionNum++);
                    entry.put("work_date", dayRow.get("work_date"));
                    entry.put("username", dayRow.get("username"));
                    entry.put("role_code", dayRow.get("role_code"));
                    entry.put("session_start", seg.get("session_start"));
                    entry.put("session_end", seg.get("session_end"));
                    entry.put("first_login_today", seg.get("session_start"));
                    entry.put("effective_end_time", seg.get("session_end"));
                    entry.put("duration_seconds", seg.get("duration_seconds"));
                    entry.put("total_seconds_today", seg.get("duration_seconds"));
                    entry.put("day_total_seconds", dayRow.get("total_seconds_today"));
                    boolean activeSeg = Boolean.TRUE.equals(seg.get("is_active")) && Boolean.TRUE.equals(dayRow.get("is_active_now"));
                    entry.put("is_active_now", activeSeg);
                    result.add(entry);
                }
            } else {
                Map<String, Object> fallback = new LinkedHashMap<>(dayRow);
                fallback.put("session_number", 1);
                fallback.put("session_start", dayRow.get("first_login_today"));
                fallback.put("session_end", dayRow.get("effective_end_time"));
                fallback.put("duration_seconds", dayRow.get("total_seconds_today"));
                fallback.put("day_total_seconds", dayRow.get("total_seconds_today"));
                result.add(fallback);
            }
        }
        return result;
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

    @Transactional
    public void sendRogueClientSecurityAlerts(String machineId, String companyName, String clientVersion, String clientIp, List<Map<String, Object>> users) {
        if (machineId == null || machineId.isBlank()) return;

        // Skip sending rogue security alerts when operating in LOCAL / development mode, unless explicitly triggered for test
        String env = deploymentEnv != null ? deploymentEnv.trim().toUpperCase(Locale.ROOT) : "LOCAL";
        boolean isExplicitTest = machineId != null && machineId.startsWith("TEST-ROGUE-");
        if (!isExplicitTest && ("LOCAL".equals(env) || "DEV".equals(env) || "TEST".equals(env))) {
            log.debug("Suppressing rogue client alert in {} environment for machine {}", env, machineId);
            return;
        }

        // In-memory 24-hour debounce check: prevents spamming even if machine is not yet recorded in DB
        Instant lastMemoryAlert = rogueAlertDebounceMap.get(machineId);
        if (lastMemoryAlert != null && Duration.between(lastMemoryAlert, Instant.now()).toHours() < 24) {
            log.debug("Rogue client alert throttled by in-memory 24h cooldown for machine {}", machineId);
            return;
        }

        // Database debounce check: only send 1 alert per machine per 24 hours
        try {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT last_alert_sent_at FROM licensed_client_registry WHERE machine_id = ?", machineId
            );
            if (!existing.isEmpty() && existing.getFirst().get("last_alert_sent_at") != null) {
                Instant lastAlert = toInstant(existing.getFirst().get("last_alert_sent_at"));
                if (lastAlert != null && lastAlert.isAfter(Instant.now().minus(24, ChronoUnit.HOURS))) {
                    rogueAlertDebounceMap.put(machineId, lastAlert);
                    return; // debounced within 24h
                }
            }
        } catch (Exception ignored) {}

        rogueAlertDebounceMap.put(machineId, Instant.now());

        String token = java.util.UUID.randomUUID().toString().replace("-", "") + Long.toHexString(System.currentTimeMillis());

        try {
            jdbc.update(
                    "INSERT INTO license_revocation_token (token, machine_id, company_name, client_ip, expires_at) " +
                            "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '72 hours')",
                    token, machineId, (companyName != null && !companyName.isBlank()) ? companyName : "Unregistered Client",
                    clientIp != null ? clientIp : "Unknown IP"
            );
            jdbc.update("UPDATE licensed_client_registry SET last_alert_sent_at = CURRENT_TIMESTAMP WHERE machine_id = ?", machineId);
        } catch (Exception ex) {
            log.warn("Could not record revocation token: {}", ex.getMessage());
        }

        String baseUrl = resolveEffectiveServerPublicUrl();
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
        // Dynamically resolve Master Phone from Tab 1 (primary client in licensed_client_registry)
        String superAdminPhone = null;
        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            superAdminPhone = jdbc.queryForObject(
                    "SELECT phone FROM licensed_client_registry " +
                            "WHERE (UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' OR UPPER(REPLACE(machine_id, '-', '')) = ?) " +
                            "AND phone IS NOT NULL AND BTRIM(phone) <> '' ORDER BY id ASC LIMIT 1",
                    String.class, hostClean
            );
        } catch (Exception ignored) {}

        if (superAdminPhone == null || superAdminPhone.isBlank()) {
            superAdminPhone = getSetting("licensing.superadmin_whatsapp", "");
        }
        if (superAdminPhone == null || superAdminPhone.isBlank()) {
            try {
                superAdminPhone = jdbc.queryForObject(
                        "SELECT phone FROM users WHERE (LOWER(username) = 'superadmin' OR UPPER(COALESCE(role,'')) = 'SUPER_ADMIN') " +
                                "AND phone IS NOT NULL AND BTRIM(phone) <> '' ORDER BY id ASC LIMIT 1",
                        String.class
                );
            } catch (Exception ignored) {}
        }
        if (superAdminPhone == null || superAdminPhone.isBlank()) {
            superAdminPhone = getSetting("licensing.support_phone", "");
        }
        if (superAdminPhone == null || superAdminPhone.isBlank()) return;

        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String supportBrand = getSetting("licensing.issuer_brand", templates.companyName());
        String text = templates.renderWhatsapp(
                "rogue_alert",
                Map.of(
                        "CompanyName", company,
                        "MachineId", machineId,
                        "ClientIp", ip,
                        "Users", users,
                        "ClientVersion", version,
                        "KickUrl", kickUrl,
                        "SupportBrand", supportBrand
                )
        );

        try {
            whatsappService.sendText(new org.example.server.whatsapp.WahaDtos.TextSendRequest(superAdminPhone, text, null));
            log.info("Rogue instance security alert dispatched to Super Admin WhatsApp ({}) for machine {}", superAdminPhone, machineId);
        } catch (Exception e) {
            log.warn("Failed to dispatch rogue instance WhatsApp alert: {}", e.getMessage());
        }
    }

    private void dispatchSuperAdminEmailAlert(String machineId, String company, String ip, String version, String users, String kickUrl) {
        if (mailService == null || !mailService.isConfigured()) return;
        // Dynamically resolve Master Email from Tab 1 (primary client in licensed_client_registry)
        String superAdminEmail = null;
        try {
            String hostClean = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").toUpperCase(Locale.ROOT);
            superAdminEmail = jdbc.queryForObject(
                    "SELECT email FROM licensed_client_registry " +
                            "WHERE (UPPER(TRIM(company_name)) = 'JASVI INDUSTRIES' OR UPPER(REPLACE(machine_id, '-', '')) = ?) " +
                            "AND email IS NOT NULL AND BTRIM(email) <> '' ORDER BY id ASC LIMIT 1",
                    String.class, hostClean
            );
        } catch (Exception ignored) {}

        if (superAdminEmail == null || superAdminEmail.isBlank()) {
            superAdminEmail = getSetting("licensing.superadmin_email", "").trim();
        }
        if (superAdminEmail == null || superAdminEmail.isBlank()) {
            try {
                String queried = jdbc.queryForObject(
                        "SELECT email FROM users WHERE (LOWER(username) = 'superadmin' OR UPPER(COALESCE(role,'')) = 'SUPER_ADMIN') " +
                                "AND email IS NOT NULL AND BTRIM(email) <> '' ORDER BY id ASC LIMIT 1",
                        String.class
                );
                if (queried != null && !queried.isBlank()) {
                    superAdminEmail = queried.trim();
                }
            } catch (Exception ignored) {}
        }
        if (superAdminEmail == null || superAdminEmail.isBlank()) return;

        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String supportBrand = getSetting("licensing.issuer_brand", templates.companyName());
        var rendered = templates.renderEmail(
                "rogue_alert",
                Map.of(
                        "CompanyName", company,
                        "MachineId", machineId,
                        "ClientIp", ip,
                        "Users", users,
                        "ClientVersion", version,
                        "KickUrl", kickUrl,
                        "SupportBrand", supportBrand
                ),
                new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                        "#dc2626", "#991b1b",
                        "CRITICAL SECURITY ALERT • UNLICENSED INSTANCE",
                        "🚨 Unlicensed Workstation Detected",
                        List.of(new org.example.shared.communication.TokenTemplateEngine.ActionButton(
                                "🛑 INSTANT KICK & BLOCK THIS INSTANCE", kickUrl, "#dc2626")),
                        supportBrand + " Platform Security"
                )
        );

        try {
            mailService.sendBusiness(superAdminEmail, rendered.subject(), rendered.body());
            log.info("Rogue instance security alert emailed to Super Admin ({}) for machine {}", superAdminEmail, machineId);
        } catch (Exception e) {
            log.warn("Failed to dispatch rogue instance email alert: {}", e.getMessage());
        }
    }

    @Transactional
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
                        "kick_reason = 'Terminated by Super Admin via Email/WhatsApp 1-click alert' " +
                        "WHERE machine_id = ? OR UPPER(REPLACE(machine_id, '-', '')) = UPPER(REPLACE(?, '-', ''))",
                targetMachine, targetMachine
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
                var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
                String issuerBrand = getSetting("licensing.issuer_brand", templates.companyName() + " ERP");
                String supportEmail = templates.companySupportEmail();
                String baseName = company.replaceAll("[^a-zA-Z0-9_-]", "_");
                String filenameJlic = baseName + "-license.jlic";
                String filenameJson = baseName + "-license.json";
                byte[] jlicBytes = payload.getBytes(StandardCharsets.UTF_8);

                var rendered = templates.renderEmail(
                        "license_activation",
                        Map.of(
                                "CompanyName", company,
                                "Tier", tier,
                                "MachineId", machineId,
                                "ValidUntil", validUntil,
                                "JlicFilename", filenameJlic,
                                "JsonFilename", filenameJson,
                                "IssuerBrand", issuerBrand,
                                "SupportEmail", supportEmail
                        ),
                        new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                                "#0284c7", "#0369a1",
                                "Official Digital License Delivery",
                                issuerBrand + " Enterprise License Activation",
                                List.of(),
                                issuerBrand + (supportEmail.isBlank() ? "" : " • Support: " + supportEmail)
                        )
                );

                var attJlic = new org.example.server.auth.SmtpMailService.Attachment(filenameJlic, "application/octet-stream", jlicBytes);
                var attJson = new org.example.server.auth.SmtpMailService.Attachment(filenameJson, "application/json", jlicBytes);
                mailService.sendBusiness(email, rendered.subject(), rendered.body(), List.of(attJlic, attJson));
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

        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String issuerBrand = getSetting("licensing.issuer_brand", templates.companyName() + " ERP");
        Map<String, String> waTokens = Map.of(
                "CompanyName", company,
                "Tier", tier,
                "MachineId", machineId,
                "ValidUntil", validUntil,
                "JlicFilename", filenameJlic,
                "JsonFilename", filenameJson,
                "IssuerBrand", issuerBrand,
                "SupportEmail", templates.companySupportEmail()
        );
        String caption = templates.renderWhatsapp("license_activation", waTokens);

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
