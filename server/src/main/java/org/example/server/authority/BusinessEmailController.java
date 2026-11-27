package org.example.server.authority;

import org.example.server.auth.SmtpMailService;
import org.example.server.auth.EmailDeliveryException;
import org.example.server.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;

@RestController
@RequestMapping("/api/authority/email")
public class BusinessEmailController {
    private final SmtpMailService mail;
    private final org.example.server.communication.ServerNotificationTemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessEmailController(SmtpMailService mail,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) org.example.server.communication.ServerNotificationTemplateService templates) {
        this.mail = mail;
        this.templates = templates;
    }

    public BusinessEmailController(SmtpMailService mail) {
        this(mail, null);
    }

    @PostMapping
    public Result send(@RequestBody Request r) {
        CurrentUser.requirePermission("COMMUNICATION.CREATE", "Send business email");
        return sendInternal(r, "Sent by company server");
    }

    @PostMapping("/resend")
    public Result resend(@RequestBody Request r) {
        CurrentUser.requirePermission("COMMUNICATION.RESEND", "Re-send business email");
        return sendInternal(r, "Re-sent by company server");
    }

    private Result sendInternal(Request r, String message) {
        byte[] attachment = r.attachmentBase64() == null || r.attachmentBase64().isBlank()
                ? null : Base64.getDecoder().decode(r.attachmentBase64());
        byte[] signature = r.signatureImageBase64() == null || r.signatureImageBase64().isBlank()
                ? null : Base64.getDecoder().decode(r.signatureImageBase64());
        mail.sendBusiness(r.recipient(), r.subject(), r.body(), r.attachmentName(), attachment, r.signatureImageName(), signature, r.senderEmail(), r.senderDisplayName());
        return new Result(true, message);
    }

    @GetMapping("/settings")
    public Settings settings() {
        requireAdmin();
        var current = mail.currentSettingsSummary();
        return new Settings(current.email(), "", current.host(), current.port(), current.passwordConfigured());
    }

    @PutMapping("/settings")
    public Settings settings(@RequestBody Settings requested) {
        requireAdmin();
        mail.saveSettings(requested.email(), requested.appPassword(), requested.host(), requested.port());
        var saved = mail.currentSettingsSummary();
        return new Settings(saved.email(), "", saved.host(), saved.port(), saved.passwordConfigured());
    }

    @PostMapping("/test")
    public Result test(@RequestBody TestRequest request) {
        requireAdmin();
        String recipient = request == null ? null : request.recipient();
        try {
            String company = mail.companyName();
            String defaultSubject = mail.companyName() + " email test";
            var tpl = templates != null ? templates : org.example.server.communication.ServerNotificationTemplateService.forJdbc(null);
            var rendered = tpl.renderEmail(
                    "smtp_test",
                    java.util.Map.of(
                            "CompanyName", company,
                            "AppName", company + " ERP",
                            "Recipient", recipient == null ? "" : recipient
                    ),
                    new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                            "#0284c7", "#0369a1", "SMTP Diagnostic",
                            company + " Email Verification", java.util.List.of(),
                            company + " Notification Engine"
                    )
            );
            String finalSubject = rendered.subject() != null && !rendered.subject().isBlank() ? rendered.subject() : defaultSubject;
            mail.sendBusiness(recipient, finalSubject, rendered.body());
            return new Result(true, "Test email sent successfully");
        } catch (EmailDeliveryException failure) {
            throw new IllegalStateException(failure.adminMessage(), failure);
        }
    }

    private void requireAdmin() {
        if (!"ADMIN".equalsIgnoreCase(CurrentUser.require().role()) && !"SUPER_ADMIN".equalsIgnoreCase(CurrentUser.require().role()))
            throw new SecurityException("Company email settings require Administrator access");
    }

    public record Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64, String signatureImageName, String signatureImageBase64, String senderEmail, String senderDisplayName) {
        public Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64, String signatureImageName, String signatureImageBase64) {
            this(recipient, subject, body, attachmentName, attachmentBase64, signatureImageName, signatureImageBase64, null, null);
        }
    }
    public record Settings(String email, String appPassword, String host, Integer port, boolean passwordConfigured) {}
    public record TestRequest(String recipient) {}
    public record Result(boolean success, String message) {}
}
