package org.example.service;

import org.example.config.ConfigManager;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.example.util.BusinessClock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manages user-configurable email and WhatsApp message templates, signatures,
 * and dynamic token substitutions for business documents.
 */
public final class MessageTemplateService {

    public enum DocumentType {
        SALES_INVOICE("sale", "Sales Invoice"),
        QUOTATION("quote", "Quotation"),
        PURCHASE_BILL("purchase", "Purchase Bill"),
        PAYMENT_RECEIPT("payment", "Payment Receipt"),
        SALES_RETURN("sales_return", "Sales Return"),
        PURCHASE_RETURN("purchase_return", "Purchase Return");

        private final String key;
        private final String displayName;

        DocumentType(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String key() {
            return key;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public record FormattedMessage(String subject, String body) {}

    private MessageTemplateService() {}

    /**
     * Replaces dynamic tokens such as {CustomerName}, {DocumentNo}, {TotalAmount}, etc.
     */
    public static String format(String template, Map<String, String> tokens) {
        if (template == null || template.isBlank()) return "";
        String result = template;
        if (tokens != null) {
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                String token = entry.getKey();
                String value = entry.getValue() == null ? "" : entry.getValue();
                result = result.replace("{" + token + "}", value);
            }
        }
        return result;
    }

    public static String getSignature() {
        return ConfigManager.get("email.signature", defaultSignature());
    }

    public static String getSignature(DocumentType type) {
        if (type != null) {
            String custom = ConfigManager.get("email.signature." + type.key(), "").trim();
            if (!custom.isBlank()) {
                String company = BrandingService.companyName();
                String phone = ConfigManager.get("company.phone", "").trim();
                String email = ConfigManager.get("company.email", "").trim();
                return custom.replace("{CompanyName}", company)
                             .replace("{CompanyPhone}", phone)
                             .replace("{CompanyEmail}", email);
            }
        }
        return getSignature();
    }

    public static void setSignature(String signature) {
        ConfigManager.setWithoutSaving("email.signature", signature == null ? "" : signature.trim());
    }

    public static void setSignature(DocumentType type, String signature) {
        if (type == null) {
            setSignature(signature);
        } else {
            ConfigManager.setWithoutSaving("email.signature." + type.key(), signature == null ? "" : signature.trim());
        }
    }

    public static String getSignatureImagePath() {
        String path = ConfigManager.get("email.signature.image", "").trim();
        if (!path.isBlank()) {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.isFile()) return f.getAbsolutePath().replace('\\', '/');
            try {
                java.nio.file.Path p = ConfigManager.getConfigurationFolder().resolve(path);
                if (java.nio.file.Files.isRegularFile(p)) return p.toAbsolutePath().toString().replace('\\', '/');
            } catch (Exception ignored) {}
        }
        String fallback = ConfigManager.get("company.signaturePath", "").trim();
        if (!fallback.isBlank()) {
            java.io.File f = new java.io.File(fallback);
            if (f.exists() && f.isFile()) return f.getAbsolutePath().replace('\\', '/');
            try {
                java.nio.file.Path p = ConfigManager.getConfigurationFolder().resolve(fallback);
                if (java.nio.file.Files.isRegularFile(p)) return p.toAbsolutePath().toString().replace('\\', '/');
            } catch (Exception ignored) {}
        }
        return path;
    }

    public static void setSignatureImagePath(String path) {
        ConfigManager.setWithoutSaving("email.signature.image", path == null ? "" : path.trim());
    }

    public static boolean isAttachSignatureToEmail() {
        return ConfigManager.getBoolean("email.signature.attachToEmail", true);
    }

    public static boolean isAttachSignatureToWhatsapp() {
        return ConfigManager.getBoolean("email.signature.attachToWhatsapp", false);
    }

    public static void setAttachSignatureToEmail(boolean enabled) {
        ConfigManager.setWithoutSaving("email.signature.attachToEmail", String.valueOf(enabled));
    }

