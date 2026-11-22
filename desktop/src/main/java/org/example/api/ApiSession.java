package org.example.api;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiSession {
    public interface SessionRevocationListener {
        void onSessionRevoked(String reason);
    }

    private static volatile String accessToken;
    private static volatile Instant expiresAt;
    private static volatile String apiBaseUrl;
    private static volatile SessionRevocationListener revocationListener;
    private static final AtomicBoolean revokedNoticeDispatched = new AtomicBoolean(false);

    private ApiSession() {
    }

    public static void setSessionRevocationListener(SessionRevocationListener listener) {
        revocationListener = listener;
    }

    public static void notifySessionRevoked(String reason) {
        String msg = (reason != null && !reason.isBlank()) ? reason : "Your account was signed in from another computer.";
        SessionRevocationListener listener = revocationListener;
        clear();
        var current = org.example.service.SessionService.current();
        if (listener != null && current != null && !current.isForcePasswordChange() && revokedNoticeDispatched.compareAndSet(false, true)) {
            listener.onSessionRevoked(msg);
        }
    }

    public static void establish(String token, String expiry, String baseUrl) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Authentication token is missing");
        String normalizedBase = normalizeBaseUrl(baseUrl);
        if (normalizedBase.isBlank()) throw new IllegalArgumentException("Authentication server address is missing");
        accessToken = token;
        expiresAt = expiry == null || expiry.isBlank() ? null : Instant.parse(expiry);
        apiBaseUrl = normalizedBase;
        revokedNoticeDispatched.set(false);
    }

    /** Compatibility overload for tests/older callers; new login code must bind the issuing server explicitly. */
    public static void establish(String token, String expiry) {
        establish(token, expiry, org.example.config.ConfigManager.getDataApiBaseUrlUnbound());
    }

    public static HttpRequest.Builder authorize(HttpRequest.Builder request) {
        String token = accessToken;
        if (token == null || token.isBlank() || (expiresAt != null && !expiresAt.isAfter(Instant.now()))) {
            boolean wasEstablished = token != null && !token.isBlank();
            clear();
            if (wasEstablished) {
                notifySessionRevoked("Your login session expired. Please sign in again.");
            }
            throw new AuthenticationRequiredException("Please sign in again");
        }
        return request.header("Authorization", "Bearer " + token);
    }

    public static String token() {
        return accessToken;
    }

    public static String boundApiBaseUrl() {
        return apiBaseUrl;
    }

    public static boolean isEstablished() {
        return accessToken != null && !accessToken.isBlank();
    }

    public static void rebindApiBaseUrl(String baseUrl) {
        if (!isEstablished()) return;
        String normalizedBase = normalizeBaseUrl(baseUrl);
        if (normalizedBase.isBlank()) throw new IllegalArgumentException("Authentication server address is missing");
        apiBaseUrl = normalizedBase;
    }

    public static void clear() {
        accessToken = null;
        expiresAt = null;
        apiBaseUrl = null;
    }

    private static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static AuthenticationRequiredException rejected(String operation, String responseBody) {
        String code = jsonField(responseBody, "code");
        String message = jsonField(responseBody, "message");
        String prefix = operation == null || operation.isBlank() ? "ERP request" : operation.trim();
        String detail = code.isBlank() ? "AUTHENTICATION_REJECTED" : code;
        if (!message.isBlank() && !"Authentication required".equalsIgnoreCase(message)) {
            detail += " - " + message;
        }
        notifySessionRevoked(prefix + " was rejected by the ERP server (" + detail + ").");
        return new AuthenticationRequiredException(prefix + " was rejected by the ERP server (" + detail + "). Please sign in again.");
    }

    private static String jsonField(String json, String field) {
        if (json == null || json.isBlank() || field == null || field.isBlank()) return "";
        String marker = "\"" + field + "\"";
        int key = json.indexOf(marker);
        if (key < 0) return "";
        int colon = json.indexOf(':', key + marker.length());
        if (colon < 0) return "";
        int start = json.indexOf('\"', colon + 1);
        if (start < 0) return "";
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { out.append(c); escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '\"') break;
            out.append(c);
        }
        return out.toString().trim();
    }

    public static final class AuthenticationRequiredException extends IllegalStateException {
        public AuthenticationRequiredException(String message) {
            super(message);
        }
    }
}
