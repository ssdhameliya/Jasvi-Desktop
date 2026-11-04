package org.example.server.licensing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Spring Boot MVC Interceptor enforcing "Read-Only Compliance Mode".
 * Allows all GET / query requests so users never lose read/print/export access to their books,
 * but strictly blocks POST / PUT / DELETE mutations when a license is expired or tampered with.
 */
@Component
public class LicenseSecurityInterceptor implements HandlerInterceptor {

    private final LicenseVerifierService verifier;

    public LicenseSecurityInterceptor(LicenseVerifierService verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/api/license") || uri.startsWith("/api/auth") || uri.startsWith("/api/setup")
                || uri.startsWith("/actuator") || uri.startsWith("/api/health")
                || uri.endsWith("/verify-gstin") || uri.startsWith("/api/support/search")) {
            return true;
        }

        try {
            var user = org.example.server.security.CurrentUser.require();
            if ("SUPER_ADMIN".equalsIgnoreCase(user.role()) || "superadmin".equalsIgnoreCase(user.username())) {
                return true;
            }
        } catch (Exception ignored) {}

        LicenseVerifierService.LicenseStatus status = verifier.checkLicense();
        if (status.isWriteAllowed()) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        try (PrintWriter writer = response.getWriter()) {
            writer.write(String.format(
                    "{\"status\":403,\"error\":\"LICENSE_RESTRICTION\",\"licenseStatus\":\"%s\",\"message\":\"%s\"}",
                    status.status().name(),
                    escapeJson(status.message())
            ));
            writer.flush();
        }
        return false;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