    public static void setAttachSignatureToWhatsapp(boolean enabled) {
        ConfigManager.setWithoutSaving("email.signature.attachToWhatsapp", String.valueOf(enabled));
    }

    public static String defaultSignature() {
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("Regards,\n").append(company);
        if (!phone.isBlank() || !email.isBlank()) {
            sb.append("\n");
            if (!phone.isBlank()) sb.append("Phone: ").append(phone);
            if (!phone.isBlank() && !email.isBlank()) sb.append(" | ");
            if (!email.isBlank()) sb.append("Email: ").append(email);
        }
        return sb.toString();
    }

    public static String getEmailSubject(DocumentType type) {
        return ConfigManager.get("email.template." + type.key() + ".subject", defaultEmailSubject(type));
    }

    public static String getEmailBody(DocumentType type) {
        return ConfigManager.get("email.template." + type.key() + ".body", defaultEmailBody(type));
    }

    public static void setEmailTemplate(DocumentType type, String subject, String body) {
        ConfigManager.setWithoutSaving("email.template." + type.key() + ".subject", subject == null ? "" : subject.trim());
        ConfigManager.setWithoutSaving("email.template." + type.key() + ".body", body == null ? "" : body.trim());
    }

    public static String getWhatsappBody(DocumentType type) {
        return ConfigManager.get("whatsapp.template." + type.key() + ".body", defaultWhatsappBody(type));
    }

    public static void setWhatsappTemplate(DocumentType type, String body) {
        ConfigManager.setWithoutSaving("whatsapp.template." + type.key() + ".body", body == null ? "" : body.trim());
    }

    public static String defaultEmailSubject(DocumentType type) {
        return switch (type) {
            case SALES_INVOICE -> "Sales Invoice {DocumentNo} from {CompanyName}";
            case QUOTATION -> "Quotation {DocumentNo} from {CompanyName}";
            case PURCHASE_BILL -> "Purchase Order {DocumentNo} from {CompanyName}";
            case PAYMENT_RECEIPT -> "Payment Receipt for {DocumentNo} from {CompanyName}";
            case SALES_RETURN -> "Sales Return Note {DocumentNo} from {CompanyName}";
            case PURCHASE_RETURN -> "Purchase Return Note {DocumentNo} from {CompanyName}";
        };
    }

    public static String defaultEmailBody(DocumentType type) {
        return switch (type) {
            case SALES_INVOICE -> """
                Dear {CustomerName},

                Please find attached your sales invoice {DocumentNo} dated {DocumentDate}.

                Invoice Summary:
                • Invoice Number: {DocumentNo}
                • Invoice Date: {DocumentDate}
                • Total Amount: {TotalAmount}
                • Balance Due: {BalanceAmount}

                Thank you for your business.

                {Signature}""";
            case QUOTATION -> """
                Dear {CustomerName},

                Thank you for your enquiry. Please find attached our quotation {DocumentNo} dated {DocumentDate} for total amount {TotalAmount}.

                We look forward to serving you. Please let us know if you need any adjustments or further information.

                {Signature}""";
            case PURCHASE_BILL -> """
                Dear {CustomerName},

                Please find attached our purchase order {DocumentNo} dated {DocumentDate}.

                Kindly confirm receipt and proceed with fulfillment per agreed terms.

                {Signature}""";
            case PAYMENT_RECEIPT -> """
                Dear {CustomerName},

                Thank you for your payment. We have successfully received payment against document {DocumentNo}.

                Receipt Summary:
                • Document Number: {DocumentNo}
                • Receipt Date: {DocumentDate}
                • Amount Paid: {PaidAmount}
                • Remaining Balance: {BalanceAmount}

                Thank you for your business.

                {Signature}""";
            case SALES_RETURN -> """
                Dear {CustomerName},

                Please find attached sales return / credit note {DocumentNo} dated {DocumentDate}.

                • Total Credit: {TotalAmount}

                {Signature}""";
            case PURCHASE_RETURN -> """
                Dear {CustomerName},

                Please find attached purchase return / debit note {DocumentNo} dated {DocumentDate}.

                • Total Amount: {TotalAmount}

                {Signature}""";
        };
    }

