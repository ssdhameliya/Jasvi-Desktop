package org.example.server.communication;

import org.example.server.auth.SmtpMailService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.util.BusinessClock;
import org.example.server.whatsapp.BusinessWhatsappService;
import org.example.server.whatsapp.WahaDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Automated Multi-Cadence Executive Briefing Scheduler.
 * Governed by module.briefing.daily / module.briefing.cadences setting.
 * Broadcasts authentic financial KPIs (Sales, Purchases, Collections, Payments, Net Cash Flow,
 * Bank Balances, Overdue AR/AP, and Top Invoices) via WhatsApp (WAHA) and Email (SMTP)
 * across Daily, Weekly, Monthly, Quarterly, Half-Yearly, and Yearly (Indian FY or Custom 12M) cadences.
 */
@Service
public class ExecutiveBriefingScheduler {
    private static final Logger log = Logger.getLogger(ExecutiveBriefingScheduler.class.getName());

    public enum BriefingCadence {
        DAILY,
        WEEKLY,
        MONTHLY,
        QUARTERLY,
        HALF_YEARLY,
        YEARLY
    }

    public record BriefingExecutionResult(
            boolean success,
            String message,
            int whatsappSent,
            int whatsappFailed,
            int emailSent,
            int emailFailed,
            String summaryText
    ) {}

    public record DateRange(
            LocalDate startDate,
            LocalDate endDate,
            String cadenceLabel,
            String periodLabel
    ) {}

    private final JpaNativeRepository jdbc;
    private final ObjectProvider<BusinessWhatsappService> whatsappServiceProvider;
    private final ObjectProvider<SmtpMailService> mailServiceProvider;
    private final TransactionTemplate requiresNewTx;

