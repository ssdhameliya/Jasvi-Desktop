package org.example.server.runtime;

import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.PaymentRecordEntity;
import org.example.server.persistence.entity.SalesHeaderEntity;
import org.example.server.persistence.repository.PaymentRecordRepository;
import org.example.server.persistence.repository.SalesHeaderRepository;
import org.example.shared.PaymentStatusTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Service providing public live payment status rendering and verification.
 * Follows server persistence rules by leveraging JpaNativeRepository and Spring Data JPA.
 */
@Service
public class PublicPaymentStatusService {

    private final SalesHeaderRepository salesRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final JpaNativeRepository db;

    public PublicPaymentStatusService(
            SalesHeaderRepository salesRepository,
            PaymentRecordRepository paymentRecordRepository,
            JpaNativeRepository db) {
        this.salesRepository = salesRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.db = db;
    }

    public ResponseEntity<String> renderPublicPaymentStatus(String invoiceNo, String token) {
        if (invoiceNo == null || invoiceNo.isBlank() || token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(renderErrorPage("Invalid or incomplete verification link."));
        }

        Optional<SalesHeaderEntity> optionalSale = salesRepository.findByInvoiceNo(invoiceNo.trim());
        if (optionalSale.isEmpty()) {
            return ResponseEntity.status(404).body(renderErrorPage("Invoice record not found in system register."));
        }

        SalesHeaderEntity sale = optionalSale.get();
        double totalAmount = sale.getTotalAmount() == null ? 0.0 : sale.getTotalAmount();

        if (!PaymentStatusTokenService.verifyToken(invoiceNo, totalAmount, token)) {
            return ResponseEntity.status(403).body(renderErrorPage("Security verification signature mismatch or link has expired."));
        }

        double paidAmount = sale.getPaidAmount() == null ? 0.0 : sale.getPaidAmount();
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

        List<PaymentRecordEntity> payments = paymentRecordRepository
                .findByDocumentTypeAndDocumentIdOrderByPaymentDateDescIdDesc("SALE", sale.getId());

        String companyName = querySetting("company.name", "JASVI INDUSTRIES");
        String companyPhone = querySetting("company.phone", "+91 98765 43210");
        String companyEmail = querySetting("company.email", "sales@jasviindustries.in");
        String bankName = querySetting("payment.bankName", "HDFC Bank");
        String accountHolder = querySetting("payment.accountHolder", companyName);
        String accountNumber = querySetting("payment.accountNumber", "50200012345678");
        String ifsc = querySetting("payment.ifsc", "HDFC0001234");

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

        String customerName = (sale.getCustomer() != null && sale.getCustomer().getName() != null)
                ? sale.getCustomer().getName() : "Valued Customer";

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
                escape(sale.getInvoiceDate() == null ? "-" : sale.getInvoiceDate()),
                escape(sale.getDueDate() == null ? "-" : sale.getDueDate()),
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

    private String renderErrorPage(String message) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Verification Error • Jasvi ERP</title>
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
        """.formatted(escape(message));
    }
}
