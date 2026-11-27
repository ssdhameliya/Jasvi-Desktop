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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Service for processing emergency one-click action links (Unlock and Password Reset).
 * Decoupled from controllers per clean data architecture rules.
 */
@Service
public class EmergencyActionService {
    private static final Logger LOG = LoggerFactory.getLogger(EmergencyActionService.class);

    private final JpaNativeRepository jdbc;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SmtpMailService mailService;
    private final BusinessWhatsappService whatsappService;

    public EmergencyActionService(
            JpaNativeRepository jdbc,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SmtpMailService mailService,
            BusinessWhatsappService whatsappService) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.whatsappService = whatsappService;
    }

    public record ExecutionResult(
            boolean success,
            String title,
            String message,
            String ticketId,
            String subtext
    ) {}

    public record IncidentStatus(
            boolean found,
            String ticketId,
            String username,
            String adminName,
            boolean locked,
            boolean forcePasswordChange,
            String lockReason,
            String timestamp,
            String companyName,
            String appName
    ) {
        public IncidentStatus(boolean found, String ticketId, String username, String adminName,
                              boolean locked, boolean forcePasswordChange, String lockReason, String timestamp) {
            this(found, ticketId, username, adminName, locked, forcePasswordChange, lockReason, timestamp, "Company", "Company ERP");
        }
    }

    public String companyName() {
        if (mailService != null) {
            String name = mailService.companyName();
            if (name != null && !name.isBlank()) return name.trim();
        }
        return "Company";
    }

    public String appName() {
        return companyName() + " ERP";
    }

    @Transactional(readOnly = true)
    public IncidentStatus getIncidentStatus(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return new IncidentStatus(false, null, null, null, false, false, null, null, companyName(), appName());
        }
        var rows = jdbc.queryForList(
                "SELECT e.user_id, u.username, u.full_name, u.locked, u.lock_reason, u.force_password_change " +
                "FROM emergency_security_action e JOIN users u ON e.user_id = u.id " +
                "WHERE e.ticket_id = ? ORDER BY e.id DESC LIMIT 1",
                ticketId.trim()
        );
        if (rows.isEmpty()) {
            return new IncidentStatus(false, ticketId.trim(), null, null, false, false, null, null, companyName(), appName());
        }
        var r = rows.get(0);
        boolean locked = Integer.valueOf(1).equals(r.get("locked")) || Boolean.TRUE.equals(r.get("locked"));
        boolean forcePw = Integer.valueOf(1).equals(r.get("force_password_change"));
        String uname = String.valueOf(r.get("username"));
        String fname = r.get("full_name") != null ? String.valueOf(r.get("full_name")) : uname;
        String reason = r.get("lock_reason") != null ? String.valueOf(r.get("lock_reason")) : "NONE";
        return new IncidentStatus(true, ticketId.trim(), uname, fname, locked, forcePw, reason, BusinessClock.nowUtcText(), companyName(), appName());
    }

    @Transactional
    public ExecutionResult executeEmergencyAction(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return new ExecutionResult(
                    false,
                    "Invalid Action Request",
                    "Security token parameter is missing from the request URL.",
                    null, null
            );
        }

        String tokenHash = sha256Hex(rawToken.trim());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, action_type, ticket_id, expires_at, used FROM emergency_security_action " +
                "WHERE token_hash = ?",
                tokenHash
        );

        if (rows.isEmpty()) {
            return new ExecutionResult(
                    false,
                    "Security Token Not Found",
                    "This emergency security link does not exist or was forged. No matching action found.",
                    null, null
            );
        }

        Map<String, Object> action = rows.get(0);
        Long actionId = ((Number) action.get("id")).longValue();
        Integer userId = ((Number) action.get("user_id")).intValue();
        String actionType = String.valueOf(action.get("action_type"));
        String ticketId = String.valueOf(action.get("ticket_id"));
        boolean used = Boolean.TRUE.equals(action.get("used")) || Integer.valueOf(1).equals(action.get("used"));

        if (used) {
            return new ExecutionResult(
                    false,
                    "Action Link Already Used",
                    "This emergency action link (Ticket #" + ticketId + ") has already been executed and cannot be reused.",
                    ticketId, null
            );
        }

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return new ExecutionResult(
                    false,
                    "User Account Not Found",
                    "The target administrator account associated with Ticket #" + ticketId + " no longer exists in the database.",
                    ticketId, null
            );
        }

        String adminName = user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName().trim() : user.getUsername();

        if ("UNLOCK".equalsIgnoreCase(actionType)) {
            // Option A: Unlock administrator account and clear failure counters
            jdbc.update(
                    "UPDATE users SET locked = 0, failed_attempts = 0, mfa_failed_attempts = 0, lock_reason = 'NONE' WHERE id = ?",
                    userId
            );
            jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE ?", "%|" + user.getUsername().toLowerCase(java.util.Locale.ROOT));
            jdbc.update(
                    "UPDATE emergency_security_action SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE id = ?",
                    actionId
            );
            jdbc.update(
                    "UPDATE emergency_security_action SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE ticket_id = ? AND id <> ?",
                    ticketId, actionId
            );

            jdbc.update(
                    "INSERT INTO activity_log(entity_type, entity_id, action, detail, created_by, created_at) " +
                    "VALUES('USER', ?, 'ACCOUNT_EMERGENCY_UNLOCKED', 'Account unlocked by Super Admin via emergency one-click action', 'SUPER_ADMIN', ?)",
                    userId, BusinessClock.nowUtcText()
            );

            notifyAdminUnlocked(user, ticketId);

            return new ExecutionResult(
                    true,
                    "Administrator Account Successfully Unlocked",
                    "Administrator <strong>" + adminName + "</strong> (" + user.getUsername() + ") has been unlocked in PostgreSQL. " +
                    "The administrator retains their existing password and can sign in immediately on the Desktop workstation.",
                    ticketId, "Status: ACTIVE & UNLOCKED"
            );

        } else if ("RESET_PASSWORD".equalsIgnoreCase(actionType)) {
            // Option B: Unlock and reset to secure temporary password with mandatory first-login update
            String tempPassword = generateSecureTempPassword();
            String passwordHash = passwordEncoder.encode(tempPassword);
            String nowUtc = BusinessClock.nowUtcText();

            jdbc.update(
                    "UPDATE users SET password = ?, locked = 0, failed_attempts = 0, mfa_failed_attempts = 0, lock_reason = 'NONE', last_password_reset_utc = ?, force_password_change = 1 WHERE id = ?",
                    passwordHash, nowUtc, userId
            );
            jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE ?", "%|" + user.getUsername().toLowerCase(java.util.Locale.ROOT));
            jdbc.update(
                    "UPDATE emergency_security_action SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE id = ?",
                    actionId
            );
            jdbc.update(
                    "UPDATE emergency_security_action SET used = TRUE, used_at = CURRENT_TIMESTAMP WHERE ticket_id = ? AND id <> ?",
                    ticketId, actionId
            );

            jdbc.update(
                    "INSERT INTO user_password_history (user_id, password_hash, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                    userId, passwordHash
            );

            jdbc.update(
                    "INSERT INTO activity_log(entity_type, entity_id, action, detail, created_by, created_at) " +
                    "VALUES('USER', ?, 'ACCOUNT_EMERGENCY_PASSWORD_RESET', 'Password reset and account unlocked by Super Admin via emergency one-click action', 'SUPER_ADMIN', ?)",
                    userId, nowUtc
            );

            notifyAdminPasswordReset(user, ticketId, tempPassword);

            return new ExecutionResult(
                    true,
                    "Password Reset & Account Unlocked",
                    "Administrator <strong>" + adminName + "</strong> (" + user.getUsername() + ") has been unlocked and assigned a secure temporary password. " +
                    "Mandatory password change has been enforced on first login. The temporary credentials have been dispatched directly to the administrator's registered email.",
                    ticketId, "Temporary Password Dispatched to " + user.getEmail()
            );
        }

        return new ExecutionResult(
                false,
                "Unknown Action Type",
                "Unrecognized emergency action type: " + actionType,
                ticketId, null
        );
    }

    private void notifyAdminUnlocked(UserEntity user, String ticketId) {
        Thread.ofVirtual().start(() -> {
            try {
                String email = user.getEmail();
                String adminDisplayName = user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : user.getUsername();
                var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
                String brand = companyName();
                String app = brand + " ERP";
                Map<String, String> tokens = Map.of(
                        "AppName", app,
                        "CompanyName", brand,
                        "TicketId", ticketId == null ? "" : ticketId,
                        "FullName", adminDisplayName,
                        "Username", user.getUsername() == null ? "" : user.getUsername()
                );
                if (email != null && !email.isBlank()) {
                    var rendered = templates.renderEmail(
                            "security_unlock",
                            tokens,
                            new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                                    "#16a34a", "#15803d",
                                    "Ticket #" + ticketId + " • RESOLVED",
                                    "🎉 Account Successfully Unlocked",
                                    List.of(),
                                    brand + " Autonomous Security Sentinel • Incident Ticket #" + ticketId
                            )
                    );
                    mailService.sendBusiness(email, rendered.subject(), rendered.body());
                }
                String phone = user.getPhone();
                if (phone != null && !phone.isBlank()) {
                    String msg = templates.renderWhatsapp("security_unlock", tokens);
                    whatsappService.sendText(new WahaDtos.TextSendRequest(phone, msg, null));
                }
            } catch (Exception ex) {
                LOG.error("Failed to dispatch unlock confirmation to admin {}: {}", user.getUsername(), ex.getMessage());
            }
        });
    }

    private void notifyAdminPasswordReset(UserEntity user, String ticketId, String tempPassword) {
        Thread.ofVirtual().start(() -> {
            try {
                String email = user.getEmail();
                String adminDisplayName = user.getFullName() != null && !user.getFullName().isBlank() ? user.getFullName() : user.getUsername();
                var templates = org.example.server.communication.ServerNotificationTemplateService.forJdbc(jdbc);
                String brand = companyName();
                String app = brand + " ERP";
                Map<String, String> tokens = Map.of(
                        "AppName", app,
                        "CompanyName", brand,
                        "TicketId", ticketId == null ? "" : ticketId,
                        "FullName", adminDisplayName,
                        "Username", user.getUsername() == null ? "" : user.getUsername(),
                        "TempPassword", tempPassword == null ? "" : tempPassword,
                        "UserEmail", email == null ? "" : email
                );
                if (email != null && !email.isBlank()) {
                    var rendered = templates.renderEmail(
                            "security_recovery_reset",
                            tokens,
                            new org.example.server.communication.ServerNotificationTemplateService.CardStyle(
                                    "#d97706", "#b45309",
                                    "Ticket #" + ticketId + " • ACTION REQUIRED",
                                    "🔑 Temporary Password Dispatched",
                                    List.of(),
                                    brand + " Autonomous Security Sentinel • Incident Ticket #" + ticketId
                            )
                    );
                    mailService.sendBusiness(email, rendered.subject(), rendered.body());
                }
                String phone = user.getPhone();
                if (phone != null && !phone.isBlank()) {
                    String msg = templates.renderWhatsapp("security_recovery_reset", tokens);
                    whatsappService.sendText(new WahaDtos.TextSendRequest(phone, msg, null));
                }
            } catch (Exception ex) {
                LOG.error("Failed to dispatch password reset notification to admin {}: {}", user.getUsername(), ex.getMessage());
            }
        });
    }

    private static String generateSecureTempPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnopqrstuvwxyz";
        String digits = "23456789";
        String special = "!@#$%^&*";
        SecureRandom sr = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(sr.nextInt(upper.length())));
        sb.append(upper.charAt(sr.nextInt(upper.length())));
        sb.append(lower.charAt(sr.nextInt(lower.length())));
        sb.append(lower.charAt(sr.nextInt(lower.length())));
        sb.append(digits.charAt(sr.nextInt(digits.length())));
        sb.append(digits.charAt(sr.nextInt(digits.length())));
        sb.append(special.charAt(sr.nextInt(special.length())));
        sb.append(special.charAt(sr.nextInt(special.length())));
        String all = upper + lower + digits + special;
        while (sb.length() < 12) {
            sb.append(all.charAt(sr.nextInt(all.length())));
        }
        return sb.toString();
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