    public ExecutiveBriefingScheduler(
            JpaNativeRepository jdbc,
            ObjectProvider<BusinessWhatsappService> whatsappServiceProvider,
            ObjectProvider<SmtpMailService> mailServiceProvider,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.whatsappServiceProvider = whatsappServiceProvider;
        this.mailServiceProvider = mailServiceProvider;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private String getSetting(String key, String def) {
        try {
            List<String> list = jdbc.query(
                    "SELECT setting_value FROM application_setting WHERE setting_key = ?",
                    (rs, i) -> rs.getString(1),
                    key
            );
            if (!list.isEmpty() && list.getFirst() != null && !list.getFirst().isBlank()) {
                return list.getFirst().trim();
            }
        } catch (Exception ignored) {}
        return def;
    }

    // Daily at 8:00 PM (20:00) Indian Standard Time
    @Scheduled(cron = "${dse.briefing.cron:0 0 20 * * *}")
    public void executeDailyExecutiveBriefing() {
        LocalDate today = LocalDate.now(BusinessClock.zone());
        String cadences = getSetting("module.briefing.cadences", "DAILY").toUpperCase(Locale.ROOT);

        // 1. Daily
        if (cadences.contains("DAILY")) {
            executeExecutiveBriefing(false, "DAILY");
        }

        // 2. Weekly (Every Sunday evening)
        if (cadences.contains("WEEKLY") && today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            executeExecutiveBriefing(false, "WEEKLY");
        }

        // 3. Monthly (On the final day of the month)
        YearMonth ym = YearMonth.from(today);
        if (cadences.contains("MONTHLY") && today.getDayOfMonth() == ym.lengthOfMonth()) {
            executeExecutiveBriefing(false, "MONTHLY");
        }

        // 4. Quarterly (End of Q1: 30-Jun, Q2: 30-Sep, Q3: 31-Dec, Q4: 31-Mar)
        if (cadences.contains("QUARTERLY") && (
                (today.getMonthValue() == 6 && today.getDayOfMonth() == 30) ||
                (today.getMonthValue() == 9 && today.getDayOfMonth() == 30) ||
                (today.getMonthValue() == 12 && today.getDayOfMonth() == 31) ||
                (today.getMonthValue() == 3 && today.getDayOfMonth() == 31))) {
            executeExecutiveBriefing(false, "QUARTERLY");
        }

        // 5. Half-Yearly (End of H1: 30-Sep, End of H2: 31-Mar)
        if (cadences.contains("HALF_YEARLY") && (
                (today.getMonthValue() == 9 && today.getDayOfMonth() == 30) ||
                (today.getMonthValue() == 3 && today.getDayOfMonth() == 31))) {
            executeExecutiveBriefing(false, "HALF_YEARLY");
        }

        // 6. Yearly (Fiscal Year End / Custom Period End)
        DateRange yearlyRange = resolveDateRange(BriefingCadence.YEARLY, today);
        if (cadences.contains("YEARLY") && today.equals(yearlyRange.endDate())) {
            executeExecutiveBriefing(false, "YEARLY");
        }
    }

    public BriefingExecutionResult executeDailyExecutiveBriefing(boolean isManualTest) {
        return executeExecutiveBriefing(isManualTest, "DAILY");
    }

    public BriefingExecutionResult executeExecutiveBriefing(boolean isManualTest, String cadenceName) {
        String enabled = getSetting("module.briefing.daily", getSetting("automation.briefing.enabled", "false"));
        if (!isManualTest && !"true".equalsIgnoreCase(enabled)) {
            log.fine("Executive Briefing Suite is disabled in settings. Skipping cron run.");
            return new BriefingExecutionResult(false, "Executive Briefing Suite is disabled in Settings.", 0, 0, 0, 0, "");
        }

        boolean waEnabled = "true".equalsIgnoreCase(getSetting("module.briefing.whatsapp", "true"));
        boolean emailEnabled = "true".equalsIgnoreCase(getSetting("module.briefing.email", "true"));
        String phonesRaw = getSetting("module.briefing.phone", "");
        String emailsRaw = getSetting("module.briefing.emails", "");

        if (!waEnabled && !emailEnabled) {
            return new BriefingExecutionResult(false, "Both WhatsApp and Email channels are disabled for Executive Briefing.", 0, 0, 0, 0, "");
        }

        BriefingCadence cadence;
        try {
            cadence = BriefingCadence.valueOf(cadenceName != null ? cadenceName.toUpperCase(Locale.ROOT).replace("-", "_") : "DAILY");
        } catch (Exception ex) {
            cadence = BriefingCadence.DAILY;
        }

        LocalDate today = LocalDate.now(BusinessClock.zone());
        DateRange dateRange = resolveDateRange(cadence, today);
        String startIso = dateRange.startDate().toString();
        String endIso = dateRange.endDate().toString();
        long periodDays = Math.max(1L, java.time.temporal.ChronoUnit.DAYS.between(dateRange.startDate(), dateRange.endDate()) + 1L);
        LocalDate prevEndDate = dateRange.startDate().minusDays(1);
        LocalDate prevStartDate = prevEndDate.minusDays(periodDays - 1);
        String prevStartIso = prevStartDate.toString();
        String prevEndIso = prevEndDate.toString();
        var templates = ServerNotificationTemplateService.forJdbc(jdbc);
        String companyName = templates.companyName();
        String env = System.getProperty("DSE_DEPLOYMENT_ENVIRONMENT", System.getenv().getOrDefault("DSE_DEPLOYMENT_ENVIRONMENT", getSetting("dse.deployment.environment", "LOCAL"))).toUpperCase(Locale.ROOT);
        String portalUrl = getSetting("company.publicPortalUrl", getSetting("web.portal.url", "")).trim();

        // 1. Invoiced Sales (+ prior period delta)
        long salesCount = 0;
        double salesTotal = 0.0;
        double prevSalesTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount), 0) AS total
                    FROM sales_header
                    WHERE invoice_date >= ? AND invoice_date <= ?
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, startIso, endIso);
            if (!rows.isEmpty()) {
                salesCount = ((Number) rows.getFirst().get("cnt")).longValue();
                salesTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
            Double prev = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(total_amount), 0) FROM sales_header
                    WHERE invoice_date >= ? AND invoice_date <= ?
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, Double.class, prevStartIso, prevEndIso);
            if (prev != null) prevSalesTotal = prev;
        } catch (Exception ex) {
            log.warning("Briefing sales calculation error: " + ex.getMessage());
        }

        // 2. Purchases Booked (+ prior period delta)
        long purchaseCount = 0;
        double purchaseTotal = 0.0;
        double prevPurchaseTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount), 0) AS total
                    FROM purchase_header
                    WHERE bill_date >= ? AND bill_date <= ?
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, startIso, endIso);
            if (!rows.isEmpty()) {
                purchaseCount = ((Number) rows.getFirst().get("cnt")).longValue();
                purchaseTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
            Double prev = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(total_amount), 0) FROM purchase_header
                    WHERE bill_date >= ? AND bill_date <= ?
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, Double.class, prevStartIso, prevEndIso);
            if (prev != null) prevPurchaseTotal = prev;
        } catch (Exception ex) {
            log.warning("Briefing purchases calculation error: " + ex.getMessage());
        }

        // 3. Customer Collections (Receipts + prior period delta)
        long collCount = 0;
        double collTotal = 0.0;
        double prevCollTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total
                    FROM payment_record
                    WHERE payment_date >= ? AND payment_date <= ?
                      AND (UPPER(COALESCE(payment_type, '')) = 'RECEIPT' OR UPPER(COALESCE(document_type, '')) = 'SALE')
                      AND UPPER(COALESCE(payment_type, '')) != 'PAYMENT'
                    """, startIso, endIso);
            if (!rows.isEmpty()) {
                collCount = ((Number) rows.getFirst().get("cnt")).longValue();
                collTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
            Double prev = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM payment_record
                    WHERE payment_date >= ? AND payment_date <= ?
                      AND (UPPER(COALESCE(payment_type, '')) = 'RECEIPT' OR UPPER(COALESCE(document_type, '')) = 'SALE')
                      AND UPPER(COALESCE(payment_type, '')) != 'PAYMENT'
                    """, Double.class, prevStartIso, prevEndIso);
            if (prev != null) prevCollTotal = prev;
        } catch (Exception ex) {
            log.warning("Briefing collections calculation error: " + ex.getMessage());
        }

        // 4. Vendor Payments Disbursed (+ prior period delta)
        long payCount = 0;
        double payTotal = 0.0;
        double prevPayTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS total
                    FROM payment_record
                    WHERE payment_date >= ? AND payment_date <= ?
                      AND (UPPER(COALESCE(payment_type, '')) = 'PAYMENT' OR UPPER(COALESCE(document_type, '')) = 'PURCHASE')
                    """, startIso, endIso);
            if (!rows.isEmpty()) {
                payCount = ((Number) rows.getFirst().get("cnt")).longValue();
                payTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
            Double prev = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(amount), 0) FROM payment_record
                    WHERE payment_date >= ? AND payment_date <= ?
                      AND (UPPER(COALESCE(payment_type, '')) = 'PAYMENT' OR UPPER(COALESCE(document_type, '')) = 'PURCHASE')
                    """, Double.class, prevStartIso, prevEndIso);
            if (prev != null) prevPayTotal = prev;
        } catch (Exception ex) {
            log.warning("Briefing payments calculation error: " + ex.getMessage());
        }

        // 5. Net Period Cash Flow
        double netCashFlow = collTotal - payTotal;

        // 6. Overdue Customer Receivables Alert (AR as of today)
        long overdueArCount = 0;
        double overdueArTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount - COALESCE(paid_amount, 0)), 0) AS total
                    FROM sales_header
                    WHERE due_date IS NOT NULL AND due_date < ?
                      AND COALESCE(total_amount, 0) > COALESCE(paid_amount, 0)
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, today.toString());
            if (!rows.isEmpty()) {
                overdueArCount = ((Number) rows.getFirst().get("cnt")).longValue();
                overdueArTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
        } catch (Exception ex) {
            log.warning("Briefing overdue AR calculation error: " + ex.getMessage());
        }

        // 7. Overdue Vendor Payables Alert (AP as of today)
        long overdueApCount = 0;
        double overdueApTotal = 0.0;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt, COALESCE(SUM(total_amount - COALESCE(paid_amount, 0)), 0) AS total
                    FROM purchase_header
                    WHERE due_date IS NOT NULL AND due_date < ?
                      AND COALESCE(total_amount, 0) > COALESCE(paid_amount, 0)
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    """, today.toString());
            if (!rows.isEmpty()) {
                overdueApCount = ((Number) rows.getFirst().get("cnt")).longValue();
                overdueApTotal = ((Number) rows.getFirst().get("total")).doubleValue();
            }
        } catch (Exception ex) {
            log.warning("Briefing overdue AP calculation error: " + ex.getMessage());
        }

        // 8. Top 3 Invoices of Period
        List<Map<String, Object>> topInvoices = new ArrayList<>();
        try {
            topInvoices = jdbc.queryForList("""
                    SELECT invoice_number, party_name, total_amount
                    FROM sales_header
                    WHERE invoice_date >= ? AND invoice_date <= ?
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    ORDER BY total_amount DESC
                    LIMIT 3
                    """, startIso, endIso);
        } catch (Exception ex) {
            log.warning("Briefing top invoices calculation error: " + ex.getMessage());
        }

        // 9. Liquid Balances (Bank & Cash Accounts snapshot)
        List<Map<String, Object>> liquidAccounts = new ArrayList<>();
        double totalLiquid = 0.0;
        try {
            liquidAccounts = jdbc.queryForList("""
                    SELECT account_code, account_name, current_balance
                    FROM chart_of_accounts
                    WHERE is_active = true
                      AND (UPPER(COALESCE(account_subtype, '')) IN ('BANK', 'CASH') OR account_code IN ('1010', '1020'))
                    ORDER BY account_code
                    """);
            for (var acc : liquidAccounts) {
                Object b = acc.get("current_balance");
                if (b instanceof Number num) totalLiquid += num.doubleValue();
            }
        } catch (Exception ex) {
            log.warning("Briefing liquid accounts calculation error: " + ex.getMessage());
        }

        // 9b. Top 5 Overdue AR Debtors & Pending Bank Reconciliation Count
        List<Map<String, Object>> topOverdueDebtors = new ArrayList<>();
        try {
            topOverdueDebtors = jdbc.queryForList("""
                    SELECT COALESCE(party_name, 'Customer') AS party_name,
                           COUNT(*) AS inv_cnt,
                           COALESCE(SUM(total_amount - COALESCE(paid_amount, 0)), 0) AS overdue_bal,
                           MIN(due_date) AS oldest_due
                    FROM sales_header
                    WHERE due_date IS NOT NULL AND due_date < ?
                      AND COALESCE(total_amount, 0) > COALESCE(paid_amount, 0)
                      AND UPPER(COALESCE(document_status, '')) NOT IN ('CANCELLED', 'DELETED')
                    GROUP BY COALESCE(party_name, 'Customer')
                    ORDER BY overdue_bal DESC
                    LIMIT 5
                    """, today.toString());
        } catch (Exception ex) {
            log.warning("Briefing top overdue debtors error: " + ex.getMessage());
        }

        long pendingBankReconCount = 0;
        try {
            Long cnt = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM bank_statement_transaction
                    WHERE UPPER(COALESCE(status, 'UNMATCHED')) IN ('UNMATCHED', 'REVIEW', 'SUGGESTED')
                    """, Long.class);
            if (cnt != null) pendingBankReconCount = cnt;
        } catch (Exception ignored) {}

        // 10. Format Dynamic Metrics Summary & WhatsApp Text
        StringBuilder metrics = new StringBuilder();
        if (!"PROD".equalsIgnoreCase(env)) {
            metrics.append("⚠️ [TEST DISPATCH — ").append(env).append(" ENVIRONMENT]\n");
        }
        metrics.append("📈 Invoiced Sales: ").append(formatInr(salesTotal))
               .append(" (").append(salesCount).append(salesCount == 1 ? " Invoice" : " Invoices").append(")\n");
        metrics.append("📥 Purchases Booked: ").append(formatInr(purchaseTotal))
               .append(" (").append(purchaseCount).append(purchaseCount == 1 ? " Bill" : " Bills").append(")\n");
        metrics.append("💵 Customer Collections: ").append(formatInr(collTotal))
               .append(" (").append(collCount).append(collCount == 1 ? " Receipt" : " Receipts").append(")\n");
        metrics.append("📤 Vendor Payments: ").append(formatInr(payTotal))
               .append(" (").append(payCount).append(payCount == 1 ? " Payment" : " Payments").append(")\n");
        metrics.append("⚖️ Net Cash Flow: ").append(netCashFlow >= 0 ? "+" : "").append(formatInr(netCashFlow))
               .append(netCashFlow > 0 ? " (Surplus)" : (netCashFlow < 0 ? " (Deficit)" : " (Balanced)")).append("\n");
        metrics.append("🏦 Total Liquid Balances: ").append(formatInr(totalLiquid)).append("\n");
        for (var acc : liquidAccounts) {
            String name = Objects.toString(acc.get("account_name"), "Account");
            double bal = acc.get("current_balance") instanceof Number n ? n.doubleValue() : 0.0;
            metrics.append("   • ").append(name).append(": ").append(formatInr(bal)).append("\n");
        }
        if (overdueArCount > 0) {
            metrics.append("⚠️ Overdue Receivables (AR): ").append(formatInr(overdueArTotal))
                   .append(" (").append(overdueArCount).append(" Invoices)\n");
        } else {
            metrics.append("✅ Overdue Receivables (AR): Zero overdue accounts.\n");
        }
        if (overdueApCount > 0) {
            metrics.append("⏳ Pending Payables (AP): ").append(formatInr(overdueApTotal))
                   .append(" (").append(overdueApCount).append(" Bills Due)\n");
        }
        if (!topInvoices.isEmpty()) {
            metrics.append("🌟 Top Invoices of Period:\n");
            for (var inv : topInvoices) {
                String no = Objects.toString(inv.get("invoice_number"), "-");
                String party = Objects.toString(inv.get("party_name"), "Customer");
                double amt = inv.get("total_amount") instanceof Number n ? n.doubleValue() : 0.0;
                metrics.append("   • ").append(no).append(" (").append(party).append("): ").append(formatInr(amt)).append("\n");
            }
        }
        String metricsSummary = metrics.toString().trim();

        Map<String, String> briefingTokens = Map.of(
                "CompanyName", companyName,
                "CadenceLabel", dateRange.cadenceLabel(),
                "PeriodLabel", dateRange.periodLabel(),
                "Environment", env,
                "PortalUrl", portalUrl,
                "MetricsSummary", metricsSummary
        );
        String waText = templates.renderWhatsapp("briefing_dispatch", briefingTokens);

        // 11. Format Email (Dynamic custom template or Standard Plain Text vs rich table-based Executive Card)
        String customSubject = templates.setting("email.template.briefing_dispatch.subject", "");
        String customBody = templates.setting("email.template.briefing_dispatch.body", "");
        boolean useHtmlEmail = templates.shouldUseHtml("briefing_dispatch");
        String emailSubject;
        String emailPayload;

        if (!useHtmlEmail) {
            var rendered = templates.renderEmail(
                    "briefing_dispatch",
                    briefingTokens,
                    new ServerNotificationTemplateService.CardStyle(
                            "#0284c7", "#0369a1",
                            dateRange.cadenceLabel(),
                            companyName + " — " + dateRange.cadenceLabel(),
                            List.of(),
                            "Dispatched automatically by " + companyName + " ERP Scheduled Automation Suite."
                    )
            );
            emailSubject = rendered.subject();
            emailPayload = rendered.body();
        } else {
            List<String> sections = new ArrayList<>();
            if (!"PROD".equalsIgnoreCase(env)) {
                sections.add(org.example.shared.communication.TokenTemplateEngine.calloutBoxHtml(
                        "ENVIRONMENT NOTICE",
                        env + " DISPATCH",
                        "Generated from non-production environment (" + env + "). Verify environment before acting on balances.",
                        "#d97706", "#fffbeb", "#92400e"
                ));
            }
            sections.add(org.example.shared.communication.TokenTemplateEngine.kpiGridHtml(
                    "📊 Period Financial Performance & Cash Flow",
                    List.of(
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Invoiced Sales", formatInr(salesTotal),
                                    formatBriefingDeltaNote(salesCount, "Invoice", "Invoices", salesTotal, prevSalesTotal), "#0284c7"),
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Purchases Booked", formatInr(purchaseTotal),
                                    formatBriefingDeltaNote(purchaseCount, "Bill", "Bills", purchaseTotal, prevPurchaseTotal), "#4f46e5"),
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Customer Collections", formatInr(collTotal),
                                    formatBriefingDeltaNote(collCount, "Receipt", "Receipts", collTotal, prevCollTotal), "#16a34a"),
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Vendor Payments Outflow", formatInr(payTotal),
                                    formatBriefingDeltaNote(payCount, "Payment", "Payments", payTotal, prevPayTotal), "#d97706"),
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Net Period Cash Flow", (netCashFlow >= 0 ? "+" : "") + formatInr(netCashFlow),
                                    netCashFlow > 0 ? "Cash Surplus" : (netCashFlow < 0 ? "Cash Deficit" : "Balanced"),
                                    netCashFlow >= 0 ? "#16a34a" : "#dc2626"),
                            new org.example.shared.communication.TokenTemplateEngine.KpiTile(
                                    "Overdue Receivables (AR)", formatInr(overdueArTotal),
                                    overdueArCount + " Open Overdue Invoice(s)",
                                    overdueArCount > 0 ? "#dc2626" : "#16a34a")
                    ),
                    2
            ));
            if (!topInvoices.isEmpty()) {
                List<List<String>> topInvRows = new ArrayList<>();
                for (var inv : topInvoices) {
                    String no = Objects.toString(inv.get("invoice_number"), "-");
                    String party = Objects.toString(inv.get("party_name"), "Customer");
                    double amt = inv.get("total_amount") instanceof Number n ? n.doubleValue() : 0.0;
                    topInvRows.add(List.of(no, party, formatInr(amt)));
                }
                sections.add(org.example.shared.communication.TokenTemplateEngine.dataTableHtml(
                        "🌟 Top Invoices of Period",
                        topInvoices.size() + " Highlight(s)",
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Invoice No", false, "28%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Customer Name", false, "44%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Amount", true, "28%")
                        ),
                        topInvRows,
                        null
                ));
            }
            if (!topOverdueDebtors.isEmpty()) {
                List<List<String>> debtorRows = new ArrayList<>();
                for (var d : topOverdueDebtors) {
                    String party = Objects.toString(d.get("party_name"), "Customer");
                    long cnt = d.get("inv_cnt") instanceof Number n ? n.longValue() : 0L;
                    double bal = d.get("overdue_bal") instanceof Number n ? n.doubleValue() : 0.0;
                    String oldestDue = Objects.toString(d.get("oldest_due"), "-");
                    debtorRows.add(List.of(party, Long.toString(cnt), oldestDue, formatInr(bal)));
                }
                sections.add(org.example.shared.communication.TokenTemplateEngine.dataTableHtml(
                        "⚠️ Top Overdue Receivables (AR Debtors)",
                        "Total AR Overdue: " + formatInr(overdueArTotal),
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Customer / Party", false, "40%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Invoices", true, "14%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Oldest Due", false, "20%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Overdue Bal", true, "26%")
                        ),
                        debtorRows,
                        overdueApCount > 0 || pendingBankReconCount > 0
                                ? "Vendor Payables Due: " + formatInr(overdueApTotal) + " (" + overdueApCount + " bills)  •  Pending Bank Recon Rows: " + pendingBankReconCount
                                : null
                ));
            }
            if (!liquidAccounts.isEmpty()) {
                List<List<String>> accRows = new ArrayList<>();
                for (var acc : liquidAccounts) {
                    String name = Objects.toString(acc.get("account_name"), "Account");
                    String code = Objects.toString(acc.get("account_code"), "-");
                    double bal = acc.get("current_balance") instanceof Number n ? n.doubleValue() : 0.0;
                    accRows.add(List.of(name, code, formatInr(bal)));
                }
                sections.add(org.example.shared.communication.TokenTemplateEngine.dataTableHtml(
                        "🏦 Liquid Bank & Cash Balances",
                        "Total: " + formatInr(totalLiquid),
                        List.of(
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Account Name", false, "50%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Code", false, "20%"),
                                new org.example.shared.communication.TokenTemplateEngine.TableColumn("Current Balance", true, "30%")
                        ),
                        accRows,
                        "Pending AP Bills: " + overdueApCount + " (" + formatInr(overdueApTotal) + ")  •  Unreconciled Bank Entries: " + pendingBankReconCount
                ));
            }
            Map<String, String> emailTokens = new LinkedHashMap<>(briefingTokens);
            if (customBody.isBlank()) {
                emailTokens.put("MetricsSummary", "Executive Financial & Liquidity Snapshot for " + dateRange.periodLabel());
            }
            var rendered = templates.renderRichEmail(
                    "briefing_dispatch",
                    emailTokens,
                    new ServerNotificationTemplateService.RichCardStyle(
                            "#0284c7", "#0369a1",
                            dateRange.cadenceLabel().toUpperCase(Locale.ROOT) + " • " + env,
                            "EXECUTIVE BRIEFING",
                            "#0369a1",
                            companyName + " — " + dateRange.cadenceLabel(),
                            dateRange.periodLabel(),
                            dateRange.cadenceLabel() + ": Sales " + formatInr(salesTotal) + ", Collections " + formatInr(collTotal) + ", Liquid " + formatInr(totalLiquid),
                            List.of(
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("PERIOD", dateRange.startDate() + " to " + dateRange.endDate()),
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("NET CASH FLOW", (netCashFlow >= 0 ? "+" : "") + formatInr(netCashFlow), netCashFlow >= 0 ? "#16a34a" : "#dc2626"),
                                    new org.example.shared.communication.TokenTemplateEngine.MetaColumn("LIQUID BALANCE", formatInr(totalLiquid))
                            ),
                            sections,
                            portalUrl.isBlank()
                                    ? List.of()
                                    : List.of(new org.example.shared.communication.TokenTemplateEngine.ActionButton(
                                            "Open Web ERP Portal →", portalUrl, "#0284c7")),
                            "Dispatched automatically by " + companyName + " ERP Scheduled Automation Suite.",
                            null
                    )
            );
            emailSubject = !customSubject.isBlank()
                    ? org.example.shared.communication.TokenTemplateEngine.format(customSubject, briefingTokens)
                    : (!rendered.subject().isBlank() ? rendered.subject() : (companyName + " — " + dateRange.cadenceLabel() + " (" + dateRange.periodLabel() + ")"));
            emailPayload = rendered.body();
        }

        // 12. Multi-Recipient Dispatch
        int waSent = 0, waFailed = 0;
        int emailSent = 0, emailFailed = 0;

        // WhatsApp Multi-Recipient Dispatch
        if (waEnabled && !phonesRaw.isBlank()) {
            BusinessWhatsappService waService = whatsappServiceProvider.getIfAvailable();
            String[] phones = phonesRaw.split("[,;]");
            for (String phone : phones) {
                String p = phone.trim();
                if (p.isBlank()) continue;
                if (waService != null) {
                    try {
                        var res = waService.sendText(new WahaDtos.TextSendRequest(p, waText, null));
                        if (res != null && res.success()) {
                            waSent++;
                        } else {
                            waFailed++;
                            log.warning("WhatsApp briefing failed for " + p + ": " + (res != null ? res.message() : "null response"));
                        }
                    } catch (Exception ex) {
                        waFailed++;
                        log.warning("WhatsApp briefing exception for " + p + ": " + ex.getMessage());
                    }
                } else {
                    waFailed++;
                    log.warning("BusinessWhatsappService unavailable for briefing dispatch.");
                }
            }
        }

        // Email Multi-Recipient Dispatch
        if (emailEnabled && !emailsRaw.isBlank()) {
            SmtpMailService mailService = mailServiceProvider.getIfAvailable();
            String[] emails = emailsRaw.split("[,;]");
            for (String email : emails) {
                String e = email.trim();
                if (e.isBlank()) continue;
                if (mailService != null && mailService.isConfigured()) {
                    try {
                        mailService.sendBusinessFiles(e, emailSubject, emailPayload, List.of());
                        emailSent++;
                        recordBriefingEmailAudit(e, true, null);
                    } catch (Exception ex) {
                        emailFailed++;
                        recordBriefingEmailAudit(e, false, ex.getMessage());
                        log.warning("Email briefing failed for " + e + ": " + ex.getMessage());
                    }
                } else {
                    emailFailed++;
                    recordBriefingEmailAudit(e, false, "SMTP is not configured on server");
                    log.warning("SMTP Mail Service is not configured for briefing email dispatch.");
                }
            }
        }

        String statusMsg = String.format(Locale.ROOT,
                "%s dispatch complete: WhatsApp (%d sent, %d failed), Email (%d sent, %d failed).",
                dateRange.cadenceLabel(), waSent, waFailed, emailSent, emailFailed);
        log.info("Executive Briefing Suite: " + statusMsg);

        boolean overallSuccess = (waSent > 0 || emailSent > 0) || (waFailed == 0 && emailFailed == 0);
        return new BriefingExecutionResult(overallSuccess, statusMsg, waSent, waFailed, emailSent, emailFailed, waText);
    }

    public DateRange resolveDateRange(BriefingCadence cadence, LocalDate today) {
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd MMM yyyy");
        DateTimeFormatter shortDf = DateTimeFormatter.ofPattern("dd MMM");

        return switch (cadence) {
            case DAILY -> new DateRange(
                    today,
                    today,
                    "Executive Daily Briefing",
                    "Evening Performance Summary • " + today.format(df) + " • 08:00 PM IST"
            );
            case WEEKLY -> {
                LocalDate start = today.minusDays(6);
                yield new DateRange(
                        start,
                        today,
                        "Executive Weekly Summary",
                        "Weekly Performance • " + start.format(shortDf) + " to " + today.format(df)
                );
            }
            case MONTHLY -> {
                LocalDate start = today.withDayOfMonth(1);
                yield new DateRange(
                        start,
                        today,
                        "Executive Monthly Briefing",
                        "Month-to-Date Performance • " + today.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                );
            }
            case QUARTERLY -> {
                int month = today.getMonthValue();
                int q = month <= 3 ? 4 : (month <= 6 ? 1 : (month <= 9 ? 2 : 3));
                int fyYear = month <= 3 ? today.getYear() - 1 : today.getYear();
                LocalDate start = switch (q) {
                    case 1 -> LocalDate.of(fyYear, 4, 1);
                    case 2 -> LocalDate.of(fyYear, 7, 1);
                    case 3 -> LocalDate.of(fyYear, 10, 1);
                    default -> LocalDate.of(fyYear + 1, 1, 1);
                };
                LocalDate end = switch (q) {
                    case 1 -> LocalDate.of(fyYear, 6, 30);
                    case 2 -> LocalDate.of(fyYear, 9, 30);
                    case 3 -> LocalDate.of(fyYear, 12, 31);
                    default -> LocalDate.of(fyYear + 1, 3, 31);
                };
                yield new DateRange(
                        start,
                        today.isBefore(end) ? today : end,
                        "Quarterly Executive Business Review",
                        "Quarter Q" + q + " Performance • " + start.format(shortDf) + " to " + end.format(df)
                );
            }
            case HALF_YEARLY -> {
                int month = today.getMonthValue();
                boolean isH1 = month >= 4 && month <= 9;
                int fyYear = month <= 3 ? today.getYear() - 1 : today.getYear();
                LocalDate start = isH1 ? LocalDate.of(fyYear, 4, 1) : LocalDate.of(fyYear, 10, 1);
                LocalDate end = isH1 ? LocalDate.of(fyYear, 9, 30) : LocalDate.of(fyYear + 1, 3, 31);
                yield new DateRange(
                        start,
                        today.isBefore(end) ? today : end,
                        "Half-Yearly Executive Review",
                        "Half-Year " + (isH1 ? "H1" : "H2") + " Performance • " + start.format(shortDf) + " to " + end.format(df)
                );
            }
            case YEARLY -> {
                String mode = getSetting("module.briefing.period.mode", "INDIAN_FY");
                if ("CUSTOM_12M".equalsIgnoreCase(mode)) {
                    String customStart = getSetting("module.briefing.period.custom_start", "11-01");
                    String[] parts = customStart.split("-");
                    int startM = parts.length >= 1 ? Integer.parseInt(parts[0]) : 11;
                    int startD = parts.length >= 2 ? Integer.parseInt(parts[1]) : 1;

                    LocalDate startThisYear = LocalDate.of(today.getYear(), startM, startD);
                    LocalDate cycleStart = today.isBefore(startThisYear) ? startThisYear.minusYears(1) : startThisYear;
                    LocalDate cycleEnd = cycleStart.plusMonths(12).minusDays(1);
                    yield new DateRange(
                            cycleStart,
                            today.isBefore(cycleEnd) ? today : cycleEnd,
                            "Annual Executive Financial Review",
                            "Custom 12-Month Performance • " + cycleStart.format(df) + " to " + cycleEnd.format(df)
                    );
                } else {
                    int fyYear = today.getMonthValue() <= 3 ? today.getYear() - 1 : today.getYear();
                    LocalDate start = LocalDate.of(fyYear, 4, 1);
                    LocalDate end = LocalDate.of(fyYear + 1, 3, 31);
                    yield new DateRange(
                            start,
                            today.isBefore(end) ? today : end,
                            "Annual Financial Review",
                            "Financial Year " + fyYear + "-" + ((fyYear + 1) % 100) + " • " + start.format(df) + " to " + end.format(df)
                    );
                }
            }
        };
    }

    private String formatInr(double amount) {
        Locale india = Locale.of("en", "IN");
        NumberFormat fmt = NumberFormat.getCurrencyInstance(india);
        return fmt.format(amount);
    }

    private String formatBriefingDeltaNote(long count, String singular, String plural, double current, double previous) {
        String countText = count + " " + (count == 1 ? singular : plural);
        if (previous <= 0.009) {
            return current > 0.009 ? countText + " • Active Period" : countText;
        }
        double pct = ((current - previous) / previous) * 100.0;
        String sign = pct >= 0 ? "+" : "";
        return String.format(Locale.ROOT, "%s • %s%.1f%% vs prior", countText, sign, pct);
    }

    private void recordBriefingEmailAudit(String recipient, boolean success, String errorMessage) {
        try {
            requiresNewTx.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO communication_log (entity_type, entity_id, channel, recipient, subject, status, error_message, created_by, created_at)
                        VALUES ('BRIEFING', 0, 'EMAIL', ?, 'Executive Briefing Suite', ?, ?, 'SYSTEM', ?)
                        """,
                        recipient,
                        success ? "SENT" : "FAILED",
                        errorMessage,
                        BusinessClock.nowUtcText()
                );
            });
        } catch (Exception ex) {
            log.warning("Failed to record briefing email audit for " + recipient + ": " + ex.getMessage());
        }
    }
}
