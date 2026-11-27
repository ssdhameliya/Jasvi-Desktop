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
        return org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc).companyName();
    }

    private String appName() {
        return companyName() + " ERP";
    }

    private void dispatchSuperAdminEmail(String recipient, String ticketId, String adminName, String username,
                                         String email, String phone, String branch, String department,
                                         String reason, String ip, String timestamp,
                                         String unlockUrl, String resetUrl) {
        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String brand = templates.companyName();
        String app = brand + " ERP";
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("AppName", app);
        tokens.put("CompanyName", brand);
        tokens.put("TicketId", ticketId);
        tokens.put("AdminName", adminName);
        tokens.put("Username", username);
        tokens.put("AdminEmail", email);
        tokens.put("AdminPhone", phone);
        tokens.put("Branch", branch);
        tokens.put("Department", department);
        tokens.put("LockReason", reason);
        tokens.put("ClientIp", ip);
        tokens.put("Timestamp", timestamp);
        tokens.put("UnlockUrl", unlockUrl);
        tokens.put("ResetUrl", resetUrl);

        var rendered = templates.renderEmail(
                "security_lock_superadmin",
                tokens,
                new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                        "#b91c1c", "#991b1b",
                        "Ticket #" + ticketId + " • CRITICAL PRIORITY",
                        "🚨 Security Incident: Administrator Account Locked",
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.ActionButton(
                                        "🔓 Unlock Admin Account (Preserve Existing Password)", unlockUrl, "#2563eb"),
                                new org.example.shared.communication.TokenTemplateEngine.ActionButton(
                                        "🔑 Unlock & Reset to Secure Temporary Password", resetUrl, "#d97706")
                        ),
                        brand + " Autonomous Security Sentinel • Single-use cryptographic tokens (24h expiry)."
                )
        );

        try {
            mailService.sendBusiness(recipient, rendered.subject(), rendered.body());
            LOG.info("Super Admin emergency incident email successfully sent to {}", recipient);
        } catch (Exception ex) {
            LOG.error("Failed to deliver Super Admin emergency incident email to {}: {}", recipient, ex.getMessage(), ex);
        }
    }

    private void dispatchSuperAdminWhatsapp(String recipientPhone, String ticketId, String adminName, String username,
                                            String email, String phone, String branch, String department,
                                            String reason, String ip, String timestamp,
                                            String unlockUrl, String resetUrl) {
        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String brand = templates.companyName();
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("AppName", brand + " ERP");
        tokens.put("CompanyName", brand);
        tokens.put("TicketId", ticketId);
        tokens.put("AdminName", adminName);
        tokens.put("Username", username);
        tokens.put("AdminEmail", email);
        tokens.put("AdminPhone", phone);
        tokens.put("Branch", branch);
        tokens.put("Department", department);
        tokens.put("LockReason", reason);
        tokens.put("ClientIp", ip);
        tokens.put("Timestamp", timestamp);
        tokens.put("UnlockUrl", unlockUrl);
        tokens.put("ResetUrl", resetUrl);
        String msg = templates.renderWhatsapp("security_lock_superadmin", tokens);

        sendWhatsappAlert("Super Admin emergency", recipientPhone, msg);
    }

    private void dispatchLockedAdminEmail(String recipient, String ticketId, String adminName, String username,
                                         String superAdminEmail, String superAdminPhone, String timestamp) {
        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String brand = templates.companyName();
        String statusUrl = serverBaseUrl + "/api/security/emergency/status?ticket=" + ticketId;
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("AppName", brand + " ERP");
        tokens.put("CompanyName", brand);
        tokens.put("TicketId", ticketId);
        tokens.put("AdminName", adminName);
        tokens.put("Username", username);
        tokens.put("SuperAdminEmail", superAdminEmail);
        tokens.put("SuperAdminPhone", superAdminPhone);
        tokens.put("Timestamp", timestamp);
        tokens.put("StatusUrl", statusUrl);

        var rendered = templates.renderEmail(
                "security_lock_admin",
                tokens,
                new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                        "#d97706", "#b45309",
                        "Incident Reference Ticket #" + ticketId,
                        "⚠️ Administrator Account Security Lockout",
                        List.of(new org.example.shared.communication.TokenTemplateEngine.ActionButton(
                                "🔍 Track Live Unlock Status Online", statusUrl, "#2563eb")),
                        brand + " Autonomous Security Sentinel • Ticket #" + ticketId
                )
        );

        try {
            mailService.sendBusiness(recipient, rendered.subject(), rendered.body());
            LOG.info("Locked Admin advisory email successfully sent to {}", recipient);
        } catch (Exception ex) {
            LOG.error("Failed to deliver locked Admin advisory email to {}: {}", recipient, ex.getMessage(), ex);
        }
    }

    private void dispatchLockedAdminWhatsapp(String recipientPhone, String ticketId, String adminName, String username,
                                             String superAdminEmail, String superAdminPhone, String timestamp) {
        var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
        String brand = templates.companyName();
        String statusUrl = serverBaseUrl + "/api/security/emergency/status?ticket=" + ticketId;
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        tokens.put("AppName", brand + " ERP");
        tokens.put("CompanyName", brand);
        tokens.put("TicketId", ticketId);
        tokens.put("AdminName", adminName);
        tokens.put("Username", username);
        tokens.put("SuperAdminEmail", superAdminEmail);
        tokens.put("SuperAdminPhone", superAdminPhone);
        tokens.put("Timestamp", timestamp);
        tokens.put("StatusUrl", statusUrl);
        String msg = templates.renderWhatsapp("security_lock_admin", tokens);

        sendWhatsappAlert("Locked Admin advisory", recipientPhone, msg);
    }

    private void sendWhatsappAlert(String roleLabel, String recipientPhone, String msg) {
        try {
            WahaDtos.SendResponse response = whatsappService.sendText(new WahaDtos.TextSendRequest(recipientPhone, msg, null));
            if (response != null && response.success()) {
                LOG.info("{} WhatsApp alert successfully sent to {}", roleLabel, recipientPhone);
            } else {
                String reason = response == null || response.message() == null || response.message().isBlank()
                        ? "WAHA did not confirm delivery" : response.message();
                LOG.warn("{} WhatsApp alert NOT delivered to {}: {}", roleLabel, recipientPhone, reason);
            }
        } catch (Exception ex) {
            LOG.error("Failed to deliver {} WhatsApp alert to {}: {}", roleLabel, recipientPhone, ex.getMessage(), ex);
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
