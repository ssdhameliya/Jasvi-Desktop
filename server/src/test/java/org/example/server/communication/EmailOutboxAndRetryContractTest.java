package org.example.server.communication;

import org.example.server.audit.AuditService;
import org.example.server.auth.SmtpMailService;
import org.example.server.persistence.JpaNativeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailOutboxAndRetryContractTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private static PlatformTransactionManager noopTxManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }

    @Test
    void sendWithOutboxMergesDefaultCopiesLogsAuditAndSupportsRetryLifecycle() {
        Map<Long, Map<String, Object>> store = new LinkedHashMap<>();
        List<String> executedSql = new ArrayList<>();

        JpaNativeRepository stubDb = new JpaNativeRepository() {
            long seq = 1L;

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, java.util.function.BiFunction<NativeRow, Integer, T> mapper, Object... args) {
                if (sql.contains("FROM application_setting")) {
                    String key = args != null && args.length > 0 ? String.valueOf(args[0]) : "";
                    String val = switch (key) {
                        case "email.default.cc" -> "audit@company.example.com";
                        case "email.default.bcc" -> "archive@company.example.com";
                        case "email.outbox.enabled" -> "true";
                        case "email.outbox.maxRetries" -> "3";
                        default -> null;
                    };
                    if (val == null) return List.of();
                    return List.of((T) val);
                }
                return List.of();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                executedSql.add(sql);
                if (sql.contains("INSERT INTO email_outbox") && type == Long.class) {
                    long id = seq++;
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", id);
                    rec.put("recipient_to", args[0]);
                    rec.put("recipient_cc", args[1]);
                    rec.put("recipient_bcc", args[2]);
                    rec.put("subject", args[3]);
                    rec.put("body", args[4]);
                    rec.put("status", "PENDING");
                    rec.put("attempt_count", 0);
                    rec.put("max_attempts", args[17]);
                    rec.put("entity_type", args[13]);
                    rec.put("entity_id", args[14]);
                    rec.put("reference_no", args[15]);
                    store.put(id, rec);
                    return (T) Long.valueOf(id);
                }
                return null;
            }

            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                executedSql.add(sql);
                if (sql.contains("SELECT * FROM email_outbox WHERE id = ?") && args != null && args.length > 0) {
                    Map<String, Object> row = store.get(((Number) args[0]).longValue());
                    return row == null ? List.of() : List.of(row);
                }
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                executedSql.add(sql);
                if (sql.contains("SET status = 'SENT'")) {
                    Map<String, Object> rec = store.get(((Number) args[1]).longValue());
                    if (rec != null) {
                        rec.put("status", "SENT");
                        rec.put("attempt_count", args[0]);
                    }
                    return 1;
                }
                if (sql.contains("status = 'RETRYING'")) {
                    Map<String, Object> rec = store.get(((Number) args[3]).longValue());
                    if (rec != null) {
                        rec.put("status", "RETRYING");
                        rec.put("attempt_count", args[0]);
                    }
                    return 1;
                }
                if (sql.contains("status = 'FAILED'")) {
                    Map<String, Object> rec = store.get(((Number) args[2]).longValue());
                    if (rec != null) {
                        rec.put("status", "FAILED");
                        rec.put("attempt_count", args[0]);
                    }
                    return 1;
                }
                if (sql.contains("SET status = 'SENDING'")) {
                    long id = ((Number) args[0]).longValue();
                    Map<String, Object> rec = store.get(id);
                    if (rec != null && ("FAILED".equals(rec.get("status")) || "RETRYING".equals(rec.get("status")))) {
                        rec.put("status", "SENDING");
                        return 1;
                    }
                    return 0;
                }
                return 1;
            }
        };

        SmtpMailService mail = mock(SmtpMailService.class);
        when(mail.isConfigured()).thenReturn(true);
        AuditService audit = mock(AuditService.class);
        EmailOutboxService outbox = new EmailOutboxService(stubDb, providerOf(mail), providerOf(audit), noopTxManager());

        // 1. Happy path merges default CC/BCC and marks SENT + logs EMAIL_SENT audit
        outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                "buyer@example.com", "finance@buyer.com", "", "Invoice INV-101", "<!DOCTYPE html><html><body>Hi</body></html>",
                List.of(), null, null, null, "SALE", 42L, "INV-101", "sale"
        ));

        verify(mail, times(1)).sendBusinessWithCopies(
                eq("buyer@example.com"),
                eq("finance@buyer.com, audit@company.example.com"),
                eq("archive@company.example.com"),
                eq("Invoice INV-101"),
                contains("<!DOCTYPE html>"),
                any(), isNull(SmtpMailService.Attachment.class), isNull(), isNull()
        );
        verify(audit, times(1)).log(eq("SALE"), eq(42L), eq("EMAIL_SENT"), contains("buyer@example.com"));
        assertEquals("SENT", store.get(1L).get("status"));

        // 2. Transient timeout queues RETRYING instead of terminal FAILED
        doThrow(new RuntimeException("SMTP connection timed out")).when(mail)
                .sendBusinessWithCopies(eq("retry@example.com"), any(), any(), any(), any(), any(), isNull(SmtpMailService.Attachment.class), any(), any());
        assertThrows(RuntimeException.class, () -> outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                "retry@example.com", null, null, "Retry Subj", "Body",
                List.of(), null, null, null, "SALE", 43L, "INV-102", "sale"
        )));
        assertEquals("RETRYING", store.get(2L).get("status"));

        // 3. Manual retry succeeds when SMTP recovers
        doNothing().when(mail).sendBusinessWithCopies(eq("retry@example.com"), any(), any(), any(), any(), any(), isNull(SmtpMailService.Attachment.class), any(), any());
        EmailOutboxService.RetryResult retryRes = outbox.retryNow(2L);
        assertTrue(retryRes.success());
        assertEquals("SENT", retryRes.status());

        // 4. Permanent auth failure raises FAILED + Admin notification row
        doThrow(new IllegalStateException("535 Authentication credentials invalid")).when(mail)
                .sendBusinessWithCopies(eq("perm@example.com"), any(), any(), any(), any(), any(), isNull(SmtpMailService.Attachment.class), any(), any());
        assertThrows(IllegalStateException.class, () -> outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                "perm@example.com", null, null, "Bad Auth", "Body",
                List.of(), null, null, null, "PURCHASE", 44L, "PUR-9", "purchase"
        )));
        assertEquals("FAILED", store.get(3L).get("status"));
        assertTrue(executedSql.stream().anyMatch(s -> s.contains("INSERT INTO notifications")),
                "Permanent email failure must raise an Admin Notification Center alert");
    }

    @Test
    void smtpHelperDetectsHtmlContentSafelyAndMigrationV11_0_93HasNoDollarBlocks() throws Exception {
        assertTrue(SmtpMailService.isHtmlContent("<!DOCTYPE html><html><body>Hello</body></html>"));
        assertTrue(SmtpMailService.isHtmlContent("<html><body>Card</body></html>"));
        assertFalse(SmtpMailService.isHtmlContent("Dear Customer, please find attached invoice."));

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V11_0_93__email_outbox_bank_gl_subscription_tracking.sql")) {
            assertNotNull(in, "V11_0_93 migration SQL must be packaged on classpath");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(sql.contains("$$"), "Migration SQL must not contain $$ blocks");
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS email_outbox"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS bank_gl_account_map"));
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS client_telemetry_monthly_summary"));
        }
    }
}
