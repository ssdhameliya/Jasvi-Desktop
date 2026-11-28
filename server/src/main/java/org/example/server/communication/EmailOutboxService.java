package org.example.server.communication;

import org.example.server.audit.AuditService;
import org.example.server.auth.EmailDeliveryException;
import org.example.server.auth.SmtpMailService;
import org.example.server.auth.SmtpMailService.Attachment;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.security.CurrentUser;
import org.example.server.util.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Persistent Email Outbox, audit logger, and exponential-backoff retry engine.
 * Every outbound SMTP invocation runs outside open DB transactions (Rule 10),
 * records EMAIL_SENT / EMAIL_FAILED in {@code audit_event} via REQUIRES_NEW, and
 * automatically retries transient SMTP failures in the background.
 */
@Service
public class EmailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    public record OutboxSendRequest(
            String recipientTo,
            String recipientCc,
            String recipientBcc,
            String subject,
            String body,
            List<Attachment> attachments,
            Attachment signatureImage,
            String customFromEmail,
            String customFromName,
            String entityType,
            Long entityId,
            String referenceNo,
            String templateKey
    ) {}

    public record OutboxItemDto(
            long id,
            String channel,
            String recipientTo,
            String recipientCc,
            String recipientBcc,
            String subject,
            String status,
            int attemptCount,
            int maxAttempts,
            String nextRetryAt,
            String lastError,
            String entityType,
            Long entityId,
            String referenceNo,
            String templateKey,
            String createdBy,
            String createdAt,
            String sentAt
    ) {}

    public record OutboxSummary(long total, long sent, long retrying, long pending, long failed) {}

    public record OutboxPage(
            List<OutboxItemDto> rows,
            OutboxSummary summary,
            int page,
            int size,
            long totalRows,
            int totalPages
    ) {}

    public record RetryResult(boolean success, String status, String message) {}

    private final JpaNativeRepository db;
    private final ObjectProvider<SmtpMailService> mailProvider;
    private final ObjectProvider<AuditService> auditProvider;
    private final TransactionTemplate requiresNewTx;

    public EmailOutboxService(
            JpaNativeRepository db,
            ObjectProvider<SmtpMailService> mailProvider,
            ObjectProvider<AuditService> auditProvider,
            PlatformTransactionManager txManager) {
        this.db = db;
        this.mailProvider = mailProvider;
        this.auditProvider = auditProvider;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Sends immediately via SMTP while recording outbox state, defaults CC/BCC, and audit events. */
    public void sendWithOutbox(OutboxSendRequest req) {
        SmtpMailService mail = mailProvider != null ? mailProvider.getIfAvailable() : null;
        if (mail == null) {
            throw new IllegalStateException("SMTP mail service is unavailable");
        }
        String actor = CurrentUser.isSet() ? CurrentUser.require().username() : "SYSTEM";
        String mergedCc = mergeEmails(req.recipientCc(), setting("email.default.cc", ""));
        String mergedBcc = mergeEmails(req.recipientBcc(), setting("email.default.bcc", ""));
        boolean isHtml = SmtpMailService.isHtmlContent(req.body());
        int maxAttempts = Math.max(1, Math.min(6, intSetting("email.outbox.maxRetries", 3)));

        String primaryAttachName = null;
        String primaryAttachB64 = null;
        if (req.attachments() != null && !req.attachments().isEmpty()) {
            Attachment first = req.attachments().getFirst();
            if (first != null && first.data() != null && first.data().length > 0) {
                primaryAttachName = first.name();
                primaryAttachB64 = Base64.getEncoder().encodeToString(first.data());
            }
        }
        String sigName = req.signatureImage() != null && req.signatureImage().data() != null && req.signatureImage().data().length > 0
                ? req.signatureImage().name() : null;
        String sigB64 = sigName != null
                ? Base64.getEncoder().encodeToString(req.signatureImage().data()) : null;

        Long outboxId = insertOutboxRow(
                req.recipientTo(), mergedCc, mergedBcc, req.subject(), req.body(), isHtml,
                req.customFromEmail(), req.customFromName(), primaryAttachName, primaryAttachB64,
                sigName, sigB64, req.entityType(), req.entityId(), req.referenceNo(), req.templateKey(),
                maxAttempts, actor
        );

        try {
            mail.sendBusinessWithCopies(
                    req.recipientTo(), mergedCc, mergedBcc, req.subject(), req.body(),
                    req.attachments(), req.signatureImage(), req.customFromEmail(), req.customFromName()
            );
            markSent(outboxId, 1);
            logAudit(req.entityType(), req.entityId(), outboxId, "EMAIL_SENT",
                    "Email delivered to " + safe(req.recipientTo())
                            + (mergedCc.isBlank() ? "" : " (CC: " + mergedCc + ")")
                            + " • " + safe(req.subject()));
        } catch (Exception ex) {
            String errMsg = extractAdminError(ex);
            boolean outboxEnabled = !"false".equalsIgnoreCase(setting("email.outbox.enabled", "true"));
            boolean transientErr = isTransientSmtpError(ex, errMsg);
            boolean canRetry = outboxEnabled && transientErr && maxAttempts > 1;
            if (canRetry) {
                markRetrying(outboxId, 1, computeBackoffSeconds(1), errMsg);
            } else {
                markFailed(outboxId, 1, errMsg);
                raiseAdminFailureAlert(outboxId, req.recipientTo(), req.subject(), errMsg, req.entityType(), req.entityId(), req.referenceNo());
            }
            logAudit(req.entityType(), req.entityId(), outboxId, "EMAIL_FAILED",
                    "Email failed for " + safe(req.recipientTo()) + ": " + errMsg);
            if (ex instanceof EmailDeliveryException ede) {
                throw ede;
            }
            throw ex instanceof RuntimeException re ? re : new IllegalStateException(errMsg, ex);
        }
    }

    /** Scheduled worker retrying due RETRYING / PENDING emails with exponential backoff. */
    @Scheduled(initialDelayString = "${dse.email.outbox-initial-delay-ms:45000}",
               fixedDelayString = "${dse.email.outbox-poll-ms:60000}")
    public void processPendingRetries() {
        if ("false".equalsIgnoreCase(setting("email.outbox.enabled", "true"))) {
            return;
        }
        SmtpMailService mail = mailProvider != null ? mailProvider.getIfAvailable() : null;
        if (mail == null || !mail.isConfigured()) {
            return;
        }
        List<Long> dueIds;
        try {
            dueIds = db.query("""
                    SELECT id FROM email_outbox
                    WHERE status IN ('PENDING', 'RETRYING')
                      AND next_retry_at IS NOT NULL
                      AND next_retry_at <= CURRENT_TIMESTAMP
                    ORDER BY next_retry_at ASC, id ASC
                    LIMIT 20
                    """, (rs, i) -> rs.getLong(1));
        } catch (Exception e) {
            log.debug("Email outbox polling skipped: {}", e.getMessage());
            return;
        }
        for (Long id : dueIds) {
            try {
                attemptDeliveryById(id, false);
            } catch (Exception ex) {
                log.debug("Outbox retry #{} finished with error: {}", id, ex.getMessage());
            }
        }
        cleanupOldAttachments();
    }

    public RetryResult retryNow(long id) {
        return attemptDeliveryById(id, true);
    }

    private RetryResult attemptDeliveryById(long id, boolean manualTrigger) {
        Map<String, Object> row = claimRowForSending(id, manualTrigger);
        if (row == null) {
            return new RetryResult(false, "SKIPPED", "Outbox item was already sent or is being processed.");
        }
        SmtpMailService mail = mailProvider != null ? mailProvider.getIfAvailable() : null;
        if (mail == null || !mail.isConfigured()) {
            markFailed(id, num(row.get("attempt_count")) + 1, "SMTP is not configured on server");
            return new RetryResult(false, "FAILED", "Server email SMTP is not configured.");
        }

        String to = str(row.get("recipient_to"));
        String cc = str(row.get("recipient_cc"));
        String bcc = str(row.get("recipient_bcc"));
        String subject = str(row.get("subject"));
        String body = str(row.get("body"));
        String senderEmail = blankToNull(str(row.get("sender_email")));
        String senderName = blankToNull(str(row.get("sender_name")));
        String entityType = blankToNull(str(row.get("entity_type")));
        Long entityId = row.get("entity_id") instanceof Number n ? n.longValue() : null;
        String refNo = blankToNull(str(row.get("reference_no")));
        int attempts = num(row.get("attempt_count")) + 1;
        int maxAttempts = Math.max(1, num(row.get("max_attempts")));

        List<Attachment> attachments = List.of();
        String attName = str(row.get("attachment_name"));
        String attB64 = str(row.get("attachment_b64"));
        if (!attB64.isBlank()) {
            try {
                attachments = List.of(new Attachment(attName.isBlank() ? "document.pdf" : attName, "application/octet-stream", Base64.getDecoder().decode(attB64)));
            } catch (Exception ignored) {}
        }
        Attachment sigAtt = null;
        String sigName = str(row.get("signature_name"));
        String sigB64 = str(row.get("signature_b64"));
        if (!sigB64.isBlank()) {
            try {
                sigAtt = new Attachment(sigName.isBlank() ? "signature.png" : sigName, "image/png", Base64.getDecoder().decode(sigB64));
            } catch (Exception ignored) {}
        }

        try {
            mail.sendBusinessWithCopies(to, cc, bcc, subject, body, attachments, sigAtt, senderEmail, senderName);
            markSent(id, attempts);
            logAudit(entityType, entityId, id, "EMAIL_SENT",
                    (manualTrigger ? "Manual resend succeeded to " : "Auto-retry #"+attempts+" succeeded to ") + to + " • " + subject);
            return new RetryResult(true, "SENT", "Email delivered to " + to + ".");
        } catch (Exception ex) {
            String errMsg = extractAdminError(ex);
            boolean transientErr = isTransientSmtpError(ex, errMsg);
            if (!manualTrigger && transientErr && attempts < maxAttempts) {
                markRetrying(id, attempts, computeBackoffSeconds(attempts), errMsg);
                logAudit(entityType, entityId, id, "EMAIL_FAILED", "Auto-retry #" + attempts + " failed for " + to + ": " + errMsg);
                return new RetryResult(false, "RETRYING", errMsg);
            }
            markFailed(id, attempts, errMsg);
            logAudit(entityType, entityId, id, "EMAIL_FAILED", "Delivery failed for " + to + ": " + errMsg);
            raiseAdminFailureAlert(id, to, subject, errMsg, entityType, entityId, refNo);
            return new RetryResult(false, "FAILED", errMsg);
        }
    }

    public OutboxPage listOutbox(String status, String q, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!st.isBlank() && !"ALL".equals(st)) {
            where.append(" AND UPPER(status) = ?");
            args.add(st);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND LOWER(CONCAT_WS(' ', recipient_to, recipient_cc, subject, reference_no, last_error, created_by)) LIKE ?");
            args.add("%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        long totalRows = Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM email_outbox" + where, Long.class, args.toArray())).orElse(0L);
        int totalPages = totalRows == 0 ? 0 : (int) Math.ceil(totalRows / (double) safeSize);
        if (totalPages > 0 && safePage >= totalPages) safePage = totalPages - 1;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<OutboxItemDto> items = db.query("""
                SELECT id, channel, recipient_to, COALESCE(recipient_cc,''), COALESCE(recipient_bcc,''),
                       COALESCE(subject,''), status, attempt_count, max_attempts,
                       COALESCE(CAST(next_retry_at AS TEXT),''), COALESCE(last_error,''),
                       COALESCE(entity_type,''), entity_id, COALESCE(reference_no,''),
                       COALESCE(template_key,''), COALESCE(created_by,''),
                       COALESCE(CAST(created_at AS TEXT),''), COALESCE(CAST(sent_at AS TEXT),'')
                FROM email_outbox
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new OutboxItemDto(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
                        rs.getString(10), rs.getString(11), rs.getString(12),
                        rs.getObject(13) instanceof Number n ? n.longValue() : null,
                        rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18)
                ),
                pageArgs.toArray());
        return new OutboxPage(items, summary(), safePage, safeSize, totalRows, totalPages);
    }

    public OutboxSummary summary() {
        try {
            Map<String, Object> m = db.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COALESCE(SUM(CASE WHEN status = 'SENT' THEN 1 ELSE 0 END), 0) AS sent,
                           COALESCE(SUM(CASE WHEN status = 'RETRYING' THEN 1 ELSE 0 END), 0) AS retrying,
                           COALESCE(SUM(CASE WHEN status IN ('PENDING','SENDING') THEN 1 ELSE 0 END), 0) AS pending,
                           COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed
                    FROM email_outbox
                    """);
            return new OutboxSummary(numLong(m.get("total")), numLong(m.get("sent")), numLong(m.get("retrying")),
                    numLong(m.get("pending")), numLong(m.get("failed")));
        } catch (Exception ignored) {
            return new OutboxSummary(0, 0, 0, 0, 0);
        }
    }

    private Long insertOutboxRow(String to, String cc, String bcc, String subject, String body, boolean isHtml,
                                 String senderEmail, String senderName, String attName, String attB64,
                                 String sigName, String sigB64, String entityType, Long entityId,
                                 String refNo, String templateKey, int maxAttempts, String actor) {
        try {
            return requiresNewTx.execute(s -> db.queryForObject("""
                    INSERT INTO email_outbox(
                        channel, recipient_to, recipient_cc, recipient_bcc, subject, body, is_html,
                        sender_email, sender_name, attachment_name, attachment_b64, signature_name, signature_b64,
                        entity_type, entity_id, reference_no, template_key, status, attempt_count, max_attempts,
                        created_by, created_at, updated_at
                    ) VALUES ('EMAIL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    RETURNING id
                    """, Long.class,
                    safe(to), cc, bcc, safe(subject), safeBody(body), isHtml,
                    senderEmail, senderName, attName, attB64, sigName, sigB64,
                    blankToNull(entityType), entityId, blankToNull(refNo), blankToNull(templateKey), maxAttempts, actor));
        } catch (Exception ex) {
            log.debug("Could not persist email_outbox row: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> claimRowForSending(long id, boolean manualTrigger) {
        return requiresNewTx.execute(s -> {
            String allowed = manualTrigger ? "status IN ('FAILED','RETRYING','PENDING')" : "status IN ('RETRYING','PENDING')";
            int updated = db.update("UPDATE email_outbox SET status = 'SENDING', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND " + allowed, id);
            if (updated != 1) return null;
            List<Map<String, Object>> rows = db.queryForList("SELECT * FROM email_outbox WHERE id = ?", id);
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    private void markSent(Long outboxId, int attempts) {
        if (outboxId == null) return;
        try {
            requiresNewTx.executeWithoutResult(s -> db.update("""
                    UPDATE email_outbox
                    SET status = 'SENT', attempt_count = ?, next_retry_at = NULL, last_error = NULL,
                        sent_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, attempts, outboxId));
        } catch (Exception ignored) {}
    }

    private void markRetrying(Long outboxId, int attempts, long backoffSeconds, String err) {
        if (outboxId == null) return;
        try {
            Instant next = Instant.now().plusSeconds(backoffSeconds);
            requiresNewTx.executeWithoutResult(s -> db.update("""
                    UPDATE email_outbox
                    SET status = 'RETRYING', attempt_count = ?, next_retry_at = ?, last_error = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, attempts, java.util.Date.from(next), trimError(err), outboxId));
        } catch (Exception ignored) {}
    }

    private void markFailed(Long outboxId, int attempts, String err) {
        if (outboxId == null) return;
        try {
            requiresNewTx.executeWithoutResult(s -> db.update("""
                    UPDATE email_outbox
                    SET status = 'FAILED', attempt_count = ?, next_retry_at = NULL, last_error = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, attempts, trimError(err), outboxId));
        } catch (Exception ignored) {}
    }

    private void logAudit(String entityType, Long entityId, Long outboxId, String action, String detail) {
        try {
            requiresNewTx.executeWithoutResult(s -> {
                AuditService audit = auditProvider != null ? auditProvider.getIfAvailable() : null;
                if (audit != null) {
                    String type = (entityType != null && !entityType.isBlank()) ? entityType : "COMMUNICATION";
                    Long eid = entityId != null && entityId > 0 ? entityId : outboxId;
                    audit.log(type, eid, action, detail);
                }
            });
        } catch (Exception ignored) {}
    }

    private void raiseAdminFailureAlert(Long outboxId, String recipient, String subject, String error,
                                        String entityType, Long entityId, String refNo) {
        try {
            requiresNewTx.executeWithoutResult(s -> db.update("""
                    INSERT INTO notifications(title, message, severity, category, is_read, target_fxml, reference_no, module_key, record_id, action_code, created_at)
                    VALUES (?, ?, 'WARNING', 'communication', 0, '/fxml/pages/Settings.fxml', ?, 'COMMUNICATION', ?, 'EMAIL_FAILED', ?)
                    """,
                    "Email Delivery Failed",
                    "Could not deliver \"" + safe(subject) + "\" to " + safe(recipient) + ". Error: " + trimError(error),
                    blankToNull(refNo) != null ? refNo : (outboxId != null ? "OUTBOX-" + outboxId : null),
                    entityId,
                    BusinessClock.nowUtcText()));
        } catch (Exception ignored) {}
    }

    private void cleanupOldAttachments() {
        try {
            requiresNewTx.executeWithoutResult(s -> db.update("""
                    UPDATE email_outbox SET attachment_b64 = NULL, signature_b64 = NULL
                    WHERE status = 'SENT' AND attachment_b64 IS NOT NULL
                      AND sent_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
                    """));
        } catch (Exception ignored) {}
    }

    private static long computeBackoffSeconds(int attempts) {
        return switch (attempts) {
            case 1 -> 120L;
            case 2 -> 600L;
            case 3 -> 1800L;
            default -> 3600L;
        };
    }

    private static boolean isTransientSmtpError(Exception ex, String msg) {
        String lower = (msg == null ? "" : msg).toLowerCase(Locale.ROOT);
        if (lower.contains("not configured") || lower.contains("invalid") || lower.contains("authentication")
                || lower.contains("535 ") || lower.contains("550 ") || lower.contains("required")) {
            return false;
        }
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect")
                || lower.contains("421") || lower.contains("450") || lower.contains("451") || lower.contains("452")
                || lower.contains("temporary") || lower.contains("reset") || ex instanceof EmailDeliveryException;
    }

    private static String mergeEmails(String primary, String configuredDefaults) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        addEmails(unique, primary);
        addEmails(unique, configuredDefaults);
        return String.join(", ", unique);
    }

    private static void addEmails(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String token : raw.split("[;,]")) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) target.add(trimmed);
        }
    }

    private String setting(String key, String def) {
        try {
            List<String> list = db.query("SELECT setting_value FROM application_setting WHERE setting_key = ?", (rs, i) -> rs.getString(1), key);
            if (!list.isEmpty() && list.getFirst() != null && !list.getFirst().isBlank()) return list.getFirst().trim();
        } catch (Exception ignored) {}
        return def;
    }

    private int intSetting(String key, int def) {
        try {
            return Integer.parseInt(setting(key, Integer.toString(def)));
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String extractAdminError(Throwable t) {
        if (t instanceof EmailDeliveryException ede && ede.adminMessage() != null) return ede.adminMessage();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String trimError(String err) {
        String s = safe(err);
        return s.length() <= 1500 ? s : s.substring(0, 1500);
    }

    private static String safe(String v) { return v == null ? "" : v.trim(); }
    private static String safeBody(String v) { return v == null ? "" : v; }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long numLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
