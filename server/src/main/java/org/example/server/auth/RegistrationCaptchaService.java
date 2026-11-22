package org.example.server.auth;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise alphanumeric CAPTCHA service with high-entropy challenge generation,
 * brute-force rate-limiting, and case-insensitive verification.
 */
@Service
public class RegistrationCaptchaService {
    private static final String CHAR_POOL = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public AuthDtos.CaptchaResponse issue() {
        cleanup();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(CHAR_POOL.charAt(random.nextInt(CHAR_POOL.length())));
        }
        String code = sb.toString();
        String id = UUID.randomUUID().toString();
        challenges.put(id, new Challenge(code, Instant.now().plusSeconds(300).getEpochSecond(), 0));
        return new AuthDtos.CaptchaResponse(id, code, "5 minutes");
    }

    public boolean verify(String id, String answer) {
        cleanup();
        if (id == null || answer == null) return false;
        Challenge c = challenges.get(id);
        if (c == null) return false;
        if (c.attempts() >= 5) {
            challenges.remove(id);
            return false;
        }
        String normalizedExpected = c.answer().trim();
        String normalizedActual = answer.trim();
        if (!normalizedExpected.equalsIgnoreCase(normalizedActual)) {
            challenges.put(id, new Challenge(c.answer(), c.expiresAt(), c.attempts() + 1));
            return false;
        }
        challenges.remove(id);
        return true;
    }

    private void cleanup() {
        long now = Instant.now().getEpochSecond();
        challenges.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    private record Challenge(String answer, long expiresAt, int attempts) {}
}
