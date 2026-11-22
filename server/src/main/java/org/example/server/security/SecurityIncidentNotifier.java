package org.example.server.security;

import org.example.server.auth.SmtpMailService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.UserEntity;
import org.example.server.persistence.repository.UserRepository;
import org.example.server.util.BusinessClock;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Autonomous Emergency Security Incident Notifier (Rule 17: Zero Mock Data).
 * Decoupled from transactional database lock paths (Rule 10).
 * Dispatches authentic HTML emails and WhatsApp messages to Super Admin with single-use action tokens,
 * and delivers real-time advisory notices to the locked administrator.
 */
@Service
public class SecurityIncidentNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityIncidentNotifier.class);

    private final UserRepository userRepository;
    private final JpaNativeRepository jdbc;
    private final SmtpMailService mailService;
    private final BusinessWhatsappService whatsappService;
    private final TransactionTemplate tx;
    private final String serverBaseUrl;

    public SecurityIncidentNotifier(
            UserRepository userRepository,
            JpaNativeRepository jdbc,
            SmtpMailService mailService,
            BusinessWhatsappService whatsappService,
            PlatformTransactionManager transactionManager,
            @Value("${dse.server.base-url:}") String serverBaseUrl,
            @Value("${server.port:8080}") int serverPort) {
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.mailService = mailService;
        this.whatsappService = whatsappService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (serverBaseUrl != null && !serverBaseUrl.isBlank()) {
            this.serverBaseUrl = serverBaseUrl.trim().replaceAll("/+$", "");
        } else {
            this.serverBaseUrl = "http://127.0.0.1:" + (serverPort <= 0 ? 8080 : serverPort);
        }
    }

    /**
     * Synchronously generates incident ticket & tokens in PostgreSQL, returning the ticketId immediately,
     * while dispatching notifications asynchronously in a virtual thread.
     */
    public String notifyAccountLockedAsync(UserEntity lockedAdmin, String lockReason, String detail, String clientIp) {
        if (lockedAdmin == null) return null;
        List<UserEntity> superAdmins = userRepository.findActiveSuperAdmins();
        if (superAdmins.isEmpty()) {
            LOG.warn("Security Incident: No active SUPER_ADMIN found in database. Local account locked without external notification.");
            return null;
        }
        UserEntity superAdmin = superAdmins.get(0);

        String ticketId = "SEC-" + String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 999999));
        String rawUnlockToken = generateCryptoToken();
        String rawResetToken = generateCryptoToken();

        String unlockTokenHash = sha256Hex(rawUnlockToken);
        String resetTokenHash = sha256Hex(rawResetToken);

        String ip = clientIp == null || clientIp.isBlank() ? "Local Workstation" : clientIp.trim();
        String timestamp = BusinessClock.nowUtcText();

        tx.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO emergency_security_action (token_hash, user_id, action_type, ticket_id, client_ip, expires_at, used) " +
                    "VALUES (?, ?, 'UNLOCK', ?, ?, CURRENT_TIMESTAMP + INTERVAL '24 HOURS', FALSE)",
                    unlockTokenHash, lockedAdmin.getId(), ticketId, ip
            );
            jdbc.update(
                    "INSERT INTO emergency_security_action (token_hash, user_id, action_type, ticket_id, client_ip, expires_at, used) " +
                    "VALUES (?, ?, 'RESET_PASSWORD', ?, ?, CURRENT_TIMESTAMP + INTERVAL '24 HOURS', FALSE)",
                    resetTokenHash, lockedAdmin.getId(), ticketId, ip
            );
        });

        Thread.ofVirtual().name("sec-incident-alert-" + ticketId).start(() -> {
            try {
                dispatchAllAlerts(lockedAdmin, superAdmin, lockReason, detail, ip, timestamp, ticketId, rawUnlockToken, rawResetToken);
            } catch (Exception ex) {
                LOG.error("Failed to execute emergency incident recovery notifications for ticket #{}: {}",
                        ticketId, ex.getMessage(), ex);
            }
        });

        return ticketId;
    }

    private void dispatchAllAlerts(UserEntity lockedAdmin, UserEntity superAdmin, String lockReason, String detail,
                                   String ip, String timestamp, String ticketId, String rawUnlockToken, String rawResetToken) {
        String superAdminEmail = superAdmin.getEmail();
        boolean hasSuperAdminEmail = superAdminEmail != null && !superAdminEmail.trim().isBlank();

        String superAdminPhone = superAdmin.getPhone();
        boolean hasSuperAdminPhone = superAdminPhone != null && !superAdminPhone.trim().isBlank();

        String adminEmail = lockedAdmin.getEmail();
        boolean hasAdminEmail = adminEmail != null && !adminEmail.trim().isBlank();

        String adminPhone = lockedAdmin.getPhone();
        boolean hasAdminPhone = adminPhone != null && !adminPhone.trim().isBlank();

        String displayPhone = hasAdminPhone ? adminPhone.trim() : "Not Registered in Profile";
        String displayEmail = hasAdminEmail ? adminEmail.trim() : "Not Registered in Profile";

        String unlockUrl = serverBaseUrl + "/api/security/emergency/action?token=" + rawUnlockToken;
        String resetUrl = serverBaseUrl + "/api/security/emergency/action?token=" + rawResetToken;

        String adminDisplayName = lockedAdmin.getFullName() != null && !lockedAdmin.getFullName().isBlank()
                ? lockedAdmin.getFullName().trim() : lockedAdmin.getUsername();
        String branch = lockedAdmin.getBranch() != null && !lockedAdmin.getBranch().isBlank() ? lockedAdmin.getBranch().trim() : "Main";
        String department = lockedAdmin.getDepartment() != null && !lockedAdmin.getDepartment().isBlank() ? lockedAdmin.getDepartment().trim() : "Administration";

        // Step 4: Dispatch Super Admin Alerts (Email + WhatsApp) with independent fault isolation
        if (hasSuperAdminEmail) {
            try {
                dispatchSuperAdminEmail(superAdminEmail.trim(), ticketId, adminDisplayName, lockedAdmin.getUsername(),
                        displayEmail, displayPhone, branch, department, lockReason, ip, timestamp, unlockUrl, resetUrl);
            } catch (Throwable t) {
                LOG.error("Failed to dispatch Super Admin email: {}", t.getMessage(), t);
            }
        }

        if (hasSuperAdminPhone) {
            try {
                dispatchSuperAdminWhatsapp(superAdminPhone.trim(), ticketId, adminDisplayName, lockedAdmin.getUsername(),
                        displayEmail, displayPhone, branch, department, lockReason, ip, timestamp, unlockUrl, resetUrl);
            } catch (Throwable t) {
                LOG.error("Failed to dispatch Super Admin WhatsApp alert: {}", t.getMessage(), t);
            }
        }

        // Step 5: Dispatch Locked Admin Advisory Alerts (Email + WhatsApp) with independent fault isolation
        if (hasAdminEmail) {
            try {
                dispatchLockedAdminEmail(adminEmail.trim(), ticketId, adminDisplayName, lockedAdmin.getUsername(),
                        hasSuperAdminEmail ? superAdminEmail.trim() : "Super Admin",
                        hasSuperAdminPhone ? superAdminPhone.trim() : "", timestamp);
            } catch (Throwable t) {
                LOG.error("Failed to dispatch locked Admin advisory email: {}", t.getMessage(), t);
            }
        }

        if (hasAdminPhone) {
            try {
                dispatchLockedAdminWhatsapp(adminPhone.trim(), ticketId, adminDisplayName, lockedAdmin.getUsername(),
                        hasSuperAdminEmail ? superAdminEmail.trim() : "Super Admin",
                        hasSuperAdminPhone ? superAdminPhone.trim() : "", timestamp);
            } catch (Throwable t) {
                LOG.error("Failed to dispatch locked Admin advisory WhatsApp: {}", t.getMessage(), t);
            }
        }

        LOG.info("Emergency incident recovery process completed for ticket #{} (Locked Admin: {}, Super Admin: {})",
                ticketId, lockedAdmin.getUsername(), superAdmin.getUsername());
    }

    private String companyName() {
        if (mailService != null) {
            String name = mailService.companyName();
            if (name != null && !name.isBlank()) return name.trim();
        }
        return "Company";
    }

    private String appName() {
        return companyName() + " ERP";
    }

    private void dispatchSuperAdminEmail(String recipient, String ticketId, String adminName, String username,
                                         String email, String phone, String branch, String department,
                                         String reason, String ip, String timestamp,
                                         String unlockUrl, String resetUrl) {
        String subject = "🚨 URGENT: " + appName() + " Administrator Account Locked [Ticket #" + ticketId + "]";
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }
                        .card { max-width: 620px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.05); }
                        .header { background: linear-gradient(135deg, #b91c1c, #991b1b); color: #ffffff; padding: 20px 24px; }
                        .header h2 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; }
                        .badge { display: inline-block; background: rgba(255,255,255,0.2); padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; }
                        .content { padding: 24px; }
                        .meta-table { width: 100%; border-collapse: collapse; margin: 16px 0; }
                        .meta-table td { padding: 8px 10px; font-size: 13.5px; border-bottom: 1px solid #f1f5f9; }
                        .meta-label { color: #64748b; font-weight: 600; width: 35%; }
                        .meta-value { color: #0f172a; font-weight: 500; }
                        .actions-container { margin-top: 24px; padding-top: 20px; border-top: 1px solid #e2e8f0; }
                        .btn { display: block; text-align: center; padding: 12px 18px; border-radius: 8px; font-weight: 600; font-size: 14px; text-decoration: none; margin-bottom: 12px; }
                        .btn-unlock { background-color: #2563eb; color: #ffffff !important; }
                        .btn-reset { background-color: #d97706; color: #ffffff !important; }
                        .footer { background: #f8fafc; padding: 16px 24px; font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="header">
                            <h2>🚨 Security Incident: Administrator Account Locked</h2>
                            <span class="badge">Ticket #{{TICKET_ID}} • CRITICAL PRIORITY</span>
                        </div>
                        <div class="content">
                            <p style="margin: 0 0 16px 0; font-size: 14px; line-height: 1.5;">An Administrator account has been temporarily locked in the {{APP_NAME}} system due to repeated authentication failures.</p>
                            
                            <table class="meta-table">
                                <tr><td class="meta-label">Administrator Name</td><td class="meta-value"><strong>{{ADMIN_NAME}}</strong> (User: {{USERNAME}})</td></tr>
                                <tr><td class="meta-label">Registered Email</td><td class="meta-value">{{EMAIL}}</td></tr>
                                <tr><td class="meta-label">Registered Phone</td><td class="meta-value">{{PHONE}}</td></tr>
                                <tr><td class="meta-label">Branch & Dept</td><td class="meta-value">{{BRANCH}} | {{DEPARTMENT}}</td></tr>
                                <tr><td class="meta-label">Lock Reason</td><td class="meta-value" style="color: #dc2626;"><strong>{{REASON}}</strong></td></tr>
                                <tr><td class="meta-label">Workstation / IP</td><td class="meta-value">{{IP}}</td></tr>
                                <tr><td class="meta-label">Incident Time</td><td class="meta-value">{{TIMESTAMP}}</td></tr>
                            </table>

                            <div class="actions-container">
                                <p style="margin: 0 0 14px 0; font-size: 13.5px; font-weight: 600; color: #334155;">Choose an Immediate Resolution Action:</p>
                                <a href="{{UNLOCK_URL}}" class="btn btn-unlock" target="_blank">🔓 Unlock Admin Account (Preserve Existing Password)</a>
                                <a href="{{RESET_URL}}" class="btn btn-reset" target="_blank">🔑 Unlock & Reset to Secure Temporary Password</a>
                            </div>
                        </div>
                        <div class="footer">
                            {{COMPANY_NAME}} Autonomous Security Sentinel • Single-use cryptographic tokens (24h expiry).
                        </div>
                    </div>
                </body>
                </html>
                """
                .replace("{{TICKET_ID}}", ticketId)
                .replace("{{ADMIN_NAME}}", adminName)
                .replace("{{USERNAME}}", username)
                .replace("{{EMAIL}}", email)
                .replace("{{PHONE}}", phone)
                .replace("{{BRANCH}}", branch)
                .replace("{{DEPARTMENT}}", department)
                .replace("{{REASON}}", reason)
                .replace("{{IP}}", ip)
                .replace("{{TIMESTAMP}}", timestamp)
                .replace("{{UNLOCK_URL}}", unlockUrl)
                .replace("{{RESET_URL}}", resetUrl)
                .replace("{{APP_NAME}}", appName())
                .replace("{{COMPANY_NAME}}", companyName());

        try {
            mailService.sendBusiness(recipient, subject, html);
            LOG.info("Super Admin emergency incident email successfully sent to {}", recipient);
        } catch (Exception ex) {
            LOG.error("Failed to deliver Super Admin emergency incident email to {}: {}", recipient, ex.getMessage(), ex);
        }
    }

    private void dispatchSuperAdminWhatsapp(String recipientPhone, String ticketId, String adminName, String username,
                                            String email, String phone, String branch, String department,
                                            String reason, String ip, String timestamp,
                                            String unlockUrl, String resetUrl) {
        String msg = """
                🚨 *URGENT: {{APP_NAME}} Administrator Account Locked*
                *Ticket ID:* #{{TICKET_ID}}
                *Priority:* Critical

                An Administrator account has been locked:
                • *Admin:* {{ADMIN_NAME}} (User: {{USERNAME}})
                • *Email:* {{EMAIL}}
                • *Mobile:* {{PHONE}}
                • *Branch / Dept:* {{BRANCH}} | {{DEPARTMENT}}
                • *Reason:* {{REASON}}
                • *Time:* {{TIMESTAMP}}
                • *Workstation:* {{IP}}

                *Choose an immediate action below (Single-use, 24h validity):*

                🔓 *Option 1: Unlock Account (Preserve Password)*
                {{UNLOCK_URL}}

                🔑 *Option 2: Unlock & Reset to Temporary Password*
                {{RESET_URL}}

                _{{APP_NAME}} Autonomous Security Incident Engine_
                """
                .replace("{{TICKET_ID}}", ticketId)
                .replace("{{ADMIN_NAME}}", adminName)
                .replace("{{USERNAME}}", username)
                .replace("{{EMAIL}}", email)
                .replace("{{PHONE}}", phone)
                .replace("{{BRANCH}}", branch)
                .replace("{{DEPARTMENT}}", department)
                .replace("{{REASON}}", reason)
                .replace("{{TIMESTAMP}}", timestamp)
                .replace("{{IP}}", ip)
                .replace("{{UNLOCK_URL}}", unlockUrl)
                .replace("{{RESET_URL}}", resetUrl)
                .replace("{{APP_NAME}}", appName());

        try {
            whatsappService.sendText(new WahaDtos.TextSendRequest(recipientPhone, msg, null));
            LOG.info("Super Admin emergency WhatsApp alert successfully sent to {}", recipientPhone);
        } catch (Exception ex) {
            LOG.error("Failed to deliver Super Admin emergency WhatsApp alert to {}: {}", recipientPhone, ex.getMessage(), ex);
        }
    }

    private void dispatchLockedAdminEmail(String recipient, String ticketId, String adminName, String username,
                                         String superAdminEmail, String superAdminPhone, String timestamp) {
        String subject = "⚠️ Security Notice: Your Administrator Account is Locked [Ticket #" + ticketId + "]";
        String statusUrl = serverBaseUrl + "/api/security/emergency/status?ticket=" + ticketId;
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }
                        .card { max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.05); }
                        .header { background: linear-gradient(135deg, #d97706, #b45309); color: #ffffff; padding: 20px 24px; font-weight: 700; font-size: 18px; }
                        .content { padding: 24px; font-size: 14px; line-height: 1.6; }
                        .highlight-box { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 14px; border-radius: 4px; margin: 16px 0; }
                        .btn-status { display: block; text-align: center; background-color: #2563eb; color: #ffffff !important; padding: 12px 18px; border-radius: 8px; font-weight: 600; font-size: 14px; text-decoration: none; margin: 20px 0 10px 0; }
                        .footer { background: #f8fafc; padding: 14px 24px; font-size: 12px; color: #94a3b8; text-align: center; border-top: 1px solid #e2e8f0; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="header">
                            ⚠️ Administrator Account Security Lockout
                        </div>
                        <div class="content">
                            <p>Dear <strong>{{ADMIN_NAME}}</strong> ({{USERNAME}}),</p>
                            <p>Your {{APP_NAME}} Administrator account has been temporarily locked following consecutive failed sign-in verification attempts on <strong>{{TIMESTAMP}}</strong>.</p>
                            
                            <div class="highlight-box">
                                <strong>Incident Reference Ticket: #{{TICKET_ID}}</strong><br>
                                An emergency alert has been dispatched directly to Super Admin ({{SUPER_ADMIN_EMAIL}} / {{SUPER_ADMIN_PHONE}}).
                            </div>

                            <a href="{{STATUS_URL}}" class="btn-status" target="_blank">🔍 Track Live Unlock Status Online</a>

                            <p style="margin-top: 16px;"><strong>Next Steps:</strong></p>
                            <ul>
                                <li>Please allow up to <strong>30 minutes</strong> for Super Admin review and resolution.</li>
                                <li>Your Desktop login screen will automatically live-detect when the account is unlocked.</li>
                                <li>Once unlocked or reset, you will receive an immediate confirmation notice.</li>
                            </ul>
                        </div>
                        <div class="footer">
                            {{COMPANY_NAME}} Autonomous Security Sentinel • Ticket #{{TICKET_ID}}
                        </div>
                    </div>
                </body>
                </html>
                """
                .replace("{{ADMIN_NAME}}", adminName)
                .replace("{{USERNAME}}", username)
                .replace("{{TIMESTAMP}}", timestamp)
                .replace("{{TICKET_ID}}", ticketId)
                .replace("{{SUPER_ADMIN_EMAIL}}", superAdminEmail)
                .replace("{{SUPER_ADMIN_PHONE}}", superAdminPhone)
                .replace("{{STATUS_URL}}", statusUrl)
                .replace("{{APP_NAME}}", appName())
                .replace("{{COMPANY_NAME}}", companyName());

        try {
            mailService.sendBusiness(recipient, subject, html);
            LOG.info("Locked Admin advisory email successfully sent to {}", recipient);
        } catch (Exception ex) {
            LOG.error("Failed to deliver locked Admin advisory email to {}: {}", recipient, ex.getMessage(), ex);
        }
    }

    private void dispatchLockedAdminWhatsapp(String recipientPhone, String ticketId, String adminName, String username,
                                             String superAdminEmail, String superAdminPhone, String timestamp) {
        String statusUrl = serverBaseUrl + "/api/security/emergency/status?ticket=" + ticketId;
        String msg = """
                ⚠️ *Security Notice: Administrator Account Locked*
                *Ticket ID:* #{{TICKET_ID}}

                Dear {{ADMIN_NAME}} ({{USERNAME}}),
                Your {{APP_NAME}} Administrator account has been temporarily locked following consecutive failed authentication attempts.

                An urgent emergency incident report has been dispatched to Super Admin ({{SUPER_ADMIN_EMAIL}} / {{SUPER_ADMIN_PHONE}}).

                🔍 *Track Live Status Online:*
                {{STATUS_URL}}

                *Advisory:* Please allow up to 30 minutes for resolution. Keep your Desktop login screen open for live resolution tracking.

                _{{APP_NAME}} Autonomous Security Incident Engine_
                """
                .replace("{{TICKET_ID}}", ticketId)
                .replace("{{ADMIN_NAME}}", adminName)
                .replace("{{USERNAME}}", username)
                .replace("{{SUPER_ADMIN_EMAIL}}", superAdminEmail)
                .replace("{{SUPER_ADMIN_PHONE}}", superAdminPhone)
                .replace("{{STATUS_URL}}", statusUrl)
                .replace("{{APP_NAME}}", appName());

        try {
            whatsappService.sendText(new WahaDtos.TextSendRequest(recipientPhone, msg, null));
            LOG.info("Locked Admin advisory WhatsApp successfully sent to {}", recipientPhone);
        } catch (Exception ex) {
            LOG.error("Failed to deliver locked Admin advisory WhatsApp to {}: {}", recipientPhone, ex.getMessage(), ex);
        }
    }

    private static String generateCryptoToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes) + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
