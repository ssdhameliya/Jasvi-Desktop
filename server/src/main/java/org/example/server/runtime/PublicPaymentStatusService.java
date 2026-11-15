package org.example.server.runtime;

import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.PaymentRecordEntity;
import org.example.server.persistence.entity.PurchaseHeaderEntity;
import org.example.server.persistence.entity.SalesHeaderEntity;
import org.example.server.persistence.repository.PaymentRecordRepository;
import org.example.server.persistence.repository.PurchaseHeaderRepository;
import org.example.server.persistence.repository.SalesHeaderRepository;
import org.example.shared.PaymentStatusTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Service providing public live payment status rendering and verification.
 * Follows server persistence rules by leveraging JpaNativeRepository and Spring Data JPA.
 */
@Service
public class PublicPaymentStatusService {

    private final SalesHeaderRepository salesRepository;
    private final PurchaseHeaderRepository purchaseRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final JpaNativeRepository db;

    public PublicPaymentStatusService(
            SalesHeaderRepository salesRepository,
            PurchaseHeaderRepository purchaseRepository,
            PaymentRecordRepository paymentRecordRepository,
            JpaNativeRepository db) {
        this.salesRepository = salesRepository;
        this.purchaseRepository = purchaseRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.db = db;
    }

    private static String cleanParam(String val) {
        if (val == null) return null;
        String trimmed = val.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            for (String part : parts) {
                if (part != null && !part.trim().isBlank()) {
                    return part.trim();
                }
            }
        }
        return trimmed;
    }

    private Optional<SalesHeaderEntity> resolveSale(String invoiceNo, String verifyInvoiceNo) {
        if (invoiceNo != null && !invoiceNo.isBlank()) {
            Optional<SalesHeaderEntity> direct = salesRepository.findByInvoiceNo(invoiceNo.trim());
            if (direct.isPresent()) return direct;
        }
        if (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank() && !verifyInvoiceNo.equalsIgnoreCase(invoiceNo)) {
            Optional<SalesHeaderEntity> verifyDirect = salesRepository.findByInvoiceNo(verifyInvoiceNo.trim());
            if (verifyDirect.isPresent()) return verifyDirect;
        }
        String candidate = (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank()) ? verifyInvoiceNo.trim() : (invoiceNo != null ? invoiceNo.trim() : null);
        if (candidate != null && candidate.matches("^[0-9A-Za-z_/-]{2,40}$")) {
            try {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT id FROM sales_header WHERE invoice_no = ? OR invoice_no LIKE ? OR invoice_no LIKE ? ORDER BY id DESC LIMIT 2",
                        candidate, "%/" + candidate, "%-" + candidate);
                if (rows.size() == 1) {
                    Object val = rows.get(0).get("id");
                    if (val instanceof Number n) {
                        return salesRepository.findById(n.intValue());
                    }
                }
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    private Optional<PurchaseHeaderEntity> resolvePurchase(String invoiceNo, String verifyInvoiceNo) {
        if (invoiceNo != null && !invoiceNo.isBlank()) {
            Optional<PurchaseHeaderEntity> direct = purchaseRepository.findByInvoiceNo(invoiceNo.trim());
            if (direct.isPresent()) return direct;
        }
        if (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank() && !verifyInvoiceNo.equalsIgnoreCase(invoiceNo)) {
            Optional<PurchaseHeaderEntity> verifyDirect = purchaseRepository.findByInvoiceNo(verifyInvoiceNo.trim());
            if (verifyDirect.isPresent()) return verifyDirect;
        }
        String candidate = (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank()) ? verifyInvoiceNo.trim() : (invoiceNo != null ? invoiceNo.trim() : null);
        if (candidate != null && candidate.matches("^[0-9A-Za-z_/-]{2,40}$")) {
            try {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT id FROM purchase_header WHERE invoice_no = ? OR invoice_no LIKE ? OR invoice_no LIKE ? ORDER BY id DESC LIMIT 2",
                        candidate, "%/" + candidate, "%-" + candidate);
                if (rows.size() == 1) {
                    Object val = rows.get(0).get("id");
                    if (val instanceof Number n) {
                        return purchaseRepository.findById(n.intValue());
                    }
                }
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    public ResponseEntity<String> renderPublicPaymentStatus(String invoiceNo, String token) {
        return renderPublicPaymentStatus(invoiceNo, token, null, null, null);
    }

    public ResponseEntity<String> renderPublicPaymentStatus(
            String invoiceNo, String token,
            String verifyInvoiceNo, String verifyDate, String verifyAmount) {
        invoiceNo = cleanParam(invoiceNo);
        token = cleanParam(token);
        verifyInvoiceNo = cleanParam(verifyInvoiceNo);
        verifyDate = cleanParam(verifyDate);
        verifyAmount = cleanParam(verifyAmount);

        if ((invoiceNo == null || invoiceNo.isBlank()) && (verifyInvoiceNo == null || verifyInvoiceNo.isBlank())) {
            return ResponseEntity.ok(renderVerificationChallengePage("", token != null ? token : "", null));
        }

        Optional<SalesHeaderEntity> optionalSale = resolveSale(invoiceNo, verifyInvoiceNo);
        double totalAmount = 0.0;
        double paidAmount = 0.0;
        String docType = "SALE";
        Integer docId = null;
        String customerName = "Valued Customer";
        String invoiceDate = "-";
        String dueDate = "-";

        if (optionalSale.isPresent()) {
            SalesHeaderEntity sale = optionalSale.get();
            invoiceNo = sale.getInvoiceNo();
            totalAmount = sale.getTotalAmount() == null ? 0.0 : sale.getTotalAmount();
            paidAmount = sale.getPaidAmount() == null ? 0.0 : sale.getPaidAmount();
            docId = sale.getId();
            docType = "SALE";
            customerName = (sale.getCustomer() != null && sale.getCustomer().getName() != null)
                    ? sale.getCustomer().getName() : "Valued Customer";
            invoiceDate = sale.getInvoiceDate() == null ? "-" : sale.getInvoiceDate();
            dueDate = sale.getDueDate() == null ? "-" : sale.getDueDate();
        } else {
            Optional<PurchaseHeaderEntity> optionalPurchase = resolvePurchase(invoiceNo, verifyInvoiceNo);
            if (optionalPurchase.isPresent()) {
                PurchaseHeaderEntity purchase = optionalPurchase.get();
                invoiceNo = purchase.getInvoiceNo();
                totalAmount = purchase.getTotalAmount() == null ? 0.0 : purchase.getTotalAmount();
                paidAmount = purchase.getPaidAmount() == null ? 0.0 : purchase.getPaidAmount();
                docId = purchase.getId();
                docType = "PURCHASE";
                customerName = (purchase.getSupplier() != null && purchase.getSupplier().getName() != null)
                        ? purchase.getSupplier().getName() : "Valued Supplier";
                invoiceDate = purchase.getInvoiceDate() == null ? "-" : purchase.getInvoiceDate();
                dueDate = purchase.getDueDate() == null ? "-" : purchase.getDueDate();
            } else {
                return ResponseEntity.status(404).body(renderErrorPage("Document record not found in system register."));
            }
        }

        boolean tokenValid = (token != null && !token.isBlank() && PaymentStatusTokenService.verifyToken(invoiceNo, totalAmount, token));

        // Privacy Verification Gate Option B:
        // Configurable setting (default true). When true, recipient must answer at least one verification question
        // before the private financial payment & invoice record is displayed.
        boolean requireVerificationPrompt = !"false".equalsIgnoreCase(querySetting("payment.status.requireVerificationPrompt", "true"));

        boolean hasVerificationInput = (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank())
                || (verifyDate != null && !verifyDate.isBlank())
                || (verifyAmount != null && !verifyAmount.isBlank());

        // If verification prompt is enforced and user has not provided verification input yet, present challenge page:
        if (requireVerificationPrompt && !hasVerificationInput) {
            return ResponseEntity.ok(renderVerificationChallengePage(invoiceNo, token != null ? token : "", null));
        }

        // If token is invalid and no verification input was provided:
        if (!tokenValid && !hasVerificationInput) {
            return ResponseEntity.ok(renderVerificationChallengePage(invoiceNo, token != null ? token : "", null));
        }

        boolean verified = false;

        // Check 3 verification questions:
        if (verifyInvoiceNo != null && !verifyInvoiceNo.isBlank()) {
            String cleanInput = verifyInvoiceNo.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            String cleanDocNo = invoiceNo.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            if (cleanDocNo.equals(cleanInput) || cleanDocNo.endsWith(cleanInput) || cleanInput.endsWith(cleanDocNo)) {
                verified = true;
            }
        }
        if (!verified && verifyDate != null && !verifyDate.isBlank()) {
            String cleanDate = verifyDate.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            String cleanInvDate = invoiceDate.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            String cleanDueDate = dueDate.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            if (cleanInvDate.contains(cleanDate) || cleanDate.contains(cleanInvDate) || cleanDueDate.contains(cleanDate)) {
                verified = true;
            }
        }
        if (!verified && verifyAmount != null && !verifyAmount.isBlank()) {
            try {
                double parsed = Double.parseDouble(verifyAmount.replaceAll("[^0-9.]", "").trim());
                if (Math.abs(parsed - totalAmount) < 1.0 || Math.abs(parsed - paidAmount) < 1.0 || Math.abs(parsed - Math.max(0.0, totalAmount - paidAmount)) < 1.0) {
                    verified = true;
                }
            } catch (Exception ignored) {}
        }

        // When verification prompt is disabled in settings, valid cryptographic token is sufficient:
        if (!requireVerificationPrompt && tokenValid) {
            verified = true;
        }

        if (!verified) {
            return ResponseEntity.ok(renderVerificationChallengePage(invoiceNo, token != null ? token : "",
                    "The verification details entered do not match our records for this document. Please check your invoice or email and try again."));
        }

        double balanceAmount = Math.max(0.0, totalAmount - paidAmount);

        String statusBadgeText;
        String statusColor;
        String statusBg;
        if (paidAmount + 0.009 >= totalAmount && totalAmount > 0) {
            statusBadgeText = "FULLY PAID";
            statusColor = "#16a34a";
            statusBg = "#f0fdf4";
        } else if (paidAmount > 0) {
            statusBadgeText = "PARTIALLY PAID";
            statusColor = "#d97706";
            statusBg = "#fffbeb";
        } else {
            statusBadgeText = "PAYMENT PENDING";
            statusColor = "#dc2626";
            statusBg = "#fef2f2";
        }

        List<PaymentRecordEntity> payments = (docId != null)
                ? paymentRecordRepository.findByDocumentTypeAndDocumentIdOrderByPaymentDateDescIdDesc(docType, docId)
                : List.of();

        String companyName = querySetting("company.name", querySetting("application.displayName", "DSE ERP"));
        String companyPhone = querySetting("company.phone", "");
        String companyEmail = querySetting("company.email", "");
        String bankName = querySetting("payment.bankName", "Bank of India");
        String accountHolder = querySetting("payment.accountHolder", companyName);
        String accountNumber = querySetting("payment.accountNumber", "-");
        String ifsc = querySetting("payment.ifsc", "-");

        StringBuilder paymentRowsHtml = new StringBuilder();
        if (payments.isEmpty()) {
            paymentRowsHtml.append("""
                <tr>
                    <td colspan="5" style="text-align: center; padding: 18px; color: #64748b; font-style: italic;">
                        No payment transactions recorded yet.
                    </td>
                </tr>
            """);
        } else {
            for (PaymentRecordEntity p : payments) {
                String date = p.getPaymentDate() == null ? "-" : p.getPaymentDate();
                String mode = p.getPaymentMode() == null ? "-" : p.getPaymentMode();
                String ref = p.getReferenceNo() == null || p.getReferenceNo().isBlank() ? "-" : p.getReferenceNo();
                double amt = p.getAmount() == null ? 0.0 : p.getAmount();
                paymentRowsHtml.append("""
                    <tr>
                        <td style="padding: 10px 14px; border-bottom: 1px solid #f1f5f9; color: #334155;">%s</td>
                        <td style="padding: 10px 14px; border-bottom: 1px solid #f1f5f9; color: #334155;">%s</td>
                        <td style="padding: 10px 14px; border-bottom: 1px solid #f1f5f9; color: #475569; font-family: monospace;">%s</td>
                        <td style="padding: 10px 14px; border-bottom: 1px solid #f1f5f9; text-align: right; font-weight: 600; color: #16a34a;">%s</td>
                        <td style="padding: 10px 14px; border-bottom: 1px solid #f1f5f9; text-align: center;">
                            <span style="background: #f0fdf4; color: #16a34a; font-size: 11px; font-weight: 700; padding: 3px 8px; border-radius: 9999px;">CONFIRMED</span>
                        </td>
                    </tr>
                """.formatted(escape(date), escape(mode), escape(ref), formatCurrency(amt)));
            }
        }

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Payment Status • %s</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: #f8fafc;
                        color: #1e293b;
                        padding: 24px 16px;
                        line-height: 1.5;
                    }
                    .container {
                        max-width: 680px;
                        margin: 0 auto;
                        background: #ffffff;
                        border-radius: 16px;
                        border: 1px solid #e2e8f0;
                        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.05);
                        overflow: hidden;
                    }
                    .header {
                        background: linear-gradient(135deg, #0f172a 0%%, #1e293b 100%%);
                        color: #ffffff;
                        padding: 28px 24px;
                        text-align: center;
                    }
                    .header h1 {
                        font-size: 20px;
                        font-weight: 700;
                        letter-spacing: 0.5px;
                        margin-bottom: 4px;
                    }
                    .header p {
                        font-size: 13px;
                        color: #94a3b8;
                    }
                    .content {
                        padding: 24px;
                    }
                    .status-pill {
                        display: inline-block;
                        padding: 6px 16px;
                        border-radius: 9999px;
                        font-size: 12px;
                        font-weight: 700;
                        letter-spacing: 0.5px;
                        margin-bottom: 20px;
                    }
                    .grid-summary {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 12px;
                        margin-bottom: 24px;
                    }
                    .summary-card {
                        background: #f8fafc;
                        border: 1px solid #e2e8f0;
                        border-radius: 10px;
                        padding: 14px;
                        text-align: center;
                    }
                    .summary-card .label {
                        font-size: 11px;
                        font-weight: 600;
                        text-transform: uppercase;
                        color: #64748b;
                        margin-bottom: 4px;
                    }
                    .summary-card .val {
                        font-size: 16px;
                        font-weight: 700;
                    }
                    .section-title {
                        font-size: 13px;
                        font-weight: 700;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        color: #475569;
                        margin: 20px 0 10px 0;
                        border-bottom: 1px solid #f1f5f9;
                        padding-bottom: 6px;
                    }
                    table {
                        width: 100%%;
                        border-collapse: collapse;
                        font-size: 13px;
                    }
                    th {
                        background: #f8fafc;
                        color: #475569;
                        font-weight: 600;
                        padding: 10px 14px;
                        text-align: left;
                        border-bottom: 2px solid #e2e8f0;
                        font-size: 11px;
                        text-transform: uppercase;
                    }
                    .bank-card {
                        background: #f0fdf4;
                        border: 1px solid #bbf7d0;
                        border-radius: 10px;
                        padding: 16px;
                        margin-top: 16px;
                    }
                    .bank-card h4 {
                        color: #166534;
                        font-size: 13px;
                        margin-bottom: 8px;
                    }
                    .bank-details {
                        font-size: 12px;
                        color: #15803d;
                        line-height: 1.6;
                    }
                    .footer {
                        text-align: center;
                        padding: 20px 24px;
                        background: #f8fafc;
                        border-top: 1px solid #f1f5f9;
                        font-size: 12px;
                        color: #64748b;
                    }
                    @media (max-width: 600px) {
                        .grid-summary { grid-template-columns: 1fr; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>%s</h1>
                        <p>Live Payment Verification & Invoice Status</p>
                    </div>
                    <div class="content">
                        <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;">
                            <div>
                                <div style="font-size: 12px; color: #64748b;">INVOICE NUMBER</div>
                                <div style="font-size: 18px; font-weight: 700; color: #0f172a;">%s</div>
                                <div style="font-size: 13px; color: #475569; margin-top: 2px;">Billed To: <strong>%s</strong></div>
                                <div style="font-size: 12px; color: #64748b;">Date: %s • Due: %s</div>
                            </div>
                            <div style="text-align: right;">
                                <span class="status-pill" style="background: %s; color: %s;">
                                    %s
                                </span>
                            </div>
                        </div>

                        <div class="grid-summary">
                            <div class="summary-card">
                                <div class="label">Total Billed</div>
                                <div class="val" style="color: #0f172a;">%s</div>
                            </div>
                            <div class="summary-card">
                                <div class="label">Total Received</div>
                                <div class="val" style="color: #16a34a;">%s</div>
                            </div>
                            <div class="summary-card">
                                <div class="label">Balance Due</div>
                                <div class="val" style="color: #dc2626;">%s</div>
                            </div>
                        </div>

                        <div class="section-title">Payment Transaction History</div>
                        <div style="overflow-x: auto; margin-bottom: 16px;">
                            <table>
                                <thead>
                                    <tr>
                                        <th>Date</th>
                                        <th>Mode</th>
                                        <th>Reference / UTR</th>
                                        <th style="text-align: right;">Amount</th>
                                        <th style="text-align: center;">Status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    %s
                                </tbody>
                            </table>
                        </div>

                        <div class="bank-card">
                            <h4>🏛️ Beneficiary Bank Details for Settlement</h4>
                            <div class="bank-details">
                                <div><strong>Bank:</strong> %s</div>
                                <div><strong>Account Holder:</strong> %s</div>
                                <div><strong>Account Number:</strong> %s</div>
                                <div><strong>IFSC Code:</strong> %s</div>
                                <div style="margin-top: 6px; font-size: 11px; color: #166534;">
                                    Please mention Invoice <strong>%s</strong> in the transaction remarks for rapid automatic reconciliation.
                                </div>
                            </div>
                        </div>
                    </div>

                    <div class="footer">
                        <div>Need assistance? Contact our accounts team:</div>
                        <div style="margin-top: 4px; font-weight: 500;">
                            %s • %s • %s
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.formatted(
                escape(invoiceNo),
                escape(companyName),
                escape(invoiceNo),
                escape(customerName),
                escape(invoiceDate),
                escape(dueDate),
                statusBg,
                statusColor,
                statusBadgeText,
                formatCurrency(totalAmount),
                formatCurrency(paidAmount),
                formatCurrency(balanceAmount),
                paymentRowsHtml.toString(),
                escape(bankName),
                escape(accountHolder),
                escape(accountNumber),
                escape(ifsc),
                escape(invoiceNo),
                escape(companyName),
                escape(companyEmail),
                escape(companyPhone)
        );

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(html);
    }

    private String querySetting(String key, String fallback) {
        try {
            String value = db.queryForObject("SELECT setting_value FROM application_setting WHERE setting_key = ?", String.class, key);
            return (value == null || value.isBlank()) ? fallback : value.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String formatCurrency(double amount) {
        return "₹ " + String.format(Locale.of("en", "IN"), "%,.2f", amount);
    }

    private static String escape(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String renderVerificationChallengePage(String invoiceNo, String token, String errorMessage) {
        String company = querySetting("company.name", querySetting("application.displayName", "DSE ERP"));
        String errorBlock = (errorMessage == null || errorMessage.isBlank()) ? "" : """
            <div style="background: #fef2f2; border: 1px solid #fecaca; color: #b91c1c; padding: 12px 16px; border-radius: 8px; font-size: 13px; margin-bottom: 20px; text-align: left;">
                &#9888; <strong>Verification Notice:</strong> %s
            </div>
        """.formatted(escape(errorMessage));

        String template = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Document Privacy Verification &bull; {{COMPANY}}</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: #f1f5f9;
                        color: #1e293b;
                        padding: 32px 16px;
                    }
                    .card {
                        max-width: 580px;
                        margin: 0 auto;
                        background: #ffffff;
                        border-radius: 12px;
                        border: 1px solid #e2e8f0;
                        box-shadow: 0 4px 16px rgba(15, 23, 42, 0.08);
                        overflow: hidden;
                    }
                    .header {
                        background: #1e3a8a;
                        color: #ffffff;
                        padding: 24px;
                        text-align: center;
                    }
                    .header h1 {
                        font-size: 20px;
                        font-weight: 700;
                        letter-spacing: 0.5px;
                        margin-bottom: 4px;
                    }
                    .header p {
                        font-size: 12px;
                        opacity: 0.85;
                    }
                    .content {
                        padding: 28px 24px;
                    }
                    .title-row {
                        margin-bottom: 18px;
                    }
                    .title-row h2 {
                        font-size: 17px;
                        color: #0f172a;
                        font-weight: 700;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }
                    .title-row p {
                        font-size: 13px;
                        color: #64748b;
                        margin-top: 6px;
                        line-height: 1.5;
                    }
                    .form-group {
                        margin-bottom: 16px;
                    }
                    .form-group label {
                        display: block;
                        font-size: 13px;
                        font-weight: 600;
                        color: #334155;
                        margin-bottom: 6px;
                    }
                    .form-group input {
                        width: 100%%;
                        padding: 11px 14px;
                        border: 1px solid #cbd5e1;
                        border-radius: 6px;
                        font-size: 14px;
                        color: #0f172a;
                        outline: none;
                        transition: border-color 0.2s;
                    }
                    .form-group input:focus {
                        border-color: #2563eb;
                        box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
                    }
                    .tip {
                        font-size: 11px;
                        color: #64748b;
                        margin-top: 5px;
                        display: block;
                        line-height: 1.4;
                    }
                    .divider {
                        text-align: center;
                        position: relative;
                        margin: 18px 0;
                    }
                    .divider::before {
                        content: "";
                        position: absolute;
                        left: 0;
                        top: 50%%;
                        width: 100%%;
                        height: 1px;
                        background: #e2e8f0;
                    }
                    .divider span {
                        position: relative;
                        background: #ffffff;
                        padding: 0 14px;
                        font-size: 11px;
                        font-weight: 700;
                        color: #94a3b8;
                        letter-spacing: 1px;
                    }
                    .btn-submit {
                        width: 100%%;
                        background: #1e3a8a;
                        color: #ffffff;
                        border: none;
                        padding: 13px 20px;
                        font-size: 14px;
                        font-weight: 700;
                        border-radius: 6px;
                        cursor: pointer;
                        box-shadow: 0 2px 6px rgba(30, 58, 138, 0.25);
                        margin-top: 10px;
                        transition: background 0.2s;
                    }
                    .btn-submit:hover {
                        background: #1d4ed8;
                    }
                    .footer {
                        background: #f8fafc;
                        border-top: 1px solid #f1f5f9;
                        padding: 14px 24px;
                        text-align: center;
                        font-size: 11px;
                        color: #64748b;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="header">
                        <h1>{{COMPANY}}</h1>
                        <p>Official Cloud Settlement &amp; Invoicing Gateway</p>
                    </div>
                    <div class="content">
                        {{ERROR_BLOCK}}
                        <div class="title-row">
                            <h2>&#128274; Document Privacy Verification</h2>
                            <p>For your financial security and privacy, please confirm your document details to view the live settlement and payment reconciliation record. Entering <strong>ANY ONE</strong> of the fields below will unlock your live status:</p>
                        </div>
                        <form id="verifyForm" method="POST" action="" onsubmit="return handleFormSubmit(event);">
                            <input type="hidden" name="inv" value="{{INVOICE_NO}}" />
                            <input type="hidden" name="token" value="{{TOKEN}}" />

                            <div id="clientValidationNotice" style="display:none; background:#fffbeb; border:1px solid #fde68a; color:#92400e; padding:12px 16px; border-radius:8px; font-size:13px; margin-bottom:16px;">
                                &#9888; <strong>Please enter details:</strong> Provide at least ONE of the 3 fields below to unlock your status.
                            </div>

                            <div class="form-group">
                                <label>1. Invoice / Bill Number</label>
                                <input type="text" id="verifyInvoiceNo" name="verifyInvoiceNo" placeholder="e.g. PUR/10-11-2026/5144 or 5144" autocomplete="off" />
                                <span class="tip">&#128161; <strong>Helpful Tip:</strong> Found on your invoice header, tax bill, or the subject line of your received email.</span>
                            </div>

                            <div class="divider"><span>— OR —</span></div>

                            <div class="form-group">
                                <label>2. Document Date</label>
                                <input type="text" id="verifyDate" name="verifyDate" placeholder="e.g. 10-11-2026 or 10 Nov 2026" autocomplete="off" />
                                <span class="tip">&#128161; <strong>Helpful Tip:</strong> The date printed on your invoice or the settlement advice date shown in your email.</span>
                            </div>

                            <div class="divider"><span>— OR —</span></div>

                            <div class="form-group">
                                <label>3. Total or Settlement Amount (₹)</label>
                                <input type="text" id="verifyAmount" name="verifyAmount" placeholder="e.g. 38,545,002.08 or 38545002" autocomplete="off" />
                                <span class="tip">&#128161; <strong>Helpful Tip:</strong> Total invoice value or the payment disbursed amount stated in your remittance breakdown.</span>
                            </div>

                            <button type="submit" id="btnSubmit" class="btn-submit">&#128275; Verify &amp; View Live Status</button>
                        </form>
                        <script>
                            function handleFormSubmit(e) {
                                var inv = (document.getElementById('verifyInvoiceNo') ? document.getElementById('verifyInvoiceNo').value : '').trim();
                                var dt = (document.getElementById('verifyDate') ? document.getElementById('verifyDate').value : '').trim();
                                var amt = (document.getElementById('verifyAmount') ? document.getElementById('verifyAmount').value : '').trim();
                                var notice = document.getElementById('clientValidationNotice');
                                if (!inv && !dt && !amt) {
                                    if (e && e.preventDefault) e.preventDefault();
                                    if (notice) notice.style.display = 'block';
                                    var fld = document.getElementById('verifyInvoiceNo');
                                    if (fld) { fld.focus(); fld.style.borderColor = '#ef4444'; }
                                    return false;
                                }
                                if (notice) notice.style.display = 'none';
                                var form = document.getElementById('verifyForm');
                                if (form && window.location.pathname) {
                                    form.action = window.location.pathname;
                                }
                                var btn = document.getElementById('btnSubmit');
                                if (btn) {
                                    btn.innerHTML = '&#8987; Verifying Credentials...';
                                    btn.style.opacity = '0.8';
                                    btn.style.cursor = 'wait';
                                }
                                return true;
                            }
                        </script>
                    </div>
                    <div class="footer">
                        &#128737; 256-bit Encrypted Verification &bull; Official Portal of {{COMPANY}}
                    </div>
                </div>
            </body>
            </html>
        """;

        return template
                .replace("{{COMPANY}}", escape(company))
                .replace("{{ERROR_BLOCK}}", errorBlock)
                .replace("{{INVOICE_NO}}", escape(invoiceNo))
                .replace("{{TOKEN}}", escape(token));
    }

    private String renderErrorPage(String message) {
        String company = querySetting("company.name", querySetting("application.displayName", "DSE ERP"));
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Verification Error • %s</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #f8fafc; color: #1e293b; padding: 40px 16px; text-align: center; }
                    .card { max-width: 480px; margin: 0 auto; background: #fff; padding: 32px; border-radius: 12px; border: 1px solid #e2e8f0; box-shadow: 0 4px 12px rgba(0,0,0,0.05); }
                    h2 { color: #dc2626; margin-bottom: 12px; font-size: 20px; }
                    p { color: #64748b; font-size: 14px; line-height: 1.6; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>Verification Notice</h2>
                    <p>%s</p>
                    <p style="margin-top: 16px; font-size: 12px; color: #94a3b8;">If you believe this is in error, please contact our billing department.</p>
                </div>
            </body>
            </html>
        """.formatted(escape(company), escape(message));
    }
}
