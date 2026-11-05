package org.example.server.licensing;

import org.example.server.persistence.JpaNativeRepository;
import org.example.shared.licensing.Ed25519CryptoUtil;
import org.example.shared.licensing.HardwareFingerprintUtil;
import org.example.shared.licensing.LicensePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LicenseVerifierService {

    private static final Logger log = LoggerFactory.getLogger(LicenseVerifierService.class);

    public enum Status {
        ACTIVE,
        EXPIRING_SOON,
        EXPIRED,
        CLOCK_TAMPERED,
        MACHINE_MISMATCH,
        INVALID_SIGNATURE,
        UNLICENSED
    }

    public record LicenseStatus(
            Status status,
            String licenseKey,
            String companyName,
            String machineId,
            String currentMachineId,
            String tier,
            Instant validUntil,
            long daysRemaining,
            boolean isWriteAllowed,
            String message
    ) {}

    private final JpaNativeRepository jdbc;
    private final boolean bypassInDev;
    private final AtomicReference<LicenseStatus> cachedStatus = new AtomicReference<>();

    public LicenseVerifierService(
            JpaNativeRepository jdbc,
            @Value("${jasvi.licensing.bypass:false}") boolean bypassInDev
    ) {
        this.jdbc = jdbc;
        this.bypassInDev = bypassInDev;
    }

    @Transactional(readOnly = true)
    public LicenseStatus checkLicense() {
        if (bypassInDev) {
            return new LicenseStatus(
                    Status.ACTIVE,
                    "DEV-BYPASS-ACTIVE",
                    "Jasvi Development Workspace",
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    "GOLD",
                    Instant.now().plus(365, ChronoUnit.DAYS),
                    365,
                    true,
                    "Development bypass active"
            );
        }

        // Check Anti-Clock Tampering Guard
        if (isClockTampered()) {
            return new LicenseStatus(
                    Status.CLOCK_TAMPERED,
                    "TAMPER-DETECTED",
                    "N/A",
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    "NONE",
                    Instant.now(),
                    0,
                    false,
                    "System clock has been set backward! Please correct your computer date and time."
            );
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT license_key, company_name, machine_hash, tier, valid_from, valid_until, " +
                        "grace_period_days, max_users, features, signature, canonical_payload, status " +
                        "FROM app_license ORDER BY id DESC LIMIT 1"
        );

        if (rows.isEmpty()) {
            return new LicenseStatus(
                    Status.ACTIVE,
                    "JASVI-EVALUATION-TRIAL",
                    "Evaluation Copy (Jasvi Industries)",
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    "EVALUATION",
                    Instant.now().plus(30, ChronoUnit.DAYS),
                    30,
                    true,
                    "30-day software evaluation active. Please register your license."
            );
        }

        Map<String, Object> row = rows.getFirst();
        String licenseKey = String.valueOf(row.get("license_key"));
        String companyName = String.valueOf(row.get("company_name"));
        String machineHash = String.valueOf(row.get("machine_hash"));
        String tier = String.valueOf(row.get("tier"));
        String signature = String.valueOf(row.get("signature"));
        String canonicalPayload = String.valueOf(row.get("canonical_payload"));
        Instant validUntil = toInstant(row.get("valid_until"));
        int gracePeriodDays = row.get("grace_period_days") instanceof Number n ? n.intValue() : 14;

        // 1. Cryptographic Signature Verification
        boolean sigValid = Ed25519CryptoUtil.verifyStringWithEmbeddedKey(canonicalPayload, signature);
        if (!sigValid) {
            log.error("[LicenseVerifier] Cryptographic signature check FAILED for key: {}", licenseKey);
            return new LicenseStatus(
                    Status.INVALID_SIGNATURE,
                    licenseKey,
                    companyName,
                    "INVALID",
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    tier,
                    validUntil,
                    0,
                    false,
                    "License file signature is corrupted or has been tampered with."
            );
        }

        // 2. Machine Hardware Fingerprint Verification
        if (!isMachineMatch(machineHash)) {
            log.warn("[LicenseVerifier] Machine mismatch. Expected {}, Current {}", machineHash, HardwareFingerprintUtil.getMachineHash());
            return new LicenseStatus(
                    Status.MACHINE_MISMATCH,
                    licenseKey,
                    companyName,
                    formatDisplayId(machineHash),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    tier,
                    validUntil,
                    0,
                    false,
                    "This license is locked to another computer. Machine ID mismatch."
            );
        }

        // 3. Expiration Check with Grace Period
        Instant now = Instant.now();
        long daysRemaining = ChronoUnit.DAYS.between(now, validUntil);
        Instant finalDeadline = validUntil.plus(gracePeriodDays, ChronoUnit.DAYS);

        if (now.isAfter(finalDeadline)) {
            return new LicenseStatus(
                    Status.EXPIRED,
                    licenseKey,
                    companyName,
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    tier,
                    validUntil,
                    daysRemaining,
                    false,
                    "License expired. Please renew to continue creating invoices."
            );
        }

        if (daysRemaining <= 14) {
            return new LicenseStatus(
                    Status.EXPIRING_SOON,
                    licenseKey,
                    companyName,
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    HardwareFingerprintUtil.getDisplayMachineId(),
                    tier,
                    validUntil,
                    daysRemaining,
                    true,
                    "Your license expires in " + Math.max(0, daysRemaining) + " days. Please renew."
            );
        }

        return new LicenseStatus(
                Status.ACTIVE,
                licenseKey,
                companyName,
                HardwareFingerprintUtil.getDisplayMachineId(),
                HardwareFingerprintUtil.getDisplayMachineId(),
                tier,
                validUntil,
                daysRemaining,
                true,
                "License is active and valid."
        );
    }

    @Transactional
    public boolean activateLicense(LicensePayload payload, String signature) {
        String canonical = payload.toCanonicalString();
        boolean valid = Ed25519CryptoUtil.verifyStringWithEmbeddedKey(canonical, signature);
        if (!valid) {
            throw new IllegalArgumentException("Invalid cryptographic signature on license payload.");
        }

        if (!isMachineMatch(payload.machineHash())) {
            throw new IllegalArgumentException(
                    "Hardware mismatch: License is locked to machine ID " + formatDisplayId(payload.machineHash()) +
                    ", but this computer's machine ID is " + HardwareFingerprintUtil.getDisplayMachineId() +
                    ". Please generate or request a license matching this computer's Machine ID."
            );
        }

        jdbc.update(
                "INSERT INTO app_license (license_key, company_name, machine_hash, tier, valid_from, valid_until, " +
                        "grace_period_days, max_users, features, signature, canonical_payload, last_verified_at, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'ACTIVE') " +
                        "ON CONFLICT (license_key) DO UPDATE SET " +
                        "company_name = EXCLUDED.company_name, " +
                        "machine_hash = EXCLUDED.machine_hash, " +
                        "tier = EXCLUDED.tier, " +
                        "valid_from = EXCLUDED.valid_from, " +
                        "valid_until = EXCLUDED.valid_until, " +
                        "grace_period_days = EXCLUDED.grace_period_days, " +
                        "max_users = EXCLUDED.max_users, " +
                        "features = EXCLUDED.features, " +
                        "signature = EXCLUDED.signature, " +
                        "canonical_payload = EXCLUDED.canonical_payload, " +
                        "last_verified_at = CURRENT_TIMESTAMP, " +
                        "status = 'ACTIVE'",
                payload.licenseKey(),
                payload.companyName(),
                payload.machineHash().toUpperCase(),
                payload.tier(),
                payload.validFrom(),
                payload.validUntil(),
                payload.gracePeriodDays(),
                payload.maxUsers(),
                String.join(",", payload.features()),
                signature,
                canonical
        );

        cachedStatus.set(null);
        return true;
    }

    public boolean isMachineMatch(String machineHash) {
        if (machineHash == null || machineHash.isBlank()) return false;
        String currentMachineHash = HardwareFingerprintUtil.getMachineHash();
        String currentDisplayId = HardwareFingerprintUtil.getDisplayMachineId().replace("-", "").trim();
        String targetClean = machineHash.replace("-", "").trim();
        return currentMachineHash.equalsIgnoreCase(machineHash)
                || currentMachineHash.equalsIgnoreCase(targetClean)
                || currentDisplayId.equalsIgnoreCase(targetClean)
                || currentMachineHash.toUpperCase(java.util.Locale.ROOT).startsWith(targetClean.toUpperCase(java.util.Locale.ROOT))
                || targetClean.toUpperCase(java.util.Locale.ROOT).startsWith(currentDisplayId.toUpperCase(java.util.Locale.ROOT))
                || currentDisplayId.toUpperCase(java.util.Locale.ROOT).startsWith(targetClean.toUpperCase(java.util.Locale.ROOT));
    }

    private boolean isClockTampered() {
        try {
            // Check max invoice date
            String maxCreatedAt = jdbc.queryForObject(
                    "SELECT MAX(created_at) FROM sales_header", String.class
            );
            if (maxCreatedAt != null && !maxCreatedAt.isBlank()) {
                try {
                    Instant maxInvoiceDate = Instant.parse(maxCreatedAt);
                    if (Instant.now().isBefore(maxInvoiceDate.minus(2, ChronoUnit.HOURS))) {
                        log.error("[LicenseVerifier] Clock tampering detected! Now: {}, MaxInvoice: {}", Instant.now(), maxInvoiceDate);
                        return true;
                    }
                } catch (Exception parseErr) {
                    // Ignore legacy date formats
                }
            }
        } catch (Exception ignored) {
            // If table does not exist or is empty
        }
        return false;
    }

    private static Instant toInstant(Object obj) {
        if (obj instanceof Instant i) return i;
        if (obj instanceof java.util.Date d) return d.toInstant();
        if (obj instanceof Number num) return Instant.ofEpochMilli(num.longValue());
        if (obj instanceof java.time.temporal.TemporalAccessor ta) return Instant.from(ta);
        return Instant.now();
    }

    private static String formatDisplayId(String hash) {
        if (hash == null || hash.length() < 16) return "JASVI-UNASSIGNED";
        return String.format("%s-%s-%s-%s", hash.substring(0, 4), hash.substring(4, 8), hash.substring(8, 12), hash.substring(12, 16));
    }
}