    public static String defaultWhatsappBody(DocumentType type) {
        return switch (type) {
            case SALES_INVOICE -> "Hello *{CustomerName}*,\n\nPlease find attached sales invoice *{DocumentNo}* dated *{DocumentDate}* for total amount *{TotalAmount}* (Balance: *{BalanceAmount}*).\n\nThank you for your business!\n\nRegards,\n*{CompanyName}*";
            case QUOTATION -> "Hello *{CustomerName}*,\n\nPlease find attached quotation *{DocumentNo}* dated *{DocumentDate}* for total amount *{TotalAmount}*.\n\nLooking forward to your feedback!\n\nRegards,\n*{CompanyName}*";
            case PURCHASE_BILL -> "Hello *{CustomerName}*,\n\nPlease find attached purchase order *{DocumentNo}* dated *{DocumentDate}*.\n\nRegards,\n*{CompanyName}*";
            case PAYMENT_RECEIPT -> "Hello *{CustomerName}*,\n\nWe have received payment of *{PaidAmount}* against *{DocumentNo}* (Balance: *{BalanceAmount}*).\n\nThank you!\n\n*{CompanyName}*";
            case SALES_RETURN -> "Hello *{CustomerName}*,\n\nPlease find attached sales return note *{DocumentNo}* for amount *{TotalAmount}*.\n\n*{CompanyName}*";
            case PURCHASE_RETURN -> "Hello *{CustomerName}*,\n\nPlease find attached purchase return note *{DocumentNo}* for amount *{TotalAmount}*.\n\n*{CompanyName}*";
        };
    }

