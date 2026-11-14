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
        PURCHASE_REMITTANCE("purchase_remittance", "Supplier Remittance Advice"),
        REFUND_CONFIRMATION("refund_confirmation", "Refund Settlement Confirmation"),
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

    public static java.nio.file.Path getOrCreateSignatureImagePath() {
        String existing = getSignatureImagePath();
        if (existing != null && !existing.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(existing);
            if (java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.isReadable(p)) {
                return p;
            }
        }
        if (!isAttachSignatureToEmail()) {
            return null;
        }
        try {
            java.nio.file.Path folder = ConfigManager.getConfigurationFolder();
            java.nio.file.Path stampPath = folder.resolve("corporate-stamp-generated.png");
            if (java.nio.file.Files.isRegularFile(stampPath) && java.nio.file.Files.size(stampPath) > 500) {
                return stampPath;
            }
            int w = 360, h = 160;
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = bi.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Corporate Seal Frame
            g2.setColor(new java.awt.Color(30, 58, 138));
            g2.setStroke(new java.awt.BasicStroke(2.0f));
            g2.drawRoundRect(8, 8, w - 16, h - 16, 12, 12);
            g2.setStroke(new java.awt.BasicStroke(0.8f));
            g2.drawRoundRect(12, 12, w - 24, h - 24, 8, 8);

            // Header
            g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
            String compName = "FOR, " + BrandingService.companyName().toUpperCase(java.util.Locale.ROOT);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(compName, (w - fm.stringWidth(compName)) / 2, 38);

            // Signature ink curve
            g2.setColor(new java.awt.Color(37, 99, 235));
            g2.setStroke(new java.awt.BasicStroke(2.2f));
            java.awt.geom.Path2D.Double sigPath = new java.awt.geom.Path2D.Double();
            sigPath.moveTo(60, 95);
            sigPath.curveTo(90, 60, 100, 110, 130, 75);
            sigPath.curveTo(150, 60, 170, 115, 200, 85);
            sigPath.curveTo(220, 70, 240, 105, 270, 80);
            sigPath.curveTo(285, 95, 295, 90, 305, 80);
            g2.draw(sigPath);

            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.drawLine(80, 112, 280, 105);

            // Footer
            g2.setColor(new java.awt.Color(30, 58, 138));
            g2.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
            fm = g2.getFontMetrics();
            String footer = "AUTHORIZED SIGNATORY";
            g2.drawString(footer, (w - fm.stringWidth(footer)) / 2, 142);

            g2.dispose();
            javax.imageio.ImageIO.write(bi, "png", stampPath.toFile());
            return stampPath;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getSignatureImageBase64() {
        java.nio.file.Path p = getOrCreateSignatureImagePath();
        if (p == null || !java.nio.file.Files.isRegularFile(p)) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return "";
        }
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
            case PURCHASE_REMITTANCE -> "Payment Remittance Advice: Bill #{DocumentNo} - {CompanyName}";
            case REFUND_CONFIRMATION -> "Refund Settlement: {DocumentNo} - {CompanyName}";
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
            case PURCHASE_REMITTANCE -> """
                Dear {CustomerName},

                We have disbursed a payment settlement against Purchase Bill #{DocumentNo}.

                Remittance Breakdown:
                • Bill Total: {TotalAmount}
                • Amount Disbursed: {PaidAmount}
                • Balance Remaining: {BalanceAmount}
                • Payment Mode: {PaymentMode}
                • Reference / UTR: {PaymentReference}
                • Settlement Date: {PaymentDate}
                • Remarks / Narration: {Narration}

                {Signature}""";
            case REFUND_CONFIRMATION -> """
                Dear {CustomerName},

                This is an official confirmation that a refund settlement has been issued for document #{DocumentNo}.

                Settlement Breakdown:
                • Refund Amount: {PaidAmount}
                • Remaining Balance: {BalanceAmount}
                • Refund Mode: {PaymentMode}
                • Reference / UTR: {PaymentReference}
                • Settlement Date: {PaymentDate}
                • Remarks / Notes: {Narration}

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
            case PURCHASE_REMITTANCE -> "Hello *{CustomerName}*,\n\nWe have disbursed payment of *{PaidAmount}* against Purchase Bill *{DocumentNo}* (Balance: *{BalanceAmount}*).\n\nRegards,\n*{CompanyName}*";
            case REFUND_CONFIRMATION -> "Hello *{CustomerName}*,\n\nRefund settlement of *{PaidAmount}* has been issued for document *{DocumentNo}*.\n\nRegards,\n*{CompanyName}*";
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

    public static String formatPaymentReceiptWhatsapp(String customerName, String docNo, LocalDate date,
                                                      double totalAmount, double paidAmount, double balanceAmount,
                                                      String refNo, String paymentDate, String narration) {
        String configured = ConfigManager.get("whatsapp.template.payment.body", "").trim();
        if (configured.isBlank()) {
            configured = defaultWhatsappBody(DocumentType.PAYMENT_RECEIPT);
        }
        Map<String, String> tokens = buildTokens(customerName, docNo, date, totalAmount, paidAmount, balanceAmount, DocumentType.PAYMENT_RECEIPT);
        tokens.put("PaymentReference", refNo == null ? "" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? "" : paymentDate);
        tokens.put("Narration", narration == null ? "" : narration);
        return format(configured, tokens);
    }

    public static String formatPurchaseRemittanceWhatsapp(String supplierName, String docNo, LocalDate date,
                                                         double totalAmount, double paidAmount, double balanceAmount,
                                                         String refNo, String paymentDate, String narration) {
        String configured = ConfigManager.get("whatsapp.template.purchase_remittance.body", "").trim();
        if (configured.isBlank()) {
            configured = defaultWhatsappBody(DocumentType.PURCHASE_REMITTANCE);
        }
        Map<String, String> tokens = buildTokens(supplierName, docNo, date, totalAmount, paidAmount, balanceAmount, DocumentType.PURCHASE_REMITTANCE);
        tokens.put("PaymentReference", refNo == null ? "" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? "" : paymentDate);
        tokens.put("Narration", narration == null ? "" : narration);
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
        Map<String, String> tokens = buildTokens(supplierName, docNo, date, totalAmount, paidAmount, balanceAmount, DocumentType.PURCHASE_REMITTANCE);
        tokens.put("PaymentReference", refNo == null || refNo.isBlank() ? "Direct Settlement" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? BusinessClock.formatDate(LocalDate.now()) : paymentDate);
        tokens.put("PaymentMode", paymentMode == null ? "Bank Transfer" : paymentMode);
        tokens.put("Narration", narration == null ? "" : narration);

        String subject = format(getEmailSubject(DocumentType.PURCHASE_REMITTANCE), tokens);
        String rawBody = getEmailBody(DocumentType.PURCHASE_REMITTANCE);
        String body = format(rawBody, tokens);
        return new FormattedMessage(subject, body);
    }

    public static FormattedMessage formatReturnRefundEmail(String partyName, String returnNo, String originalDocNo,
                                                          String returnType, double refundAmount, double balanceAmount,
                                                          String refNo, String paymentDate, String paymentMode, String narration) {
        Map<String, String> tokens = buildTokens(partyName, returnNo, LocalDate.now(), refundAmount + balanceAmount, refundAmount, balanceAmount, DocumentType.REFUND_CONFIRMATION);
        tokens.put("PaymentReference", refNo == null || refNo.isBlank() ? "-" : refNo);
        tokens.put("PaymentDate", paymentDate == null ? BusinessClock.formatDate(LocalDate.now()) : paymentDate);
        tokens.put("PaymentMode", paymentMode == null ? "Bank Transfer" : paymentMode);
        tokens.put("Narration", narration == null ? "" : narration);
        tokens.put("OriginalDocument", originalDocNo == null ? "" : originalDocNo);
        tokens.put("ReturnType", returnType == null ? "Return" : returnType);

        String subject = format(getEmailSubject(DocumentType.REFUND_CONFIRMATION), tokens);
        String rawBody = getEmailBody(DocumentType.REFUND_CONFIRMATION);
        String body = format(rawBody, tokens);
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
            return base + "/api/public/payment-status?inv=" + java.net.URLEncoder.encode(invoiceNo.trim(), java.nio.charset.StandardCharsets.UTF_8)
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
        return formatExecutiveHtmlEmail(
                docTitle, statusBadge, documentNo, date, partyName,
                totalAmount, paidAmount, balanceAmount, plainBody,
                hasDualBank, ourBankName, ourAccNo, ourIfsc,
                remitterDesc, remitterUtr, publicStatusUrl,
                includeInlineSignatureImage, null
        );
    }

    public static String cleanExecutiveDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return "-";
        String s = dateStr.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})").matcher(s);
        if (m.find()) {
            try {
                LocalDate d = LocalDate.parse(m.group(1));
                return BusinessClock.formatDate(d);
            } catch (Exception ignored) {}
        }
        s = s.replaceAll("\\s*\\+[0-9]{2}:?[0-9]{2}$", "").trim();
        return s;
    }

    public static String formatExecutiveBreakdownHtml(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";
        String[] lines = rawBody.split("\\r?\\n");
        StringBuilder topText = new StringBuilder();
        StringBuilder detailRows = new StringBuilder();
        StringBuilder bottomText = new StringBuilder();
        boolean inBreakdown = false;

        String totalBillVal = null;
        String disbursedVal = null;
        String balanceVal = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                inBreakdown = true;
                String content = trimmed.substring(2).trim();
                int colon = content.indexOf(':');
                if (colon > 0) {
                    String key = content.substring(0, colon).trim();
                    String val = content.substring(colon + 1).trim();
                    String keyLower = key.toLowerCase(Locale.ROOT);

                    if (keyLower.contains("total")) {
                        totalBillVal = val;
                    } else if (keyLower.contains("disbursed") || keyLower.contains("paid") || keyLower.contains("disburse")) {
                        disbursedVal = val;
                    } else if (keyLower.contains("balance")) {
                        balanceVal = val;
                    } else {
                        String icon = "📋";
                        String valStyle = "color:#0f172a; font-weight:600;";
                        String renderedVal = escape(val);

                        if (keyLower.contains("mode")) {
                            icon = "💳";
                            valStyle = "color:#334155; font-weight:600;";
                        } else if (keyLower.contains("reference") || keyLower.contains("utr")) {
                            icon = "🏷";
                            renderedVal = "<span style=\"font-family:monospace; font-weight:700; background:#f1f5f9; border:1px solid #cbd5e1; padding:2px 8px; border-radius:4px; font-size:12px; color:#334155;\">" + escape(val) + "</span>";
                        } else if (keyLower.contains("date")) {
                            icon = "📅";
                            renderedVal = escape(cleanExecutiveDate(val));
                            valStyle = "color:#334155; font-weight:600;";
                        } else if (keyLower.contains("remarks") || keyLower.contains("narration") || keyLower.contains("notes")) {
                            icon = "📝";
                            valStyle = "color:#475569; font-style:italic;";
                        }

                        detailRows.append("<tr>")
                                .append("<td style=\"padding:10px 16px; border-bottom:1px solid #f1f5f9; color:#475569; width:38%;\">")
                                .append(icon).append(" <strong>").append(escape(key)).append("</strong></td>")
                                .append("<td style=\"padding:10px 16px; border-bottom:1px solid #f1f5f9; ").append(valStyle).append("\">")
                                .append(renderedVal)
                                .append("</td></tr>\n");
                    }
                } else {
                    detailRows.append("<tr><td colspan=\"2\" style=\"padding:8px 16px; border-bottom:1px solid #f1f5f9; color:#475569;\">")
                            .append(escape(content))
                            .append("</td></tr>\n");
                }
            } else if (inBreakdown && !trimmed.isBlank()) {
                if (!trimmed.equalsIgnoreCase("{Signature}")) {
                    bottomText.append("<p style=\"margin:8px 0;\">").append(escape(trimmed)).append("</p>\n");
                }
            } else if (!inBreakdown) {
                if (trimmed.toLowerCase(Locale.ROOT).contains("breakdown:") || trimmed.toLowerCase(Locale.ROOT).contains("summary:")) {
                    // Skip header line as table owns the header
                } else if (!trimmed.isBlank() && !trimmed.equalsIgnoreCase("{Signature}")) {
                    topText.append("<p style=\"margin:8px 0;\">").append(escape(trimmed)).append("</p>\n");
                }
            }
        }

        if (totalBillVal == null && disbursedVal == null && balanceVal == null && detailRows.length() == 0) {
            return escape(rawBody).replace("\r\n", "\n").replace("\n", "<br/>\n");
        }

        StringBuilder out = new StringBuilder();
        if (topText.length() > 0) out.append(topText);

        out.append("<div style=\"margin:18px 0; background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; overflow:hidden;\">\n");
        out.append("<div style=\"background:#f1f5f9; padding:10px 16px; border-bottom:1px solid #e2e8f0; font-size:12px; font-weight:700; color:#1e3a8a; text-transform:uppercase; letter-spacing:0.5px;\">📊 Remittance &amp; Settlement Breakdown</div>\n");

        if (totalBillVal != null || disbursedVal != null || balanceVal != null) {
            String bVal = balanceVal == null ? "₹ 0.00" : balanceVal;
            boolean zeroBal = bVal.contains("0.00") || bVal.equals("₹ 0") || bVal.equals("0");
            String balanceColor = zeroBal ? "#16a34a" : "#dc2626";

            out.append("<table style=\"width:100%; border-collapse:collapse; background:#ffffff; border-bottom:1px solid #e2e8f0;\">\n");
            out.append("<tr>\n");
            out.append("<td style=\"width:33.33%; padding:14px 16px; border-right:1px solid #e2e8f0; vertical-align:top;\">\n");
            out.append("<div style=\"font-size:11px; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.5px;\">📄 Total Bill Amount</div>\n");
            out.append("<div style=\"font-size:15px; font-weight:700; color:#1e3a8a; margin-top:4px;\">").append(escape(totalBillVal == null ? "-" : totalBillVal)).append("</div>\n");
            out.append("</td>\n");

            out.append("<td style=\"width:33.33%; padding:14px 16px; border-right:1px solid #e2e8f0; vertical-align:top;\">\n");
            out.append("<div style=\"font-size:11px; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.5px;\">💸 Amount Disbursed</div>\n");
            out.append("<div style=\"font-size:15px; font-weight:700; color:#16a34a; margin-top:4px;\">").append(escape(disbursedVal == null ? "-" : disbursedVal))
                    .append(" <span style=\"background:#f0fdf4; color:#16a34a; font-size:10px; font-weight:700; padding:2px 8px; border-radius:9999px; text-transform:uppercase; border:1px solid #bbf7d0;\">SETTLED</span></div>\n");
            out.append("</td>\n");

            out.append("<td style=\"width:33.33%; padding:14px 16px; vertical-align:top;\">\n");
            out.append("<div style=\"font-size:11px; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.5px;\">⚖️ Balance Due</div>\n");
            out.append("<div style=\"font-size:15px; font-weight:700; color:").append(balanceColor).append("; margin-top:4px;\">").append(escape(bVal)).append("</div>\n");
            out.append("</td>\n");
            out.append("</tr>\n</table>\n");
        }

        if (detailRows.length() > 0) {
            out.append("<table style=\"width:100%; border-collapse:collapse; font-size:13px; line-height:1.5;\">\n");
            out.append(detailRows);
            out.append("</table>\n");
        }

        out.append("</div>\n");
        if (bottomText.length() > 0) out.append(bottomText);
        return out.toString();
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
            boolean includeInlineSignatureImage,
            String signatureImageSrc
    ) {
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        date = cleanExecutiveDate(date);

        String badgeBg = "#1e3a8a";
        String badgeColor = "#ffffff";
        if ("PAID".equalsIgnoreCase(statusBadge) || "FULLY PAID".equalsIgnoreCase(statusBadge)) {
            badgeBg = "#16a34a";
        } else if ("PARTIAL".equalsIgnoreCase(statusBadge) || "PARTIALLY PAID".equalsIgnoreCase(statusBadge)) {
            badgeBg = "#d97706";
        }

        String cleanedBody = plainBody == null ? "" : plainBody;
        if (includeInlineSignatureImage) {
            cleanedBody = cleanedBody.replace("{Signature}", "").stripTrailing();
        }

        String safeBody = formatExecutiveBreakdownHtml(cleanedBody);

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
            String src = (signatureImageSrc != null && !signatureImageSrc.isBlank()) ? signatureImageSrc : "cid:signatureImage";
            html.append("<div style=\"margin-top:20px; padding-top:14px; border-top:1px solid #f1f5f9;\">");
            html.append("<img src=\"").append(src).append("\" alt=\"Signature / Stamp\" style=\"max-height:85px; max-width:240px; object-fit:contain; display:block; border:none;\" />");
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
