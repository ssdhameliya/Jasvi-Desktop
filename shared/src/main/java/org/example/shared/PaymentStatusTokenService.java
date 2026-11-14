package org.example.shared;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Generates and validates tamper-proof HMAC-SHA256 tokens for public
 * invoice and payment live status verification links.
 */
public final class PaymentStatusTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] FALLBACK_SALT = "jasvi-erp-public-status-salt-v11-2026".getBytes(StandardCharsets.UTF_8);

    private PaymentStatusTokenService() {}

    /**
     * Computes a deterministic 32-character hexadecimal verification token
     * for a specific invoice number and total amount.
     */
    public static String generateToken(String invoiceNo, double totalAmount) {
        String cleanNo = invoiceNo == null ? "" : invoiceNo.trim().toUpperCase(java.util.Locale.ROOT);
        String payload = cleanNo + "|" + String.format(java.util.Locale.ROOT, "%.2f", totalAmount);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(FALLBACK_SALT, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate payment status HMAC token", e);
        }
    }

    /**
     * Verifies that the provided token matches the expected HMAC token in constant time.
     */
    public static boolean verifyToken(String invoiceNo, double totalAmount, String providedToken) {
        if (providedToken == null || providedToken.isBlank()) return false;
        String expected = generateToken(invoiceNo, totalAmount);
        return MessageDigest.isEqual(
                expected.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                providedToken.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }
}
