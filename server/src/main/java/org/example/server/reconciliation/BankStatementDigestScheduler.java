package org.example.server.reconciliation;

import org.example.server.auth.SmtpMailService;
import org.example.server.communication.EmailOutboxService;
import org.example.server.communication.ServerNotificationTemplateService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.util.BusinessClock;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.*;

/**
 * Automated Weekly Unreconciled Bank Statement Digest Scheduler.
 * Alerts Finance/Admin via Email (outbox-backed), WhatsApp, and in-app Notification Center
 * when open UNMATCHED / REVIEW statement items accumulate across bank accounts.
 */
@Service
public class BankStatementDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(BankStatementDigestScheduler.class);

    public record DigestResult(boolean dispatched, int openCount, int bankCount, String summaryText) {}

    private final JpaNativeRepository jdbc;
    private final ObjectProvider<EmailOutboxService> outboxProvider;
    private final ObjectProvider<SmtpMailService> mailProvider;
    private final ObjectProvider<BusinessWhatsappService> whatsappProvider;

    public BankStatementDigestScheduler(
            JpaNativeRepository jdbc,
            ObjectProvider<EmailOutboxService> outboxProvider,
            ObjectProvider<SmtpMailService> mailProvider,
            ObjectProvider<BusinessWhatsappService> whatsappProvider) {
        this.jdbc = jdbc;
        this.outboxProvider = outboxProvider;
        this.mailProvider = mailProvider;
        this.whatsappProvider = whatsappProvider;
    }

    @Scheduled(cron = "${dse.banking.digest-cron:0 0 18 * * MON}")
    public void runScheduledDigest() {
        executeDigest(false);
    }

    public DigestResult executeDigest(boolean force) {
        if (!force && "false".equalsIgnoreCase(setting("banking.unreconciled.digest.enabled", "true"))) {
            return new DigestResult(false, 0, 0, "Bank unreconciled digest disabled.");
        }
        List<Map<String, Object>> grouped;
        try {
            grouped = jdbc.queryForList("""
                    SELECT b.bank_name, b.bank_account,
                           COUNT(*) AS open_count,
                           COALESCE(SUM(COALESCE(t.credit_amount, 0)), 0) AS open_credits,
                           COALESCE(SUM(COALESCE(t.debit_amount, 0)), 0) AS open_debits,
                           MIN(t.transaction_date) AS oldest_date
                    FROM bank_statement_transaction t
                    JOIN bank_statement_import b ON b.id = t.import_id
                    WHERE UPPER(COALESCE(t.status, 'UNMATCHED')) IN ('UNMATCHED', 'REVIEW', 'SUGGESTED')
                    GROUP BY b.bank_name, b.bank_account
                    ORDER BY COUNT(*) DESC
                    """);
        } catch (Exception ex) {
            log.debug("Skipping bank digest check: {}", ex.getMessage());
            return new DigestResult(false, 0, 0, ex.getMessage());
        }
        if (grouped.isEmpty()) {
            return new DigestResult(false, 0, 0, "All bank statements are reconciled.");
        }

        int totalOpen = 0;
        double totalCr = 0.0;
        double totalDr = 0.0;
        String oldestDate = "";
        StringBuilder summaryLines = new StringBuilder();
        List<List<String>> bankTableRows = new ArrayList<>();
        for (Map<String, Object> row : grouped) {
            String bank = Objects.toString(row.get("bank_name"), "Bank");
            String acc = Objects.toString(row.get("bank_account"), "");
            int cnt = row.get("open_count") instanceof Number n ? n.intValue() : 0;
            double cr = row.get("open_credits") instanceof Number n ? n.doubleValue() : 0.0;
            double dr = row.get("open_debits") instanceof Number n ? n.doubleValue() : 0.0;
            String dt = Objects.toString(row.get("oldest_date"), "");
            totalOpen += cnt;
            totalCr += cr;
            totalDr += dr;
            if (!dt.isBlank() && (oldestDate.isBlank() || dt.compareTo(oldestDate) < 0)) {
                oldestDate = dt;
            }
            if (!summaryLines.isEmpty()) summaryLines.append("\n");
            String accountDisplay = bank + (acc.isBlank() ? "" : " (" + acc + ")");
            summaryLines.append("• ").append(accountDisplay)
                    .append(": ").append(cnt).append(" open | Cr ").append(inr(cr))
                    .append(" | Dr ").append(inr(dr));
            bankTableRows.add(List.of(
                    accountDisplay,
                    Integer.toString(cnt),
                    inr(cr),
                    inr(dr),
                    dt.isBlank() ? "-" : dt
            ));
        }
        if (totalOpen <= 0) {
            return new DigestResult(false, 0, 0, "No open transactions.");
        }

        List<List<String>> oldestTxRows = new ArrayList<>();
        try {
            List<Map<String, Object>> topTx = jdbc.queryForList("""
                    SELECT b.bank_name, t.transaction_date,
                           COALESCE(t.description, '-') AS description,
                           COALESCE(t.credit_amount, 0) AS credit_amount,
                           COALESCE(t.debit_amount, 0) AS debit_amount,
                           COALESCE(t.status, 'UNMATCHED') AS status
                    FROM bank_statement_transaction t
                    JOIN bank_statement_import b ON b.id = t.import_id
                    WHERE UPPER(COALESCE(t.status, 'UNMATCHED')) IN ('UNMATCHED', 'REVIEW', 'SUGGESTED')
                    ORDER BY t.transaction_date ASC, t.id ASC
                    LIMIT 5
                    """);
            for (Map<String, Object> tx : topTx) {
                double crAmt = tx.get("credit_amount") instanceof Number n ? n.doubleValue() : 0.0;
                double drAmt = tx.get("debit_amount") instanceof Number n ? n.doubleValue() : 0.0;
                String amtStr = crAmt > 0.001 ? ("Cr " + inr(crAmt)) : ("Dr " + inr(drAmt));
                String desc = Objects.toString(tx.get("description"), "-");
                if (desc.length() > 54) desc = desc.substring(0, 51) + "...";
                oldestTxRows.add(List.of(
                        Objects.toString(tx.get("transaction_date"), "-"),
                        Objects.toString(tx.get("bank_name"), "Bank"),
                        desc,
                        amtStr,
                        Objects.toString(tx.get("status"), "UNMATCHED")
                ));
            }
        } catch (Exception ignored) {}

        var templates = ServerNotificationTemplateService.forJdbc(jdbc);
        String company = templates.companyName();
        String env = setting("dse.deployment.environment", "LOCAL").toUpperCase(Locale.ROOT);
        String safeOldestDate = oldestDate.isBlank() ? LocalDate.now(BusinessClock.zone()).toString() : oldestDate;
        Map<String, String> tokens = Map.of(
                "CompanyName", company,
                "Environment", env,
                "OpenCount", Integer.toString(totalOpen),
                "UnreconciledCredits", inr(totalCr),
                "UnreconciledDebits", inr(totalDr),
                "OldestDate", safeOldestDate,
                "BankAccountCount", Integer.toString(grouped.size()),
                "SummaryLines", summaryLines.toString()
        );

        String targetEmails = resolveNotificationEmails(templates);
        if (!targetEmails.isBlank()) {
            try {
                List<String> sections = new ArrayList<>();
                sections.add(org.example.shared.communication.TokenTemplateEngine.kpiGridHtml(
                        "Bank Reconciliation Queue Overview",
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Open Transactions", Integer.toString(totalOpen), grouped.size() + " bank account(s)", "#b45309"),
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Unmatched Credits", inr(totalCr), "Incoming funds", "#15803d"),
                                new org.example.shared.communication.TokenTemplateEngine.KpiTile("Unmatched Debits", inr(totalDr), "Outgoing payouts", "#dc2626")
                        ),
                        3
                ));
                sections.add(org.example.shared.communication.TokenTemplateEngine.dataTableHtml(
                        "🏦 Per-Bank Unreconciled Summary",
                        grouped.size() + " Accounts",
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Bank Account", false, "30%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Open", true, "12%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Open Credits", true, "20%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Open Debits", true, "20%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Oldest Date", false, "18%")
                        ),
                        bankTableRows,
                        "Oldest pending bank statement date: " + safeOldestDate
                ));
                if (!oldestTxRows.isEmpty()) {
                    sections.add(org.example.shared.communication.TokenTemplateEngine.dataTableHtml(
                            "⏳ Top 5 Oldest Unreconciled Statement Entries",
                            "Priority Review",
                            List.of(
                                    new org.example.shared.communication.TokenTemplateEngine.TableColumn("Date", false, "15%"),
                                    new org.example.shared.communication.TokenTemplateEngine.TableColumn("Bank", false, "18%"),
                                    new org.example.shared.communication.TokenTemplateEngine.TableColumn("Narration / Ref", false, "35%"),
                                    new org.example.shared.communication.TokenTemplateEngine.TableColumn("Amount", true, "18%"),
                                    new org.example.shared.communication.TokenTemplateEngine.TableColumn("Status", false, "14%")
                            ),
                            oldestTxRows,
                            "Open Bank Statement Reconciliation in " + company + " ERP to match or auto-post."
                    ));
                }
                var rendered = templates.renderRichEmail(
                        "bank_unreconciled_digest",
                        tokens,
                        new ServerNotificationTemplateService.RichCardStyle(
                                "#0284c7", "#0369a1",
                                "BANKING WATCHDOG • " + env,
                                totalOpen + " OPEN",
                                "#d97706",
                                "Unreconciled Bank Statement Digest",
                                grouped.size() + " bank account(s) require reconciliation review",
                                totalOpen + " unreconciled bank entries (Cr " + inr(totalCr) + ", Dr " + inr(totalDr) + ") since " + safeOldestDate,
                                List.of(
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("ACCOUNTS", Integer.toString(grouped.size())),
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("OPEN ENTRIES", Integer.toString(totalOpen), "#d97706"),
                                        new org.example.shared.communication.TokenTemplateEngine.MetaColumn("OLDEST DATE", safeOldestDate)
                                ),
                                sections,
                                List.of(),
                                company + " Accounting Automation",
                                null
                        )
                );
                EmailOutboxService outbox = outboxProvider != null ? outboxProvider.getIfAvailable() : null;
                if (outbox != null) {
                    outbox.sendWithOutbox(new EmailOutboxService.OutboxSendRequest(
                            targetEmails, null, null, rendered.subject(), rendered.body(),
                            List.of(), null, null, null, "BANK_STATEMENT", null, "DIGEST", "bank_unreconciled_digest"));
                } else if (mailProvider != null && mailProvider.getIfAvailable() != null && mailProvider.getIfAvailable().isConfigured()) {
                    mailProvider.getIfAvailable().sendBusiness(targetEmails, rendered.subject(), rendered.body());
                }
            } catch (Exception ex) {
                log.warn("Bank digest email dispatch failed: {}", ex.getMessage());
            }
        }

        String phones = setting("module.briefing.phone", "");
        if (!phones.isBlank() && whatsappProvider != null && whatsappProvider.getIfAvailable() != null) {
            String waText = templates.renderWhatsapp("bank_unreconciled_digest", tokens);
            for (String phone : phones.split("[,;]")) {
                String cleaned = phone.trim();
                if (cleaned.isBlank()) continue;
                try {
                    whatsappProvider.getIfAvailable().sendText(new WahaDtos.TextSendRequest(cleaned, waText, null));
                } catch (Exception ignored) {}
            }
        }

        try {
            jdbc.update("""
                    INSERT INTO notifications(title, message, severity, category, is_read, target_fxml, reference_no, module_key, record_id, action_code, created_at)
                    VALUES (?, ?, 'WARNING', 'banking', 0, '/fxml/pages/BankStatement.fxml', ?, 'BANK_STATEMENT', NULL, 'DIGEST', ?)
                    """,
                    "Unreconciled Bank Transactions",
                    totalOpen + " open statement item(s) across " + grouped.size() + " bank account(s) pending reconciliation (Cr " + inr(totalCr) + ", Dr " + inr(totalDr) + ").",
                    "DIGEST-" + LocalDate.now(BusinessClock.zone()),
                    BusinessClock.nowUtcText());
        } catch (Exception ignored) {}

        return new DigestResult(true, totalOpen, grouped.size(), summaryLines.toString());
    }

    private String resolveNotificationEmails(ServerNotificationTemplateService templates) {
        String configured = setting("module.briefing.emails", "");
        if (!configured.isBlank()) return configured;
        return templates.companySupportEmail();
    }

    private String setting(String key, String def) {
        try {
            List<String> rows = jdbc.query("SELECT setting_value FROM application_setting WHERE setting_key = ?", (rs, i) -> rs.getString(1), key);
            if (!rows.isEmpty() && rows.getFirst() != null && !rows.getFirst().isBlank()) return rows.getFirst().trim();
        } catch (Exception ignored) {}
        return def;
    }

    private static String inr(double val) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        return nf.format(val);
    }
}
