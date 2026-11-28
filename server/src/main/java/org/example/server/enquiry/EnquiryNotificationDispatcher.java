package org.example.server.enquiry;

import org.example.server.communication.EmailOutboxService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rule 10 Compliant External Alert Dispatcher.
 * Fires strictly AFTER PostgreSQL transaction commit on a Java 25 Virtual Thread
 * so outbound WAHA WhatsApp and SMTP Email calls never hold DB locks or roll back enquiries.
 */
@Component
public class EnquiryNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EnquiryNotificationDispatcher.class);

    private final JpaNativeRepository jdbc;
    private final ObjectProvider<BusinessWhatsappService> whatsappProvider;
    private final ObjectProvider<EmailOutboxService> emailOutboxProvider;
    private final String deploymentEnvironment;
    private final String publicUrlLocal;
    private final String publicUrlUat;
    private final String publicUrlProd;

    public EnquiryNotificationDispatcher(
            JpaNativeRepository jdbc,
            ObjectProvider<BusinessWhatsappService> whatsappProvider,
            ObjectProvider<EmailOutboxService> emailOutboxProvider,
            @Value("${dse.deployment.environment:LOCAL}") String deploymentEnvironment,
            @Value("${dse.public-url.local:http://localhost:58080}") String publicUrlLocal,
            @Value("${dse.public-url.uat:https://api-uat.jasviindustries.in}") String publicUrlUat,
            @Value("${dse.public-url.prod:https://api.jasviindustries.in}") String publicUrlProd) {
        this.jdbc = jdbc;
        this.whatsappProvider = whatsappProvider;
        this.emailOutboxProvider = emailOutboxProvider;
        this.deploymentEnvironment = deploymentEnvironment == null ? "LOCAL" : deploymentEnvironment.trim().toUpperCase(Locale.ROOT);
        this.publicUrlLocal = cleanUrl(publicUrlLocal, "http://localhost:58080");
        this.publicUrlUat = cleanUrl(publicUrlUat, "https://api-uat.jasviindustries.in");
        this.publicUrlProd = cleanUrl(publicUrlProd, "https://api.jasviindustries.in");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnquirySubmitted(EnquiryDtos.EnquirySubmittedEvent event) {
        if (event == null) return;
        Thread.ofVirtual().name("enquiry-alert-" + event.enquiryId()).start(() -> dispatchAll(event));
    }

    private void dispatchAll(EnquiryDtos.EnquirySubmittedEvent e) {
        String mobileUrl = buildMobileViewUrl(e.enquiryId(), e.mobileViewToken());
        List<AdminRecipient> admins = loadActiveAdminRecipients();
        if (admins.isEmpty()) {
            log.warn("Website enquiry {} saved, but no active ADMIN or SUPER_ADMIN users have phone/email configured.", e.enquiryNo());
        }

        dispatchWhatsappAlerts(e, admins, mobileUrl);
        dispatchEmailAlerts(e, admins, mobileUrl);
    }

    private void dispatchWhatsappAlerts(EnquiryDtos.EnquirySubmittedEvent e, List<AdminRecipient> admins, String mobileUrl) {
        BusinessWhatsappService wa = whatsappProvider.getIfAvailable();
        if (wa == null) return;

        Set<String> targetPhones = new LinkedHashSet<>();
        for (AdminRecipient admin : admins) {
            String p = safe(admin.phone()).trim();
            if (!p.isBlank()) targetPhones.add(p);
        }
        if (targetPhones.isEmpty()) {
            targetPhones.addAll(resolveFallbackAdminPhones());
        }
        if (targetPhones.isEmpty()) {
            log.warn("WhatsApp alert for {} skipped: no admin phone or company phone configured.", e.enquiryNo());
            return;
        }

        Set<String> sentPhones = new LinkedHashSet<>();
        String body = buildWhatsappMessage(e, mobileUrl);
        for (String phone : targetPhones) {
            if (!sentPhones.add(normalizeDigits(phone))) continue;
            try {
                wa.sendText(new WahaDtos.TextSendRequest(phone, body, null));
            } catch (Exception ex) {
                log.warn("WhatsApp alert failed for {} ({}): {}", phone, e.enquiryNo(), ex.getMessage());
                try {
                    jdbc.update("INSERT INTO communication_log (entity_type, entity_id, channel, recipient, subject, status, error_message, created_by, created_at) " +
                            "VALUES ('ENQUIRY', ?, 'WHATSAPP', ?, ?, 'FAILED', ?, 'SYSTEM', ?)",
                            e.enquiryId(), phone, "Enquiry Alert: " + e.enquiryNo(), ex.getMessage(), org.example.server.util.BusinessClock.nowUtcText());
                } catch (Exception ignored) {}
            }
        }
    }

    private List<String> resolveFallbackAdminPhones() {
        Set<String> phones = new LinkedHashSet<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT setting_value FROM application_setting WHERE setting_key IN ('whatsapp.alert.recipient', 'notification.admin_phone', 'company.phone') AND TRIM(setting_value) <> ''");
            for (Map<String, Object> r : rows) {
                String val = Objects.toString(r.get("setting_value"), "").trim();
                if (!val.isBlank()) phones.add(val);
            }
        } catch (Exception ignored) {}
        try {
            List<Map<String, Object>> comp = jdbc.queryForList("SELECT COALESCE(phone, '') AS phone FROM company_profile WHERE TRIM(COALESCE(phone, '')) <> '' LIMIT 1");
            if (!comp.isEmpty()) {
                String val = Objects.toString(comp.getFirst().get("phone"), "").trim();
                if (!val.isBlank()) phones.add(val);
            }
        } catch (Exception ignored) {}
        return List.copyOf(phones);
    }

    private void dispatchEmailAlerts(EnquiryDtos.EnquirySubmittedEvent e, List<AdminRecipient> admins, String mobileUrl) {
        EmailOutboxService outbox = emailOutboxProvider.getIfAvailable();
        if (outbox == null) return;

        Set<String> sentEmails = new LinkedHashSet<>();
        String subject = ("FEEDBACK".equalsIgnoreCase(e.enquiryType()) ? "Website Feedback " : "Website Quote / Contact Enquiry ")
                + e.enquiryNo() + " • " + safe(e.fullName());
        String html = buildAdminHtmlEmail(e, mobileUrl);

        for (AdminRecipient admin : admins) {
            String email = safe(admin.email());
            if (email.isBlank() || !email.contains("@") || !sentEmails.add(email.toLowerCase(Locale.ROOT))) continue;
            try {
                outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                        email, null, null, subject, html, List.of(), null, null, null,
                        "ENQUIRY", e.enquiryId(), e.enquiryNo(), "PUBLIC_ENQUIRY_ADMIN_ALERT"));
            } catch (Exception ex) {
                log.warn("Admin email alert queued/failed for {} ({}): {}", email, e.enquiryNo(), ex.getMessage());
            }
        }

        if (isClientAckEnabled() && !safe(e.email()).isBlank() && e.email().contains("@")) {
            try {
                String ackSubject = "Thank you for contacting DS Industries • Ref " + e.enquiryNo();
                String ackHtml = buildClientAckHtmlEmail(e);
                outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                        e.email().trim(), null, null, ackSubject, ackHtml, List.of(), null, null, null,
                        "ENQUIRY", e.enquiryId(), e.enquiryNo(), "PUBLIC_ENQUIRY_CLIENT_ACK"));
            } catch (Exception ex) {
                log.warn("Visitor confirmation email failed for {} ({}): {}", e.email(), e.enquiryNo(), ex.getMessage());
            }
        }
    }

    private List<AdminRecipient> loadActiveAdminRecipients() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, COALESCE(full_name, username) AS display_name, COALESCE(email, '') AS email, COALESCE(phone, '') AS phone " +
                "FROM users WHERE active = 1 AND UPPER(COALESCE(role, '')) IN ('ADMIN', 'SUPER_ADMIN', 'SUPERADMIN') ORDER BY id ASC");
        return rows.stream().map(r -> new AdminRecipient(
                ((Number) r.get("id")).intValue(),
                Objects.toString(r.get("username"), ""),
                Objects.toString(r.get("display_name"), ""),
                Objects.toString(r.get("email"), "").trim(),
                Objects.toString(r.get("phone"), "").trim()
        )).toList();
    }

    private boolean isClientAckEnabled() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT setting_value FROM application_setting WHERE setting_key='enquiry.client_ack_email.enabled'");
            if (!rows.isEmpty() && rows.getFirst().get("setting_value") != null) {
                return !"false".equalsIgnoreCase(rows.getFirst().get("setting_value").toString().trim());
            }
        } catch (Exception ignored) {}
        return true;
    }

    String buildMobileViewUrl(long enquiryId, String token) {
        String base = switch (deploymentEnvironment) {
            case "PROD", "PRODUCTION" -> publicUrlProd;
            case "UAT", "STAGING" -> publicUrlUat;
            default -> publicUrlLocal;
        };
        return base + "/api/public/enquiry-view?id=" + enquiryId + "&token=" + token;
    }

    private static String buildWhatsappMessage(EnquiryDtos.EnquirySubmittedEvent e, String mobileUrl) {
        StringBuilder sb = new StringBuilder();
        String kind = "FEEDBACK".equalsIgnoreCase(e.enquiryType()) ? "Website Feedback" : "New Website Enquiry / RFQ";
        sb.append("*").append(kind).append(": ").append(e.enquiryNo()).append("*\n");
        sb.append("• Name: ").append(safe(e.fullName())).append("\n");
        if (!safe(e.companyName()).isBlank()) sb.append("• Company: ").append(safe(e.companyName())).append("\n");
        if (!safe(e.phone()).isBlank()) sb.append("• Phone: ").append(safe(e.phone())).append("\n");
        if (!safe(e.email()).isBlank()) sb.append("• Email: ").append(safe(e.email())).append("\n");
        if (!safe(e.cityState()).isBlank()) sb.append("• Location: ").append(safe(e.cityState())).append("\n");
        if (!safe(e.productSeries()).isBlank()) sb.append("• Product: ").append(safe(e.productSeries())).append("\n");
        String specs = joinNonBlank(" | ", e.npsSize(), e.pressureClass(), e.bodyMaterial(), e.quantityText());
        if (!specs.isBlank()) sb.append("• Specs: ").append(specs).append("\n");
        if (e.rating() != null) sb.append("• Rating: ").append(e.rating()).append("/5\n");
        if (!safe(e.message()).isBlank()) {
            String msg = safe(e.message());
            if (msg.length() > 350) msg = msg.substring(0, 347) + "...";
            sb.append("• Details: ").append(msg).append("\n");
        }
        sb.append("\nOpen & Act on Mobile:\n").append(mobileUrl);
        return sb.toString();
    }

    private static String buildAdminHtmlEmail(EnquiryDtos.EnquirySubmittedEvent e, String mobileUrl) {
        String kind = "FEEDBACK".equalsIgnoreCase(e.enquiryType()) ? "Client Feedback" : "Website Quote / Contact Enquiry";
        return """
            <div style="font-family:Segoe UI,Arial,sans-serif;max-width:620px;margin:0 auto;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
              <div style="background:#0f766e;color:#ffffff;padding:18px 24px;">
                <div style="font-size:12px;letter-spacing:1px;text-transform:uppercase;opacity:0.9;">%s</div>
                <h2 style="margin:6px 0 0;font-size:20px;">Reference: %s</h2>
              </div>
              <div style="padding:22px 24px;color:#1e293b;font-size:14px;line-height:1.6;">
                <table style="width:100%%;border-collapse:collapse;">
                  %s%s%s%s%s%s%s%s%s
                </table>
                <div style="margin-top:16px;padding:14px;background:#f8fafc;border-left:4px solid #0f766e;border-radius:6px;">
                  <strong>Message / Requirement:</strong><br/>%s
                </div>
                <div style="margin-top:22px;text-align:center;">
                  <a href="%s" style="display:inline-block;background:#0f766e;color:#ffffff;text-decoration:none;font-weight:600;padding:12px 22px;border-radius:8px;">
                    Open Enquiry on Mobile / Browser
                  </a>
                </div>
              </div>
            </div>
            """.formatted(
                esc(kind),
                esc(e.enquiryNo()),
                row("Client Name", e.fullName()),
                row("Company", e.companyName()),
                row("Phone", e.phone()),
                row("Email", e.email()),
                row("Location", e.cityState()),
                row("Product Series", e.productSeries()),
                row("Sizing / Specs", joinNonBlank(" | ", e.npsSize(), e.pressureClass(), e.bodyMaterial())),
                row("Quantity", e.quantityText()),
                e.rating() != null ? row("Rating", e.rating() + " / 5") : "",
                esc(safe(e.message())).replace("\n", "<br/>"),
                esc(mobileUrl));
    }

    private static String buildClientAckHtmlEmail(EnquiryDtos.EnquirySubmittedEvent e) {
        String name = safe(e.fullName()).isBlank() ? "Valued Partner" : safe(e.fullName());
        return """
            <div style="font-family:Segoe UI,Arial,sans-serif;max-width:600px;margin:0 auto;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
              <div style="background:#0f766e;color:#ffffff;padding:18px 24px;">
                <h2 style="margin:0;font-size:19px;">DS Industries — Enquiry Acknowledgement</h2>
              </div>
              <div style="padding:22px 24px;color:#1e293b;font-size:14px;line-height:1.6;">
                <p>Dear %s,</p>
                <p>Thank you for reaching out to <strong>DS Industries (Jasvi Industries Flow Systems)</strong>.</p>
                <p>Your submission has been logged under reference number <strong>%s</strong>%s.</p>
                <p>Our engineering &amp; sales team has been notified and will review your requirement shortly.</p>
                <p style="margin-top:18px;color:#475569;font-size:13px;">Plot No. 23, Vraj Industrial Park, Ahmedabad &bull; +91 95126 90771</p>
              </div>
            </div>
            """.formatted(
                esc(name),
                esc(e.enquiryNo()),
                safe(e.productSeries()).isBlank() ? "" : " for <strong>" + esc(e.productSeries()) + "</strong>");
    }

    private static String row(String label, String val) {
        if (val == null || val.isBlank()) return "";
        return "<tr><td style=\"padding:6px 8px;color:#64748b;width:36%;font-weight:600;border-bottom:1px solid #f1f5f9;\">"
                + esc(label) + "</td><td style=\"padding:6px 8px;color:#0f172a;border-bottom:1px solid #f1f5f9;\">"
                + esc(val) + "</td></tr>";
    }

    private static String joinNonBlank(String sep, String... items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (s == null || s.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(sep);
            sb.append(s.trim());
        }
        return sb.toString();
    }

    private static String cleanUrl(String url, String fallback) {
        String val = (url == null || url.isBlank()) ? fallback : url.trim();
        return val.replaceAll("/+$", "");
    }

    private static String normalizeDigits(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record AdminRecipient(int id, String username, String displayName, String email, String phone) {}
}
