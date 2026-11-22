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
            List<String> legacy = jdbc.query(
                    "SELECT config_value FROM app_config WHERE config_key = ?",
                    (rs, i) -> rs.getString(1),
                    key
            );
            if (!legacy.isEmpty() && legacy.getFirst() != null && !legacy.getFirst().isBlank()) {
                return legacy.getFirst().trim();
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
        String companyName = getSetting("company.name", "Jasvi Industries");
        String env = System.getProperty("DSE_DEPLOYMENT_ENVIRONMENT", System.getenv().getOrDefault("DSE_DEPLOYMENT_ENVIRONMENT", getSetting("dse.deployment.environment", "LOCAL"))).toUpperCase(Locale.ROOT);
        String portalUrl = getSetting("company.publicPortalUrl", getSetting("web.portal.url", "")).trim();

        // 1. Invoiced Sales
        long salesCount = 0;
        double salesTotal = 0.0;
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
        } catch (Exception ex) {
            log.warning("Briefing sales calculation error: " + ex.getMessage());
        }

        // 2. Purchases Booked
        long purchaseCount = 0;
        double purchaseTotal = 0.0;
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
        } catch (Exception ex) {
            log.warning("Briefing purchases calculation error: " + ex.getMessage());
        }

        // 3. Customer Collections (Receipts)
        long collCount = 0;
        double collTotal = 0.0;
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
        } catch (Exception ex) {
            log.warning("Briefing collections calculation error: " + ex.getMessage());
        }

        // 4. Vendor Payments Disbursed
        long payCount = 0;
        double payTotal = 0.0;
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

        // 10. Format WhatsApp Text Message
        StringBuilder wa = new StringBuilder();
        if (!"PROD".equalsIgnoreCase(env)) {
            wa.append("⚠️ *[TEST DISPATCH — ").append(env).append(" ENVIRONMENT]*\n");
        }
        wa.append("📊 *").append(companyName).append(" — ").append(dateRange.cadenceLabel()).append("*\n");
        wa.append("📅 *Period:* ").append(dateRange.periodLabel()).append("\n");
        wa.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        wa.append("📈 *Invoiced Sales:* ").append(formatInr(salesTotal))
          .append(" (").append(salesCount).append(salesCount == 1 ? " Invoice" : " Invoices").append(")\n");
        wa.append("📥 *Purchases Booked:* ").append(formatInr(purchaseTotal))
          .append(" (").append(purchaseCount).append(purchaseCount == 1 ? " Bill" : " Bills").append(")\n");
        wa.append("💵 *Customer Collections:* ").append(formatInr(collTotal))
          .append(" (").append(collCount).append(collCount == 1 ? " Receipt" : " Receipts").append(")\n");
        wa.append("📤 *Vendor Payments:* ").append(formatInr(payTotal))
          .append(" (").append(payCount).append(payCount == 1 ? " Payment" : " Payments").append(")\n");
        wa.append("⚖️ *Net Cash Flow:* ").append(netCashFlow >= 0 ? "+" : "").append(formatInr(netCashFlow))
          .append(netCashFlow > 0 ? " (Surplus)" : (netCashFlow < 0 ? " (Deficit)" : " (Balanced)")).append("\n");
        wa.append("🏦 *Total Liquid Balances:* ").append(formatInr(totalLiquid)).append("\n");
        for (var acc : liquidAccounts) {
            String name = Objects.toString(acc.get("account_name"), "Account");
            double bal = acc.get("current_balance") instanceof Number n ? n.doubleValue() : 0.0;
            wa.append("   • ").append(name).append(": ").append(formatInr(bal)).append("\n");
        }
        if (overdueArCount > 0) {
            wa.append("⚠️ *Overdue Receivables (AR):* ").append(formatInr(overdueArTotal))
              .append(" (").append(overdueArCount).append(" Invoices)\n");
        } else {
            wa.append("✅ *Overdue Receivables (AR):* Zero overdue accounts.\n");
        }
        if (overdueApCount > 0) {
            wa.append("⏳ *Pending Payables (AP):* ").append(formatInr(overdueApTotal))
              .append(" (").append(overdueApCount).append(" Bills Due)\n");
        }
        if (!topInvoices.isEmpty()) {
            wa.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            wa.append("🌟 *Top Invoices of Period:*\n");
            for (var inv : topInvoices) {
                String no = Objects.toString(inv.get("invoice_number"), "-");
                String party = Objects.toString(inv.get("party_name"), "Customer");
                double amt = inv.get("total_amount") instanceof Number n ? n.doubleValue() : 0.0;
                wa.append("   • ").append(no).append(" (").append(party).append("): ").append(formatInr(amt)).append("\n");
            }
        }
        wa.append("━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        wa.append("_Automated briefing generated by ").append(companyName).append(" ERP Engine._");
        String waText = wa.toString();

        // 11. Format Responsive HTML Email
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 24px; color: #1e293b; }");
        html.append(".card { max-width: 680px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.05); }");
        html.append(".header { background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%); color: #ffffff; padding: 24px; text-align: left; }");
        html.append(".header h2 { margin: 0 0 6px 0; font-size: 20px; font-weight: 700; letter-spacing: -0.3px; }");
        html.append(".header p { margin: 0; font-size: 13px; opacity: 0.9; }");
        html.append(".env-banner { background: #fef3c7; border-bottom: 1px solid #fde68a; color: #92400e; padding: 8px 16px; font-size: 11px; font-weight: 700; text-align: center; }");
        html.append(".body-content { padding: 24px; }");
        html.append(".metric-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; }");
        html.append(".metric-title { font-size: 11px; text-transform: uppercase; font-weight: 600; color: #64748b; margin-bottom: 4px; }");
        html.append(".metric-val { font-size: 18px; font-weight: 700; color: #0f172a; }");
        html.append(".metric-sub { font-size: 11px; color: #64748b; margin-top: 2px; }");
        html.append(".val-green { color: #16a34a; } .val-blue { color: #0284c7; } .val-indigo { color: #4f46e5; } .val-amber { color: #d97706; } .val-red { color: #dc2626; }");
        html.append(".section-title { font-size: 13px; font-weight: 700; color: #334155; margin: 20px 0 8px 0; text-transform: uppercase; letter-spacing: 0.5px; }");
        html.append("table { width: 100%; border-collapse: collapse; margin-top: 6px; }");
        html.append("th { text-align: left; font-size: 11px; color: #64748b; padding: 8px; border-bottom: 1px solid #e2e8f0; font-weight: 600; }");
        html.append("td { font-size: 13px; padding: 8px; border-bottom: 1px solid #f1f5f9; color: #1e293b; }");
        html.append(".btn-portal { display: inline-block; background: #0284c7; color: #ffffff !important; text-decoration: none; padding: 10px 22px; font-size: 13px; font-weight: 600; border-radius: 6px; margin: 16px 0; text-align: center; }");
        html.append(".btn-portal:hover { background: #0369a1; }");
        html.append(".footer { background: #f8fafc; border-top: 1px solid #e2e8f0; padding: 16px 24px; font-size: 12px; color: #64748b; text-align: center; }");
        html.append("</style></head><body>");
        html.append("<div class='card'>");
        if (!"PROD".equalsIgnoreCase(env)) {
            html.append("<div class='env-banner'>⚠️ TEST DISPATCH &bull; ").append(env).append(" ENVIRONMENT (Not Live Production)</div>");
        }
        html.append("<div class='header'><h2>").append(companyName).append(" — ").append(dateRange.cadenceLabel()).append("</h2>");
        html.append("<p>").append(dateRange.periodLabel()).append("</p></div>");
        html.append("<div class='body-content'>");

        // 6-Card Metric Grid
        html.append("<table style='margin-bottom:18px;'><tr>");
        html.append("<td style='width:50%; vertical-align:top; padding-right:8px;'><div class='metric-box'><div class='metric-title'>Invoiced Sales</div><div class='metric-val val-blue'>").append(formatInr(salesTotal)).append("</div><div class='metric-sub'>").append(salesCount).append(salesCount == 1 ? " Invoice" : " Invoices").append("</div></div></td>");
        html.append("<td style='width:50%; vertical-align:top; padding-left:8px;'><div class='metric-box'><div class='metric-title'>Purchases Booked</div><div class='metric-val val-indigo'>").append(formatInr(purchaseTotal)).append("</div><div class='metric-sub'>").append(purchaseCount).append(purchaseCount == 1 ? " Bill" : " Bills").append("</div></div></td>");
        html.append("</tr><tr>");
        html.append("<td style='width:50%; vertical-align:top; padding-right:8px; padding-top:12px;'><div class='metric-box'><div class='metric-title'>Customer Collections</div><div class='metric-val val-green'>").append(formatInr(collTotal)).append("</div><div class='metric-sub'>").append(collCount).append(collCount == 1 ? " Receipt" : " Receipts").append("</div></div></td>");
        html.append("<td style='width:50%; vertical-align:top; padding-left:8px; padding-top:12px;'><div class='metric-box'><div class='metric-title'>Vendor Payments Outflow</div><div class='metric-val val-amber'>").append(formatInr(payTotal)).append("</div><div class='metric-sub'>").append(payCount).append(payCount == 1 ? " Payment" : " Payments").append("</div></div></td>");
        html.append("</tr><tr>");
        html.append("<td style='width:50%; vertical-align:top; padding-right:8px; padding-top:12px;'><div class='metric-box'><div class='metric-title'>Net Period Cash Flow</div><div class='metric-val ").append(netCashFlow >= 0 ? "val-green" : "val-red").append("'>").append(netCashFlow >= 0 ? "+" : "").append(formatInr(netCashFlow)).append("</div><div class='metric-sub'>").append(netCashFlow > 0 ? "Cash Surplus" : (netCashFlow < 0 ? "Cash Deficit" : "Balanced")).append("</div></div></td>");
        html.append("<td style='width:50%; vertical-align:top; padding-left:8px; padding-top:12px;'><div class='metric-box'><div class='metric-title'>Critical Overdue AR</div><div class='metric-val ").append(overdueArCount > 0 ? "val-red" : "val-green").append("'>").append(formatInr(overdueArTotal)).append("</div><div class='metric-sub'>").append(overdueArCount).append(" Invoices Overdue</div></div></td>");
        html.append("</tr></table>");

        // Top Invoices Table (if any)
        if (!topInvoices.isEmpty()) {
            html.append("<div class='section-title'>Top Invoices of Period</div>");
            html.append("<table><thead><tr><th>Invoice No</th><th>Customer Name</th><th style='text-align:right;'>Amount</th></tr></thead><tbody>");
            for (var inv : topInvoices) {
                String no = Objects.toString(inv.get("invoice_number"), "-");
                String party = Objects.toString(inv.get("party_name"), "Customer");
                double amt = inv.get("total_amount") instanceof Number n ? n.doubleValue() : 0.0;
                html.append("<tr><td>").append(no).append("</td><td>").append(party).append("</td><td style='text-align:right; font-weight:600;'>").append(formatInr(amt)).append("</td></tr>");
            }
            html.append("</tbody></table>");
        }

        // Liquid Balances Table
        html.append("<div class='section-title'>Liquid Balances (Total: ").append(formatInr(totalLiquid)).append(")</div>");
        html.append("<table><thead><tr><th>Account Name</th><th>Code</th><th style='text-align:right;'>Current Balance</th></tr></thead><tbody>");
        for (var acc : liquidAccounts) {
            String name = Objects.toString(acc.get("account_name"), "Account");
            String code = Objects.toString(acc.get("account_code"), "-");
            double bal = acc.get("current_balance") instanceof Number n ? n.doubleValue() : 0.0;
            html.append("<tr><td>").append(name).append("</td><td>").append(code).append("</td><td style='text-align:right; font-weight:600;'>").append(formatInr(bal)).append("</td></tr>");
        }
        html.append("</tbody></table>");

        // Config-Gated Portal Button (White-Label: ONLY shown if portalUrl is configured)
        if (!portalUrl.isBlank()) {
            html.append("<div style='text-align:center; margin-top:24px;'>");
            html.append("<a href='").append(portalUrl).append("' class='btn-portal'>Open Web ERP Portal &rarr;</a>");
            html.append("</div>");
        }

        html.append("</div>");
        html.append("<div class='footer'>Dispatched automatically by ").append(companyName).append(" ERP Scheduled Automation Suite.</div>");
        html.append("</div></body></html>");
        String emailHtml = html.toString();

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
            String emailSubject = companyName + " — " + dateRange.cadenceLabel() + " (" + dateRange.periodLabel() + ")";
            for (String email : emails) {
                String e = email.trim();
                if (e.isBlank()) continue;
                if (mailService != null && mailService.isConfigured()) {
                    try {
                        mailService.sendBusinessFiles(e, emailSubject, emailHtml, List.of());
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