    public static FormattedMessage formatSalesEmail(Sales sale) {
        Map<String, String> tokens = buildTokens(
            sale.getCustomer() == null ? "Customer" : sale.getCustomer().getName(),
            sale.getInvoiceNo(),
            sale.getInvoiceDate(),
            sale.getTotalAmount(),
            sale.getPaidAmount(),
            sale.getBalanceAmount()
        );
        String subject = format(getEmailSubject(DocumentType.SALES_INVOICE), tokens);
        String rawBody = getEmailBody(DocumentType.SALES_INVOICE);
        String body = format(rawBody, tokens);
        String signature = tokens.get("Signature");
        if (signature != null && !signature.isBlank() && !rawBody.contains("{Signature}") && !body.contains(signature.trim())) {
            body = body.stripTrailing() + "\n\n" + signature.trim();
        }
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatPurchaseEmail(Purchase purchase) {
        Map<String, String> tokens = buildTokens(
            purchase.getSupplier() == null ? "Supplier" : purchase.getSupplier().getName(),
            purchase.getInvoiceNo(),
            purchase.getInvoiceDate(),
            purchase.getTotalAmount(),
            purchase.getPaidAmount(),
            purchase.getBalanceAmount()
        );
        String subject = format(getEmailSubject(DocumentType.PURCHASE_BILL), tokens);
        String rawBody = getEmailBody(DocumentType.PURCHASE_BILL);
        String body = format(rawBody, tokens);
        String signature = tokens.get("Signature");
        if (signature != null && !signature.isBlank() && !rawBody.contains("{Signature}") && !body.contains(signature.trim())) {
            body = body.stripTrailing() + "\n\n" + signature.trim();
        }
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatQuotationEmail(String quoteNo, String customerName, LocalDate date, double amount) {
        Map<String, String> tokens = buildTokens(
            customerName == null || customerName.isBlank() ? "Customer" : customerName,
            quoteNo,
            date,
            amount,
            0.0,
            amount
        );
        String subject = format(getEmailSubject(DocumentType.QUOTATION), tokens);
        String rawBody = getEmailBody(DocumentType.QUOTATION);
        String body = format(rawBody, tokens);
        String signature = tokens.get("Signature");
        if (signature != null && !signature.isBlank() && !rawBody.contains("{Signature}") && !body.contains(signature.trim())) {
            body = body.stripTrailing() + "\n\n" + signature.trim();
        }
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatSalesReturnEmail(String returnNo, String partyName, LocalDate date, double amount) {
        Map<String, String> tokens = buildTokens(
            partyName == null || partyName.isBlank() ? "Customer" : partyName,
            returnNo,
            date,
            amount,
            0.0,
            amount
        );
        String subject = format(getEmailSubject(DocumentType.SALES_RETURN), tokens);
        String rawBody = getEmailBody(DocumentType.SALES_RETURN);
        String body = format(rawBody, tokens);
        String signature = tokens.get("Signature");
        if (signature != null && !signature.isBlank() && !rawBody.contains("{Signature}") && !body.contains(signature.trim())) {
            body = body.stripTrailing() + "\n\n" + signature.trim();
        }
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatSalesReturnEmail(String returnNo, String partyName, String dateStr, double amount) {
        LocalDate parsed = null;
        if (dateStr != null && !dateStr.isBlank()) {
            try { parsed = LocalDate.parse(dateStr.trim()); } catch (Exception ignored) {}
        }
        return formatSalesReturnEmail(returnNo, partyName, parsed, amount);
    }

    public static FormattedMessage formatPurchaseReturnEmail(String returnNo, String partyName, LocalDate date, double amount) {
        Map<String, String> tokens = buildTokens(
            partyName == null || partyName.isBlank() ? "Supplier" : partyName,
            returnNo,
            date,
            amount,
            0.0,
            amount
        );
        String subject = format(getEmailSubject(DocumentType.PURCHASE_RETURN), tokens);
        String rawBody = getEmailBody(DocumentType.PURCHASE_RETURN);
        String body = format(rawBody, tokens);
        String signature = tokens.get("Signature");
        if (signature != null && !signature.isBlank() && !rawBody.contains("{Signature}") && !body.contains(signature.trim())) {
            body = body.stripTrailing() + "\n\n" + signature.trim();
        }
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatPurchaseReturnEmail(String returnNo, String partyName, String dateStr, double amount) {
        LocalDate parsed = null;
        if (dateStr != null && !dateStr.isBlank()) {
            try { parsed = LocalDate.parse(dateStr.trim()); } catch (Exception ignored) {}
        }
        return formatPurchaseReturnEmail(returnNo, partyName, parsed, amount);
    }

    public static String formatSalesWhatsapp(Sales sale) {
        String configured = ConfigManager.get("whatsapp.template.sale.body", "").trim();
        if (configured.isBlank()) {
            return PaymentMessageService.salesMessage(sale);
        }
        Map<String, String> tokens = buildTokens(
            sale.getCustomer() == null ? "Customer" : sale.getCustomer().getName(),
            sale.getInvoiceNo(),
            sale.getInvoiceDate(),
            sale.getTotalAmount(),
            sale.getPaidAmount(),
            sale.getBalanceAmount()
        );
        return format(configured, tokens);
    }

    public static String formatQuotationWhatsapp(int quotationId, String quoteNo, String customerName, LocalDate date, double amount) {
        String configured = ConfigManager.get("whatsapp.template.quote.body", "").trim();
        if (configured.isBlank()) {
            return PaymentMessageService.quotationMessage(quotationId);
        }
        Map<String, String> tokens = buildTokens(
            customerName == null || customerName.isBlank() ? "Customer" : customerName,
            quoteNo,
            date,
            amount,
            0.0,
            amount
        );
        return format(configured, tokens);
    }

    public static Map<String, String> buildTokens(String customerName, String documentNo, LocalDate date,
                                                  double totalAmount, double paidAmount, double balanceAmount) {
        return buildTokens(customerName, documentNo, date, totalAmount, paidAmount, balanceAmount, null);
    }

    public static Map<String, String> buildTokens(String customerName, String documentNo, LocalDate date,
                                                  double totalAmount, double paidAmount, double balanceAmount, DocumentType type) {
        Map<String, String> tokens = new HashMap<>();
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        String signature = type == null ? getSignature() : getSignature(type);
        // Replace company tokens inside signature as well
        signature = signature.replace("{CompanyName}", company)
                             .replace("{CompanyPhone}", phone)
                             .replace("{CompanyEmail}", email);

        tokens.put("CustomerName", customerName == null || customerName.isBlank() ? "Customer" : customerName);
        tokens.put("PartyName", customerName == null || customerName.isBlank() ? "Customer" : customerName);
        tokens.put("DocumentNo", documentNo == null ? "" : documentNo);
        tokens.put("DocumentDate", date == null ? "" : BusinessClock.formatDate(date));
        tokens.put("TotalAmount", money(totalAmount));
        tokens.put("PaidAmount", money(paidAmount));
        tokens.put("BalanceAmount", money(balanceAmount));
        tokens.put("CompanyName", company);
        tokens.put("CompanyPhone", phone);
        tokens.put("CompanyEmail", email);
        tokens.put("Signature", signature);
        return tokens;
    }

    public static FormattedMessage formatPaymentReceiptEmail(String customerName, String docNo, LocalDate date,
                                                             double totalAmount, double paidAmount, double balanceAmount,
                                                             String refNo, String paymentDate, String narration) {
        Map<String, String> tokens = buildTokens(customerName, docNo, date, totalAmount, paidAmount, balanceAmount, DocumentType.PAYMENT_RECEIPT);
        tokens.put("PaymentReference", refNo == null ? "" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? "" : paymentDate);
        tokens.put("Narration", narration == null ? "" : narration);

        String ourBank = ConfigManager.get("payment.bankName", "").trim();
        String ourAcc = ConfigManager.get("payment.accountNumber", "").trim();
        String ourIfsc = ConfigManager.get("payment.ifsc", "").trim();
        String ourHolder = ConfigManager.get("payment.accountHolder", BrandingService.companyName()).trim();

        StringBuilder dualBank = new StringBuilder();
        dualBank.append("\n\nPayment & Bank Settlement Details:\n");
        dualBank.append("• Beneficiary Bank: ").append(ourBank.isBlank() ? "Company Bank Account" : ourBank)
                .append(ourAcc.isBlank() ? "" : " | A/C: " + ourAcc)
                .append(ourIfsc.isBlank() ? "" : " | IFSC: " + ourIfsc)
                .append(ourHolder.isBlank() ? "" : " (" + ourHolder + ")").append("\n");
        dualBank.append("• Remitter Settlement: ").append(narration == null || narration.isBlank() ? customerName : narration).append("\n");
        dualBank.append("• Bank Reference / UTR: ").append(refNo == null || refNo.isBlank() ? "Reconciled via Bank Statement" : refNo).append("\n");
        dualBank.append("• Settlement Date: ").append(paymentDate == null ? BusinessClock.formatDate(LocalDate.now()) : paymentDate).append("\n");

        tokens.put("BankDetails", dualBank.toString());

        String subject = format(getEmailSubject(DocumentType.PAYMENT_RECEIPT), tokens);
        String rawBody = getEmailBody(DocumentType.PAYMENT_RECEIPT);
        if (!rawBody.contains("{BankDetails}")) {
            rawBody = rawBody.replace("{Signature}", dualBank.toString() + "\n{Signature}");
        }
        String body = format(rawBody, tokens);
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatPurchaseRemittanceAdviceEmail(String supplierName, String docNo, LocalDate date,
                                                                      double totalAmount, double paidAmount, double balanceAmount,
                                                                      String refNo, String paymentDate, String paymentMode, String narration) {
        Map<String, String> tokens = buildTokens(supplierName, docNo, date, totalAmount, paidAmount, balanceAmount, DocumentType.PURCHASE_BILL);
        tokens.put("PaymentReference", refNo == null ? "" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? "" : paymentDate);
        tokens.put("PaymentMode", paymentMode == null ? "" : paymentMode);
        tokens.put("Narration", narration == null ? "" : narration);

        String companyName = BrandingService.companyName();
        String subject = "Payment Remittance Advice: Bill #" + docNo + " - " + companyName;

        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(supplierName == null ? "Valued Supplier" : supplierName).append(",\n\n");
        sb.append("We have disbursed a payment settlement against Purchase Bill #").append(docNo).append(".\n\n");
        sb.append("Remittance Breakdown:\n");
        sb.append("• Bill Total: ₹ ").append(String.format(Locale.of("en", "IN"), "%,.2f", totalAmount)).append("\n");
        sb.append("• Amount Disbursed: ₹ ").append(String.format(Locale.of("en", "IN"), "%,.2f", paidAmount)).append("\n");
        sb.append("• Balance Remaining: ₹ ").append(String.format(Locale.of("en", "IN"), "%,.2f", balanceAmount)).append("\n");
        sb.append("• Payment Mode: ").append(paymentMode == null ? "Bank Transfer" : paymentMode).append("\n");
        sb.append("• Reference / UTR: ").append(refNo == null || refNo.isBlank() ? "Direct Settlement" : refNo).append("\n");
        sb.append("• Settlement Date: ").append(paymentDate == null ? BusinessClock.formatDate(LocalDate.now()) : paymentDate).append("\n");
        if (narration != null && !narration.isBlank()) {
            sb.append("• Remarks / Narration: ").append(narration).append("\n");
        }
        sb.append("\n{Signature}");

        String body = format(sb.toString(), tokens);
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatReturnRefundEmail(String partyName, String returnNo, String originalDocNo,
                                                          String returnType, double refundAmount, double balanceAmount,
                                                          String refNo, String paymentDate, String paymentMode, String narration) {
        String companyName = BrandingService.companyName();
        String subject = "Refund Settlement: " + returnNo + " - " + companyName;

        StringBuilder sb = new StringBuilder();
        sb.append("Dear ").append(partyName == null ? "Valued Customer / Partner" : partyName).append(",\n\n");
        sb.append("This is an official confirmation that a refund settlement has been issued for ").append(returnType == null ? "Return" : returnType).append(" #").append(returnNo);
        if (originalDocNo != null && !originalDocNo.isBlank()) {
            sb.append(" (Original Document: ").append(originalDocNo).append(")");
        }
        sb.append(".\n\n");
        sb.append("Settlement Breakdown:\n");
        sb.append("• Refund Amount: ₹ ").append(String.format(Locale.of("en", "IN"), "%,.2f", refundAmount)).append("\n");
        sb.append("• Remaining Balance: ₹ ").append(String.format(Locale.of("en", "IN"), "%,.2f", balanceAmount)).append("\n");
        sb.append("• Refund Mode: ").append(paymentMode == null ? "Bank Transfer" : paymentMode).append("\n");
        sb.append("• Reference / UTR: ").append(refNo == null || refNo.isBlank() ? "-" : refNo).append("\n");
        sb.append("• Settlement Date: ").append(paymentDate == null ? BusinessClock.formatDate(LocalDate.now()) : paymentDate).append("\n");
        if (narration != null && !narration.isBlank()) {
            sb.append("• Remarks / Notes: ").append(narration).append("\n");
        }
        sb.append("\n{Signature}");

        Map<String, String> tokens = buildTokens(partyName, returnNo, LocalDate.now(), refundAmount + balanceAmount, refundAmount, balanceAmount, DocumentType.SALES_RETURN);
        String body = format(sb.toString(), tokens);
        return new FormattedMessage(subject, body);
    }

    public static String buildPublicPaymentStatusUrl(String invoiceNo, double totalAmount) {
        if (invoiceNo == null || invoiceNo.isBlank()) return "";
        try {
            String token = org.example.shared.PaymentStatusTokenService.generateToken(invoiceNo, totalAmount);
            String base = ConfigManager.getDataApiBaseUrl().replaceAll("/+$", "");
            if (base.isBlank() || base.contains("localhost") || base.contains("127.0.0.1")) {
                base = "https://erp.jasviindustries.in";
            }
            return base + "/public/payment-status?inv=" + java.net.URLEncoder.encode(invoiceNo.trim(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&token=" + token;
        } catch (Exception e) {
            return "";
        }
    }

    public static String formatExecutiveHtmlEmail(
            String docTitle,
            String statusBadge,
            String documentNo,
            String date,
            String partyName,
            double totalAmount,
            double paidAmount,
            double balanceAmount,
            String plainBody,
            boolean hasDualBank,
            String ourBankName,
            String ourAccNo,
            String ourIfsc,
            String remitterDesc,
            String remitterUtr,
            String publicStatusUrl,
            boolean includeInlineSignatureImage
    ) {
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();

        String badgeBg = "#1e3a8a";
        String badgeColor = "#ffffff";
        if ("PAID".equalsIgnoreCase(statusBadge) || "FULLY PAID".equalsIgnoreCase(statusBadge)) {
            badgeBg = "#16a34a";
        } else if ("PARTIAL".equalsIgnoreCase(statusBadge) || "PARTIALLY PAID".equalsIgnoreCase(statusBadge)) {
            badgeBg = "#d97706";
        }

        String safeBody = (plainBody == null ? "" : plainBody)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r\n", "\n")
                .replace("\n", "<br/>\n");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body style=\"margin:0; padding:16px; font-family:-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color:#f1f5f9; color:#1e293b;\">");
        html.append("<div style=\"max-width:660px; margin:0 auto; background:#ffffff; border-radius:10px; border:1px solid #e2e8f0; overflow:hidden; box-shadow:0 4px 14px rgba(15,23,42,0.06);\">");

        // Header Banner
        html.append("<div style=\"background:#1e3a8a; padding:22px 24px; color:#ffffff;\">");
        html.append("<div style=\"display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;\">");
        html.append("<div>");
        html.append("<div style=\"font-size:20px; font-weight:700; letter-spacing:0.5px;\">").append(escape(company)).append("</div>");
        html.append("<div style=\"font-size:11px; opacity:0.85; margin-top:3px;\">INDUSTRIAL MACHINERY &amp; ENGINEERING SOLUTIONS &bull; AHMEDABAD</div>");
        html.append("</div>");
        html.append("<div>");
        html.append("<span style=\"background:").append(badgeBg).append("; color:").append(badgeColor).append("; font-size:11px; font-weight:700; padding:5px 12px; border-radius:9999px; text-transform:uppercase; letter-spacing:0.5px;\">")
                .append(escape(statusBadge == null ? "OFFICIAL" : statusBadge)).append("</span>");
        html.append("</div>");
        html.append("</div>");
        html.append("</div>");

        // Document Meta Bar
        html.append("<div style=\"background:#f8fafc; border-bottom:1px solid #e2e8f0; padding:14px 24px; display:flex; justify-content:space-between; flex-wrap:wrap; gap:12px; font-size:13px;\">");
        html.append("<div><span style=\"color:#64748b; font-size:11px; font-weight:600; text-transform:uppercase;\">DOCUMENT</span><br/><strong>").append(escape(documentNo)).append("</strong></div>");
        html.append("<div><span style=\"color:#64748b; font-size:11px; font-weight:600; text-transform:uppercase;\">DATE</span><br/><strong>").append(escape(date)).append("</strong></div>");
        html.append("<div><span style=\"color:#64748b; font-size:11px; font-weight:600; text-transform:uppercase;\">BILLED TO</span><br/><strong>").append(escape(partyName)).append("</strong></div>");
        html.append("</div>");

        // Body Content
        html.append("<div style=\"padding:24px; line-height:1.6; font-size:14px; color:#334155;\">");
        html.append("<div>").append(safeBody).append("</div>");

        // Dual Bank Details Card (if payment receipt)
        if (hasDualBank) {
            html.append("<div style=\"margin-top:20px; background:#f8fafc; border:1px solid #cbd5e1; border-radius:8px; padding:16px;\">");
            html.append("<div style=\"font-size:13px; font-weight:700; color:#0f172a; margin-bottom:10px;\">Bank Settlement &amp; Reconciliation Record</div>");
            html.append("<table style=\"width:100%; border-collapse:collapse; font-size:12px; line-height:1.6;\">");
            html.append("<tr>");
            html.append("<td style=\"width:50%; vertical-align:top; padding-right:12px;\">");
            html.append("<div style=\"font-weight:700; color:#1e3a8a; margin-bottom:4px;\">Beneficiary Bank (Jasvi Industries)</div>");
            html.append("<div>Bank: <strong>").append(escape(ourBankName)).append("</strong></div>");
            html.append("<div>A/C No: <strong>").append(escape(ourAccNo)).append("</strong></div>");
            html.append("<div>IFSC: <strong>").append(escape(ourIfsc)).append("</strong></div>");
            html.append("</td>");
            html.append("<td style=\"width:50%; vertical-align:top; padding-left:12px; border-left:1px solid #e2e8f0;\">");
            html.append("<div style=\"font-weight:700; color:#16a34a; margin-bottom:4px;\">Remitter / Bank Statement Match</div>");
            html.append("<div>Payer: <strong>").append(escape(remitterDesc)).append("</strong></div>");
            html.append("<div>Reference / UTR: <strong>").append(escape(remitterUtr)).append("</strong></div>");
            html.append("<div>Channel: <strong>Bank Statement Reconciliation</strong></div>");
            html.append("</td>");
            html.append("</tr>");
            html.append("</table>");
            html.append("</div>");
        }

        // Live Status CTA Button
        if (publicStatusUrl != null && !publicStatusUrl.isBlank()) {
            html.append("<div style=\"margin:24px 0 16px 0; text-align:center;\">");
            html.append("<a href=\"").append(publicStatusUrl).append("\" target=\"_blank\" style=\"display:inline-block; background:#1e3a8a; color:#ffffff; font-weight:700; font-size:13px; text-decoration:none; padding:12px 24px; border-radius:6px; letter-spacing:0.3px; box-shadow:0 2px 6px rgba(30,58,138,0.25);\">🔍 VIEW LIVE PAYMENT &amp; INVOICE STATUS</a>");
            html.append("<div style=\"font-size:11px; color:#64748b; margin-top:6px;\">Click anytime to view real-time settlement and payment reconciliation in our portal</div>");
            html.append("</div>");
        }

        // Inline Signature Image
        if (includeInlineSignatureImage) {
            html.append("<div style=\"margin-top:20px; padding-top:14px; border-top:1px solid #f1f5f9;\">");
            html.append("<img src=\"cid:signatureImage\" alt=\"Signature / Stamp\" style=\"max-height:85px; max-width:240px; object-fit:contain; display:block; border:none;\" />");
            html.append("</div>");
        }

        html.append("</div>"); // Content end

        // Footer
        html.append("<div style=\"background:#f8fafc; border-top:1px solid #e2e8f0; padding:14px 24px; font-size:11px; color:#64748b; text-align:center;\">");
        html.append("Generated automatically by ").append(escape(company)).append(" ERP &bull; Phone: ").append(escape(phone)).append(" &bull; Email: ").append(escape(email));
        html.append("</div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static Map<String, String> sampleTokens() {
        return buildTokens("Acme Enterprises", "INV-2026-0042", LocalDate.now(), 24500.00, 10000.00, 14500.00);
    }

    private static String money(double amount) {
        return "\u20B9" + String.format(Locale.of("en", "IN"), "%,.2f", amount);
    }
}
