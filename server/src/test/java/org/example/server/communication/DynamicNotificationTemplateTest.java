package org.example.server.communication;

import org.example.server.audit.AuditService;
import org.example.server.operations.BusinessOperationsService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.AuthenticatedUser;
import org.example.server.support.PaymentIntegrityService;
import org.example.server.support.SupportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamicNotificationTemplateTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rendersEmailInHtmlAndStandardModesAndHonorsPerTemplateOverride() {
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        when(jdbc.query(anyString(), any(BiFunction.class), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0, String.class);
                    Object[] args = inv.getArguments();
                    String key = args.length >= 3 && args[2] != null ? args[2].toString() : "";
                    if (sql.contains("FROM application_setting")) {
                        return switch (key) {
                            case "company.name" -> List.of("Sunrise Engineering Co");
                            case "email.format.mode" -> List.of("STANDARD");
                            case "email.template.auth_otp.subject" -> List.of("[{CompanyName}] Custom Code for {Purpose}");
                            case "email.template.auth_otp.body" -> List.of("Hi, your OTP for {Purpose} is {OtpCode} (expires in {ExpiryMinutes}m).");
                            case "email.template.security_unlock.format" -> List.of("HTML");
                            default -> List.of();
                        };
                    }
                    return List.of();
                });

        ServerNotificationTemplateService service = new ServerNotificationTemplateService(jdbc);
        assertEquals("Sunrise Engineering Co", service.companyName());
        assertFalse(service.shouldUseHtml("auth_otp"));
        assertTrue(service.shouldUseHtml("security_unlock"), "Per-template HTML override should win over company STANDARD default");

        var otpEmail = service.renderEmail(
                "auth_otp",
                Map.of("Purpose", "password reset", "OtpCode", "918273", "ExpiryMinutes", "10"),
                ServerNotificationTemplateService.CardStyle.standardBlue("OTP", "Code", "Footer")
        );
        assertFalse(otpEmail.html());
        assertEquals("[Sunrise Engineering Co] Custom Code for password reset", otpEmail.subject());
        assertEquals("Hi, your OTP for password reset is 918273 (expires in 10m).", otpEmail.body());
        assertFalse(otpEmail.body().contains("<!DOCTYPE html>"));

        var unlockEmail = service.renderEmail(
                "security_unlock",
                Map.of("TicketId", "SEC-123456", "FullName", "Asha Patel", "Username", "asha"),
                ServerNotificationTemplateService.CardStyle.standardBlue("SEC-123456", "Unlocked", "Footer")
        );
        assertTrue(unlockEmail.html());
        assertTrue(unlockEmail.body().contains("<!DOCTYPE html>"));
        assertTrue(unlockEmail.body().contains("SEC-123456"));
        assertTrue(unlockEmail.body().contains("Asha Patel"));
    }

    @Test
    void whatsappTemplatesUseCustomizedDbTemplateAndFallbackCleanly() {
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        when(jdbc.query(anyString(), any(BiFunction.class), any()))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0, String.class);
                    Object[] args = inv.getArguments();
                    String key = args.length >= 3 && args[2] != null ? args[2].toString() : "";
                    if (sql.contains("FROM application_setting")) {
                        return switch (key) {
                            case "company.name" -> List.of("Vertex Hydraulics");
                            case "whatsapp.template.aging_collection.body" -> List.of("Reminder from {CompanyName}: {PartyName} owes {TotalOutstanding}.");
                            default -> List.of();
                        };
                    }
                    return List.of();
                });

        ServerNotificationTemplateService service = new ServerNotificationTemplateService(jdbc);
        String wa = service.renderWhatsapp("aging_collection", Map.of("PartyName", "Metro Pumps", "TotalOutstanding", "₹ 85,000"));
        assertEquals("Reminder from Vertex Hydraulics: Metro Pumps owes ₹ 85,000.", wa);
    }

    @Test
    void supportServiceEnforcesAdminAndSuperAdminRolesOnProtectedTemplateKeys() {
        JpaNativeRepository jdbc = mock(JpaNativeRepository.class);
        SupportService support = new SupportService(
                jdbc,
                mock(PaymentIntegrityService.class),
                mock(BusinessOperationsService.class),
                mock(AuditService.class)
        );

        setUser(2, "billing_user", "BILLING");
        // Staff CAN update business collection templates
        assertDoesNotThrow(() -> support.setSetting("email.template.aging_collection.subject", "Payment Reminder"));
        // Staff CANNOT change company format mode or admin/super-admin security templates
        assertThrows(SecurityException.class, () -> support.setSetting("email.format.mode", "STANDARD"));
        assertThrows(SecurityException.class, () -> support.setSetting("email.template.auth_otp.body", "Custom OTP {OtpCode}"));
        assertThrows(SecurityException.class, () -> support.setSetting("whatsapp.template.rogue_alert.body", "Custom Rogue"));

        setUser(3, "company_admin", "ADMIN");
        // Admin CAN update format mode and auth_otp, but CANNOT update super-admin licensing templates
        assertDoesNotThrow(() -> support.setSetting("email.format.mode", "STANDARD"));
        assertDoesNotThrow(() -> support.setSetting("email.template.auth_otp.body", "Custom OTP {OtpCode}"));
        assertThrows(SecurityException.class, () -> support.setSetting("email.template.license_activation.subject", "Hacked"));

        setUser(1, "superadmin", "SUPER_ADMIN");
        // Super Admin CAN update licensing templates
        assertDoesNotThrow(() -> support.setSetting("email.template.license_activation.subject", "Activated {CompanyName}"));
        assertDoesNotThrow(() -> support.setSetting("whatsapp.template.rogue_alert.body", "Alert {MachineId}"));
    }

    private static void setUser(int id, String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(id, username, role), "N/A", List.of())
        );
    }
}
