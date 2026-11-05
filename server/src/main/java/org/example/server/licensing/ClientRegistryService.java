package org.example.server.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.shared.licensing.Ed25519CryptoUtil;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.shared.licensing.LicensePayload;
import org.example.server.persistence.JpaNativeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    public ClientRegistryService(JpaNativeRepository jdbc) {
        this.jdbc = jdbc;
        initDefaultClientsIfEmpty();
    }

    private void initDefaultClientsIfEmpty() {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM licensed_client_registry", Integer.class);
            if (count == null || count == 0) {
                // Seed anchor client
                registerClient(Map.of(
                        "companyName", "JASVI INDUSTRIES",
                        "machineId", "F12D-C2C0-AADE-15EB",
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
                "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version " +
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
                        "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version " +
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
        int days = req.get("days") instanceof Number n ? n.intValue() : 365;
        if (days <= 0) days = 365;

        Instant now = Instant.now();
        Instant validUntil = now.plus(days, ChronoUnit.DAYS);

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
                List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP")
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
                        "tier, max_users, annual_fee, issued_at, valid_until, status, license_payload_enc, notes, row_version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 'ACTIVE', ?, ?, 1) " +
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
                notes
        );

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
        LicensePayload payload = new LicensePayload(
                licenseKey,
                companyName,
                machineId,
                tier,
                now.minus(1, ChronoUnit.DAYS),
                newExpiry,
                14,
                maxUsers,
                List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP")
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
                        "row_version = row_version + 1 " +
                        "WHERE id = ?",
                java.util.Date.from(newExpiry),
                payloadJson,
                id
        );

        return getClientById(id);
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
            PrivateKey privateKey = Ed25519CryptoUtil.loadPrivateKey(DEFAULT_PRIV_KEY);
            return Ed25519CryptoUtil.signString(payload.toCanonicalString(), privateKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign license with master private key", e);
        }
    }
}
