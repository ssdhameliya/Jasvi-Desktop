package org.example.shared.licensing;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable canonical contract representing an application license.
 */
public record LicensePayload(
        String licenseKey,
        String companyName,
        String machineHash,
        String tier,             // "SILVER", "GOLD", "ENTERPRISE"
        Instant validFrom,
        Instant validUntil,
        int gracePeriodDays,     // default: 14 days
        int maxUsers,            // default: 1 for Silver, 5+ for Gold
        List<String> features    // e.g. ["GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP"]
) implements Serializable {

    public LicensePayload {
        Objects.requireNonNull(licenseKey, "licenseKey must not be null");
        Objects.requireNonNull(companyName, "companyName must not be null");
        Objects.requireNonNull(machineHash, "machineHash must not be null");
        tier = (tier == null || tier.isBlank()) ? "SILVER" : tier.trim().toUpperCase();
        validFrom = validFrom == null ? Instant.now() : validFrom;
        validUntil = validUntil == null ? validFrom.plusSeconds(365L * 24 * 3600) : validUntil;
        gracePeriodDays = gracePeriodDays <= 0 ? 14 : gracePeriodDays;
        maxUsers = maxUsers <= 0 ? 1 : maxUsers;
        features = features == null ? Collections.emptyList() : List.copyOf(features);
    }

    /**
     * Canonical deterministic string representation used for cryptographic signing and verification.
     */
    public String toCanonicalString() {
        List<String> sortedFeatures = features.stream().sorted().toList();
        return String.join("|",
                licenseKey.trim(),
                companyName.trim(),
                machineHash.trim().toUpperCase(),
                tier,
                String.valueOf(validFrom.getEpochSecond()),
                String.valueOf(validUntil.getEpochSecond()),
                String.valueOf(gracePeriodDays),
                String.valueOf(maxUsers),
                String.join(",", sortedFeatures)
        );
    }
}
