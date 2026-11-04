package org.example.shared.licensing;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Native Java 25 Ed25519 cryptographic signing and verification utility.
 * The public key is embedded in the application distribution to verify license leases.
 */
public final class Ed25519CryptoUtil {

    /**
     * Master Jasvi Platform Public Key (Base64 X.509 SubjectPublicKeyInfo).
     * Corresponding private key is kept strictly offline by the software creator.
     */
    public static final String EMBEDDED_PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEA/zNhQ1QFOrNySUJE7ga6x49DK1o++zhQc8xRzT0hKiU=";

    private static final PublicKey EMBEDDED_PUBLIC_KEY;

    static {
        try {
            EMBEDDED_PUBLIC_KEY = loadPublicKey(EMBEDDED_PUBLIC_KEY_BASE64);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Ed25519CryptoUtil() {}

    /**
     * Verifies data signature using the baked-in platform public key.
     */
    public static boolean verifyWithEmbeddedKey(byte[] data, byte[] signatureBytes) {
        return verify(data, signatureBytes, EMBEDDED_PUBLIC_KEY);
    }

    /**
     * Verifies data signature using a specified public key.
     */
    public static boolean verify(byte[] data, byte[] signatureBytes, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifies a canonical string against a base64 encoded signature.
     */
    public static boolean verifyStringWithEmbeddedKey(String canonicalData, String signatureBase64) {
        if (canonicalData == null || signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            byte[] dataBytes = canonicalData.getBytes(StandardCharsets.UTF_8);
            byte[] sigBytes = Base64.getDecoder().decode(signatureBase64.trim());
            return verifyWithEmbeddedKey(dataBytes, sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Signs data using the private key.
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }

    /**
     * Signs a canonical string and returns a Base64 signature.
     */
    public static String signString(String canonicalData, PrivateKey privateKey) throws GeneralSecurityException {
        byte[] dataBytes = canonicalData.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = sign(dataBytes, privateKey);
        return Base64.getEncoder().encodeToString(sigBytes);
    }

    public static PublicKey loadPublicKey(String base64) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(base64.trim());
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public static PrivateKey loadPrivateKey(String base64) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(base64.trim());
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }
}
