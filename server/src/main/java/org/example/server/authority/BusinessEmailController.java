package org.example.server.authority;

import org.example.server.auth.SmtpMailService;
import org.example.server.auth.EmailDeliveryException;
import org.example.server.communication.EmailOutboxService;
import org.example.server.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api/authority/email")
public class BusinessEmailController {
    private final SmtpMailService mail;
    private final org.example.server.communication.ServerNotificationTemplateService templates;
    private final EmailOutboxService outbox;

    @org.springframework.beans.factory.annotation.Autowired
    public BusinessEmailController(SmtpMailService mail,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) org.example.server.communication.ServerNotificationTemplateService templates,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false) EmailOutboxService outbox) {
        this.mail = mail;
        this.templates = templates;
        this.outbox = outbox;
    }

    public BusinessEmailController(SmtpMailService mail, org.example.server.communication.ServerNotificationTemplateService templates) {
        this(mail, templates, null);
    }

    public BusinessEmailController(SmtpMailService mail) {
        this(mail, null, null);
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
        if (outbox != null) {
            List<SmtpMailService.Attachment> attachments = attachment == null || attachment.length == 0
                    ? List.of()
                    : List.of(new SmtpMailService.Attachment(
                            r.attachmentName() == null ? "document.pdf" : r.attachmentName(),
                            "application/octet-stream",
                            attachment));
            SmtpMailService.Attachment sigAtt = signature == null || signature.length == 0
                    ? null
                    : new SmtpMailService.Attachment(
                            r.signatureImageName() == null ? "signature.png" : r.signatureImageName(),
                            "image/png",
                            signature);
            outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                    r.recipient(), r.recipientCc(), r.recipientBcc(), r.subject(), r.body(),
                    attachments, sigAtt, r.senderEmail(), r.senderDisplayName(),
                    r.entityType(), r.entityId(), r.referenceNo(), r.templateKey()
            ));
        } else {
            List<SmtpMailService.Attachment> attachments = attachment == null || attachment.length == 0
                    ? List.of()
                    : List.of(new SmtpMailService.Attachment(
                            r.attachmentName() == null ? "document.pdf" : r.attachmentName(),
                            "application/octet-stream",
                            attachment));
            SmtpMailService.Attachment sigAtt = signature == null || signature.length == 0
                    ? null
                    : new SmtpMailService.Attachment(
                            r.signatureImageName() == null ? "signature.png" : r.signatureImageName(),
                            "image/png",
                            signature);
            mail.sendBusinessWithCopies(r.recipient(), r.recipientCc(), r.recipientBcc(), r.subject(), r.body(), attachments, sigAtt, r.senderEmail(), r.senderDisplayName());
        }
        return new Result(true, message);
    }

    @GetMapping("/outbox")
    public EmailOutboxService.OutboxPage outbox(@RequestParam(defaultValue = "ALL") String status,
                                                @RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        CurrentUser.requirePermission("COMMUNICATION.VIEW", "View email outbox");
        if (outbox == null) {
            return new EmailOutboxService.OutboxPage(List.of(), new EmailOutboxService.OutboxSummary(0, 0, 0, 0, 0), 0, size, 0, 0);
        }
        return outbox.listOutbox(status, q, page, size);
    }

    @PostMapping("/outbox/{id}/retry")
    public EmailOutboxService.RetryResult retryOutbox(@PathVariable long id) {
        CurrentUser.requirePermission("COMMUNICATION.RESEND", "Retry failed outbox email");
        if (outbox == null) {
            return new EmailOutboxService.RetryResult(false, "UNAVAILABLE", "Outbox service is not active");
        }
        return outbox.retryNow(id);
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
            var currentSettings = mail.currentSettingsSummary();
            String nowStr = org.example.server.util.BusinessClock.nowUtcText();
            var tpl = templates != null ? templates : org.example.server.communication.ServerNotificationTemplateService.forJdbc(null);
            String hostStr = currentSettings.host() == null || currentSettings.host().isBlank() ? "-" : currentSettings.host();
            String portStr = String.valueOf(currentSettings.port());
            String senderStr = currentSettings.email() == null || currentSettings.email().isBlank() ? "-" : currentSettings.email();
            String recipStr = recipient == null || recipient.isBlank() ? senderStr : recipient;
            var rendered = tpl.renderRichEmail(
                    "smtp_test",
                    java.util.Map.of(
                            "CompanyName", company,
                            "AppName", company + " ERP",
                            "Recipient", recipStr,
                            "SmtpHost", hostStr,
                            "SmtpPort", portStr,
                            "SenderEmail", senderStr,
                            "Timestamp", nowStr
                    ),
                    new org.example.server.communication.ServerNotificationTemplateService.RichCardStyle(
                            "#0f766e", "#115e59",
                            "SMTP DIAGNOSTICS",
                            "VERIFIED",
                            "#15803d",
                            company + " Email Verification",
                            "Outbound relay transport health check",
                            "SMTP diagnostic verification passed for " + recipStr,
                            List.of(
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("HOST", hostStr),
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("PORT", portStr),
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("SENDER", senderStr)
                            ),
                            List.of(
                                    org.example.shared.communication.TokenTemplateEngine.keyValueSectionHtml(
                                            "SMTP Relay Connection Diagnostics",
                                            "#0f766e",
                                            List.of(
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("SMTP Host", hostStr, true),
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("SMTP Port", portStr, true),
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Sender Address", senderStr, false),
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Test Recipient", recipStr, false),
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Verified At", nowStr, false),
                                                    new org.example.shared.communication.TokenTemplateEngine.KeyValueRow("Connection Status", "AUTHENTICATED & HEALTHY", false, "#15803d", "#dcfce7")
                                            )
                                    )
                            ),
                            List.of(),
                            company + " Notification Engine",
                            null
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

    public record Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64,
                          String signatureImageName, String signatureImageBase64, String senderEmail, String senderDisplayName,
                          String recipientCc, String recipientBcc, String entityType, Long entityId, String referenceNo, String templateKey) {
        public Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64, String signatureImageName, String signatureImageBase64) {
            this(recipient, subject, body, attachmentName, attachmentBase64, signatureImageName, signatureImageBase64, null, null, null, null, null, null, null, null);
        }

        public Request(String recipient, String subject, String body, String attachmentName, String attachmentBase64, String signatureImageName, String signatureImageBase64, String senderEmail, String senderDisplayName) {
            this(recipient, subject, body, attachmentName, attachmentBase64, signatureImageName, signatureImageBase64, senderEmail, senderDisplayName, null, null, null, null, null, null);
        }
    }
    public record Settings(String email, String appPassword, String host, Integer port, boolean passwordConfigured) {}
    public record TestRequest(String recipient) {}
    public record Result(boolean success, String message) {}
}
