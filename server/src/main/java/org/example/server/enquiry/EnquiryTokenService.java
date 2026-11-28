package org.example.server.enquiry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Generates and verifies HMAC-SHA256 signed URL tokens for zero-app Mobile Quick-View cards
 * dispatched exclusively to ADMIN and SUPER_ADMIN alerts.
 */
@Service
public class EnquiryTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] signingKey;

    public EnquiryTokenService(
            @Value("${dse.enquiry.view-secret:jasvi-erp-enquiry-mobile-view-salt-v11-2026}") String secret) {
        String resolved = (secret == null || secret.isBlank())
                ? "jasvi-erp-enquiry-mobile-view-salt-v11-2026"
                : secret.trim();
        this.signingKey = resolved.getBytes(StandardCharsets.UTF_8);
    }

    public String generateToken(long enquiryId, String enquiryNo) {
        String cleanNo = enquiryNo == null ? "" : enquiryNo.trim().toUpperCase(Locale.ROOT);
        String payload = enquiryId + "|" + cleanNo;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign enquiry mobile-view token", ex);
        }
    }

    public boolean verifyToken(long enquiryId, String enquiryNo, String providedToken) {
        if (enquiryId <= 0 || providedToken == null || providedToken.isBlank()) return false;
        String expected = generateToken(enquiryId, enquiryNo);
        return MessageDigest.isEqual(
                expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                providedToken.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
