package org.example.service;

import org.example.config.ConfigManager;
import org.example.model.Purchase;
import org.example.model.Sales;
import org.example.shared.communication.NotificationTemplateCatalog;
import org.example.shared.communication.NotificationTemplateCatalog.EmailFormatMode;
import org.example.shared.communication.NotificationTemplateCatalog.TemplateSpec;
import org.example.shared.communication.TokenTemplateEngine;
import org.example.shared.communication.TokenTemplateEngine.ActionButton;
import org.example.shared.communication.TokenTemplateEngine.KeyValueRow;
import org.example.shared.communication.TokenTemplateEngine.KpiTile;
import org.example.shared.communication.TokenTemplateEngine.MetaColumn;
import org.example.shared.communication.TokenTemplateEngine.RichCardSpec;
import org.example.shared.communication.TokenTemplateEngine.TableColumn;
import org.example.util.BusinessClock;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manages user-configurable email and WhatsApp message templates, signatures,
 * Email format modes (HTML Card View vs Standard Plain Text), and dynamic token
 * substitutions for all business and administrative notifications.
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

    public record LineItemPreview(
            String description,
            String hsnSac,
            String quantityWithUnit,
            String rateFormatted,
            String gstFormatted,
            String totalFormatted
    ) {}

    public record RichEmailContext(
            String partyName,
            String documentDate,
            String dueDate,
            String partyGstin,
            String paymentMode,
            String paymentReference,
            String originalDocumentNo,
            Double subtotalAmount,
            Double taxAmount,
            String taxBreakup,
            List<LineItemPreview> lineItems
    ) {
        public static RichEmailContext empty() {
            return new RichEmailContext(null, null, null, null, null, null, null, null, null, null, List.of());
        }

        private static String fmtQty(double q, String unit) {
            String qtyStr = (Math.abs(q - Math.rint(q)) < 0.0001)
                    ? String.valueOf(Math.round(q))
                    : String.format(Locale.ROOT, "%.2f", q);
            return (unit == null || unit.isBlank()) ? qtyStr : qtyStr + " " + unit.trim();
        }

        public static RichEmailContext forSale(Sales sale) {
            if (sale == null) return empty();
            List<LineItemPreview> items = new java.util.ArrayList<>();
            if (sale.getLines() != null) {
                for (var l : sale.getLines()) {
                    if (l == null) continue;
                    String desc = (l.getItemDescription() != null && !l.getItemDescription().isBlank())
                            ? l.getItemDescription().trim()
                            : (l.getItemCode() == null ? "-" : l.getItemCode().trim());
                    items.add(new LineItemPreview(
                            desc,
                            l.getItemHsn() == null || l.getItemHsn().isBlank() ? "-" : l.getItemHsn().trim(),
                            fmtQty(l.getQuantity(), l.getItemUnit()),
                            money(l.getRate()),
                            String.format(Locale.ROOT, "%.1f%% (%s)", l.getGstPercent(), money(l.getGstAmount())),
                            money(l.getTotalAmount())
                    ));
                }
            }
            return new RichEmailContext(
                    sale.getCustomer() != null ? sale.getCustomer().getName() : null,
                    sale.getInvoiceDate() != null ? BusinessClock.formatDate(sale.getInvoiceDate()) : null,
                    sale.getDueDate() != null ? BusinessClock.formatDate(sale.getDueDate()) : null,
                    firstNonBlank(sale.getBillingGstin(), sale.getGstin(), sale.getCustomer() != null ? sale.getCustomer().getGstin() : ""),
                    null,
                    null,
                    sale.getOrderNo(),
                    sale.getSubtotal(),
                    sale.getGstAmount(),
                    sale.getGstType(),
                    items
            );
        }

        public static RichEmailContext forPurchase(Purchase purchase) {
            if (purchase == null) return empty();
            List<LineItemPreview> items = new java.util.ArrayList<>();
            if (purchase.getLines() != null) {
                for (var l : purchase.getLines()) {
                    if (l == null) continue;
                    String desc = (l.getItemDescription() != null && !l.getItemDescription().isBlank())
                            ? l.getItemDescription().trim()
                            : (l.getItemCode() == null ? "-" : l.getItemCode().trim());
                    items.add(new LineItemPreview(
                            desc,
                            l.getItemHsn() == null || l.getItemHsn().isBlank() ? "-" : l.getItemHsn().trim(),
                            fmtQty(l.getQuantity(), l.getItemUnit()),
                            money(l.getRate()),
                            String.format(Locale.ROOT, "%.1f%% (%s)", l.getGstPercent(), money(l.getGstAmount())),
                            money(l.getTotalAmount())
                    ));
                }
            }
            return new RichEmailContext(
                    purchase.getSupplier() != null ? purchase.getSupplier().getName() : null,
                    purchase.getInvoiceDate() != null ? BusinessClock.formatDate(purchase.getInvoiceDate()) : null,
                    purchase.getDueDate() != null ? BusinessClock.formatDate(purchase.getDueDate()) : null,
                    firstNonBlank(purchase.getBillingGstin(), purchase.getSupplier() != null ? purchase.getSupplier().getGstin() : ""),
                    null,
                    null,
                    purchase.getOrderNo(),
                    purchase.getSubtotal(),
                    purchase.getGstAmount(),
                    purchase.getGstType(),
                    items
            );
        }

        public static RichEmailContext forQuotation(
                org.example.api.quotation.QuotationApiClient.QuoteDto quote,
                List<org.example.api.quotation.QuotationApiClient.LineDto> lines) {
            if (quote == null) return empty();
            List<LineItemPreview> items = new java.util.ArrayList<>();
            double subtotalSum = 0.0;
            double taxSum = 0.0;
            if (lines != null) {
                for (var l : lines) {
                    if (l == null) continue;
                    var calc = org.example.shared.DocumentCalculationEngine.line(l.quantity(), l.rate(), l.discount(), l.gst());
                    subtotalSum += calc.taxableAmount();
                    taxSum += calc.taxAmount();
                    String desc = (l.description() != null && !l.description().isBlank())
                            ? l.description().trim()
                            : (l.code() == null ? "-" : l.code().trim());
                    items.add(new LineItemPreview(
                            desc,
                            l.hsn() == null || l.hsn().isBlank() ? "-" : l.hsn().trim(),
                            fmtQty(l.quantity(), l.unit()),
                            money(l.rate()),
                            String.format(Locale.ROOT, "%.1f%% (%s)", l.gst(), money(calc.taxAmount())),
                            money(l.total() > 0 ? l.total() : calc.totalAmount())
                    ));
                }
            }
            return new RichEmailContext(
                    quote.customer(),
                    quote.date(),
                    quote.valid(),
                    quote.gstin(),
                    null,
                    null,
                    null,
                    items.isEmpty() ? null : subtotalSum,
                    items.isEmpty() ? null : taxSum,
                    items.isEmpty() ? null : "GST Included",
                    items
            );
        }

        public static RichEmailContext forSettlement(
                String partyName,
                String docDate,
                String partyGstin,
                String paymentMode,
                String paymentReference,
                String originalDocumentNo,
                Double subtotalAmount,
                Double taxAmount,
                String taxBreakup,
                List<LineItemPreview> lineItems) {
            return new RichEmailContext(
                    partyName, docDate, null, partyGstin,
                    paymentMode, paymentReference, originalDocumentNo,
                    subtotalAmount, taxAmount, taxBreakup, lineItems
            );
        }
    }

    private MessageTemplateService() {}

    /**
     * Replaces dynamic tokens such as {CustomerName}, {DocumentNo}, {TotalAmount}, etc.
     */
    public static String format(String template, Map<String, String> tokens) {
        return TokenTemplateEngine.format(template, tokens);
    }

    public static EmailFormatMode emailFormatMode() {
        String raw = ConfigManager.get(NotificationTemplateCatalog.EMAIL_FORMAT_MODE_KEY, EmailFormatMode.HTML.name());
        EmailFormatMode parsed = EmailFormatMode.parse(raw, EmailFormatMode.HTML);
        return parsed == EmailFormatMode.INHERIT ? EmailFormatMode.HTML : parsed;
    }

    public static EmailFormatMode emailFormatModeFor(String templateKey) {
        if (templateKey != null && !templateKey.isBlank()) {
            var specOpt = NotificationTemplateCatalog.byKey(templateKey);
            if (specOpt.isPresent()) {
                String rawOverride = ConfigManager.get(specOpt.get().emailFormatSettingKey(), EmailFormatMode.INHERIT.name());
                EmailFormatMode override = EmailFormatMode.parse(rawOverride, EmailFormatMode.INHERIT);
                if (override != EmailFormatMode.INHERIT) {
                    return override;
                }
            }
        }
        return emailFormatMode();
    }

    public static boolean shouldUseHtml(String templateKey) {
        return emailFormatModeFor(templateKey) != EmailFormatMode.STANDARD;
    }

    public static boolean shouldUseHtmlForEntity(String entityTypeOrLabel) {
        return shouldUseHtml(resolveTemplateKeyForEntity(entityTypeOrLabel));
    }

    public static String resolveTemplateKeyForEntity(String entityTypeOrLabel) {
        if (entityTypeOrLabel == null || entityTypeOrLabel.isBlank()) return "sale";
        String norm = entityTypeOrLabel.trim().toLowerCase(Locale.ROOT);
        if (NotificationTemplateCatalog.byKey(norm).isPresent()) return norm;
        if (norm.contains("remittance")) return "purchase_remittance";
        if (norm.contains("refund")) return "refund_confirmation";
        if (norm.contains("payment") || norm.contains("receipt")) return "payment";
        if (norm.contains("quot")) return "quote";
        if (norm.contains("purchase") && norm.contains("return")) return "purchase_return";
        if (norm.contains("sales") && norm.contains("return")) return "sales_return";
        if (norm.contains("purchase")) return "purchase";
        if (norm.contains("aging") || norm.contains("collection")) return "aging_collection";
        if (norm.contains("gst") || norm.contains("rule36")) return "gst_rule36_notice";
        return "sale";
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

    public static String getEmailSubject(String templateKey) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey).orElse(null);
        String fallback = spec != null ? spec.defaultEmailSubject() : "";
        return ConfigManager.get(NotificationTemplateCatalog.EMAIL_TEMPLATE_PREFIX + templateKey + ".subject", fallback);
    }

    public static String getEmailBody(String templateKey) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey).orElse(null);
        String fallback = spec != null ? spec.defaultEmailBody() : "";
        return ConfigManager.get(NotificationTemplateCatalog.EMAIL_TEMPLATE_PREFIX + templateKey + ".body", fallback);
    }

    public static String getWhatsappBody(String templateKey) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey).orElse(null);
        String fallback = spec != null ? spec.defaultWhatsappBody() : "";
        return ConfigManager.get(NotificationTemplateCatalog.WHATSAPP_TEMPLATE_PREFIX + templateKey + ".body", fallback);
    }

    public static String defaultEmailSubject(DocumentType type) {
        return NotificationTemplateCatalog.byKey(type.key()).map(TemplateSpec::defaultEmailSubject).orElse("");
    }

    public static String defaultEmailBody(DocumentType type) {
        return NotificationTemplateCatalog.byKey(type.key()).map(TemplateSpec::defaultEmailBody).orElse("");
    }

    public static String defaultWhatsappBody(DocumentType type) {
        return NotificationTemplateCatalog.byKey(type.key()).map(TemplateSpec::defaultWhatsappBody).orElse("");
    }

    public static FormattedMessage renderEmailByKey(
            String templateKey,
            Map<String, String> tokens,
            String cardTitle,
            String cardBadge,
            String startHex,
            String endHex
    ) {
        Map<String, String> merged = new LinkedHashMap<>();
        String company = BrandingService.companyName();
        merged.put("CompanyName", company);
        merged.put("AppName", company + " ERP");
        merged.put("Signature", getSignature());
        if (tokens != null) {
            merged.putAll(tokens);
        }
        String subjTpl = getEmailSubject(templateKey);
        String bodyTpl = getEmailBody(templateKey);
        String subject = format(subjTpl, merged);
        boolean htmlMode = shouldUseHtml(templateKey);
        if (TokenTemplateEngine.isHtmlContent(bodyTpl)) {
            String resolved = htmlMode
                    ? TokenTemplateEngine.formatHtmlEscaped(bodyTpl, merged, Set.of())
                    : format(bodyTpl, merged).replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
            return new FormattedMessage(subject, resolved);
        }
        String plain = TokenTemplateEngine.stripWhatsappMarkdown(format(bodyTpl, merged));
        if (!htmlMode) {
            return new FormattedMessage(subject, plain);
        }
        String title = cardTitle == null || cardTitle.isBlank() ? subject : cardTitle;
        String html = TokenTemplateEngine.wrapInCardHtml(
                startHex == null ? "#0284c7" : startHex,
                endHex == null ? "#0369a1" : endHex,
                cardBadge == null ? company : cardBadge,
                title,
                plain,
                List.of(),
                "Generated by " + company + " ERP"
        );
        return new FormattedMessage(subject, html);
    }

    public static FormattedMessage renderEmailByKey(
            String templateKey,
            Map<String, String> tokens,
            String cardTitle,
            String cardBadge
    ) {
        return renderEmailByKey(templateKey, tokens, cardTitle, cardBadge, "#0284c7", "#0369a1");
    }

    public static FormattedMessage renderUrgentEmailByKey(
            String templateKey,
            Map<String, String> tokens,
            String cardTitle,
            String cardBadge
    ) {
        return renderEmailByKey(templateKey, tokens, cardTitle, cardBadge, "#dc2626", "#991b1b");
    }

    public static String wrapPlainBodyInCardHtml(String cardBadge, String cardTitle, String plainBody) {
        boolean attachSig = isAttachSignatureToEmail();
        return wrapPlainBodyInCardHtml(cardBadge, cardTitle, plainBody, attachSig, attachSig ? "cid:signatureImage" : null);
    }

    public static String wrapPlainBodyInCardHtml(
            String cardBadge,
            String cardTitle,
            String plainBody,
            boolean includeInlineSignatureImage,
            String signatureImageSrc
    ) {
        String company = BrandingService.companyName();
        ExtractedSignature extracted = extractBodyAndSignature(plainBody, null);
        String cleanPlain = TokenTemplateEngine.stripWhatsappMarkdown(extracted.bodyWithoutSignature());
        String rawImg = includeInlineSignatureImage
                ? ((signatureImageSrc != null && !signatureImageSrc.isBlank()) ? signatureImageSrc.trim() : "cid:signatureImage")
                : null;
        String address = ConfigManager.get("company.address", "").trim();
        String sigHtml = TokenTemplateEngine.signatureBlockHtml(extracted.signatureText(), rawImg, address);
        List<String> sections = sigHtml.isBlank() ? List.of() : List.of(sigHtml);
        return TokenTemplateEngine.wrapInRichCardHtml(new RichCardSpec(
                "#0284c7",
                "#0369a1",
                company,
                cardBadge == null ? company : cardBadge,
                "#0369a1",
                cardTitle == null || cardTitle.isBlank() ? company : cardTitle,
                "",
                "",
                List.of(),
                cleanPlain,
                sections,
                List.of(),
                company + " ERP",
                sigHtml.isBlank() ? buildCompanyFooterMeta() : buildCompanyFooterMetaWithoutAddress()
        ));
    }

    public static String formatOtpEmailHtml(String recipient, String purpose, String otp) {
        String company = BrandingService.companyName();
        String safeRecipient = recipient == null || recipient.isBlank() ? "Authorized User" : recipient.trim();
        String safePurpose = purpose == null || purpose.isBlank() ? "Security Verification" : purpose.trim();
        String safeOtp = otp == null ? "" : otp.trim();

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("UserName", safeRecipient);
        tokens.put("OtpCode", safeOtp);
        tokens.put("ValidityMinutes", "10");
        tokens.put("CompanyName", company);
        tokens.put("AppName", company + " ERP");
        tokens.put("Signature", getSignature());

        String bodyTpl = getEmailBody("auth_otp");
        if (TokenTemplateEngine.isHtmlContent(bodyTpl)) {
            return TokenTemplateEngine.formatHtmlEscaped(bodyTpl, tokens, Set.of());
        }

        String intro = TokenTemplateEngine.stripWhatsappMarkdown(format(bodyTpl, tokens))
                .replace(safeOtp, "").trim();
        if (intro.isBlank()) {
            intro = "Use the verification code below to complete your " + safePurpose + " in " + company + " ERP.";
        }
        String otpCallout = TokenTemplateEngine.calloutBoxHtml(
                "YOUR ONE-TIME VERIFICATION CODE",
                safeOtp.isBlank() ? "------" : safeOtp,
                "Never share this code with anyone, including support staff. Valid for 10 minutes.",
                "#0284c7",
                "#f0f9ff",
                "#0369a1"
        );
        String detailsTable = TokenTemplateEngine.keyValueSectionHtml(
                "Security Request Details",
                "#0284c7",
                List.of(
                        new KeyValueRow("Recipient Account", safeRecipient, false),
                        new KeyValueRow("Verification Purpose", safePurpose, false),
                        new KeyValueRow("Validity Window", "10 Minutes (Single Use)", false, "#1d4ed8", "#dbeafe")
                )
        );
        return TokenTemplateEngine.wrapInRichCardHtml(new RichCardSpec(
                "#0284c7",
                "#0369a1",
                company + " SECURITY",
                "ONE-TIME PASSWORD",
                "#1d4ed8",
                "Security Verification Code",
                "Time-sensitive sign-in & identity confirmation",
                "Your " + company + " verification code expires in 10 minutes.",
                List.of(
                        new MetaColumn("ACCOUNT", safeRecipient),
                        new MetaColumn("PURPOSE", safePurpose),
                        new MetaColumn("EXPIRY", "10 Minutes", "#1d4ed8")
                ),
                intro,
                List.of(otpCallout, detailsTable),
                List.of(),
                company + " ERP Security",
                buildCompanyFooterMeta()
        ));
    }

    public static FormattedMessage renderRichEmailByKey(
            String templateKey,
            Map<String, String> tokens,
            RichCardSpec richStyleTemplate
    ) {
        Map<String, String> merged = new LinkedHashMap<>();
        String company = BrandingService.companyName();
        merged.put("CompanyName", company);
        merged.put("AppName", company + " ERP");
        merged.put("Signature", getSignature());
        if (tokens != null) {
            merged.putAll(tokens);
        }
        String subjTpl = getEmailSubject(templateKey);
        String bodyTpl = getEmailBody(templateKey);
        String subject = format(subjTpl, merged);
        boolean htmlMode = shouldUseHtml(templateKey);
        if (TokenTemplateEngine.isHtmlContent(bodyTpl)) {
            String resolved = htmlMode
                    ? TokenTemplateEngine.formatHtmlEscaped(bodyTpl, merged, Set.of())
                    : format(bodyTpl, merged).replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
            return new FormattedMessage(subject, resolved);
        }
        String plain = TokenTemplateEngine.stripWhatsappMarkdown(format(bodyTpl, merged));
        if (!htmlMode) {
            return new FormattedMessage(subject, plain);
        }
        RichCardSpec spec = richStyleTemplate != null ? richStyleTemplate : new RichCardSpec(
                "#0284c7", "#0369a1", company, null, null, subject, null, subject,
                List.of(), plain, List.of(), List.of(), company + " ERP", buildCompanyFooterMeta()
        );
        String html = TokenTemplateEngine.wrapInRichCardHtml(new RichCardSpec(
                spec.headerStartHex() == null ? "#0284c7" : spec.headerStartHex(),
                spec.headerEndHex() == null ? "#0369a1" : spec.headerEndHex(),
                spec.kickerText() == null || spec.kickerText().isBlank() ? company : spec.kickerText(),
                spec.badgeText(),
                spec.badgeColorHex(),
                spec.title() == null || spec.title().isBlank() ? subject : spec.title(),
                spec.subtitle(),
                spec.preheader() == null || spec.preheader().isBlank() ? subject : spec.preheader(),
                spec.metaColumns() == null ? List.of() : spec.metaColumns(),
                spec.resolvedPlainBody() != null ? spec.resolvedPlainBody() : plain,
                spec.sectionHtmlBlocks() == null ? List.of() : spec.sectionHtmlBlocks(),
                spec.buttons() == null ? List.of() : spec.buttons(),
                spec.footerTitle() == null || spec.footerTitle().isBlank() ? "Generated automatically by " + company + " ERP" : spec.footerTitle(),
                spec.footerMeta() == null || spec.footerMeta().isBlank() ? buildCompanyFooterMeta() : spec.footerMeta()
        ));
        return new FormattedMessage(subject, html);
    }

    public static String buildCompanyFooterMeta() {
        return buildCompanyFooterMeta(true);
    }

    public static String buildCompanyFooterMetaWithoutAddress() {
        return buildCompanyFooterMeta(false);
    }

    private static String buildCompanyFooterMeta(boolean includeAddress) {
        String address = includeAddress ? ConfigManager.get("company.address", "").trim() : "";
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        String website = ConfigManager.get("company.website", "").trim();
        StringBuilder sb = new StringBuilder();
        if (!address.isBlank()) sb.append(address);
        if (!phone.isBlank()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("Phone: ").append(phone);
        }
        if (!email.isBlank()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("Email: ").append(email);
        }
        if (!website.isBlank()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(website);
        }
        return sb.toString();
    }

    public static String formatStandardPreviewHtml(String subject, String plainBody) {
        String safeSubject = TokenTemplateEngine.escapeHtml(subject == null ? "" : subject);
        String safeBody = TokenTemplateEngine.escapeHtml(
                TokenTemplateEngine.stripWhatsappMarkdown(plainBody == null ? "" : plainBody)
        );
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>"
                + "<body style=\"font-family: ui-monospace, Consolas, monospace; background:#f8fafc; padding:24px; color:#0f172a;\">"
                + "<div style=\"max-width:680px; margin:0 auto; background:#ffffff; border:1px solid #cbd5e1; border-radius:8px; padding:20px;\">"
                + "<div style=\"font-size:12px; color:#64748b; margin-bottom:12px; border-bottom:1px solid #e2e8f0; padding-bottom:8px;\">STANDARD PLAIN-TEXT MODE &bull; Subject: "
                + safeSubject
                + "</div><pre style=\"white-space:pre-wrap; font-family:inherit; margin:0; line-height:1.5;\">"
                + safeBody
                + "</pre></div></body></html>";
    }

    public static String renderWhatsappByKey(String templateKey, Map<String, String> tokens) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("CompanyName", BrandingService.companyName());
        merged.put("AppName", BrandingService.companyName() + " ERP");
        if (tokens != null) {
            merged.putAll(tokens);
        }
        return format(getWhatsappBody(templateKey), merged);
    }

    public static FormattedMessage formatSalesEmail(Sales sale) {
        String partyName = sale.getCustomer() == null ? "Customer" : sale.getCustomer().getName();
        Map<String, String> tokens = buildTokens(
            partyName,
            sale.getInvoiceNo(),
            sale.getInvoiceDate(),
            sale.getTotalAmount(),
            sale.getPaidAmount(),
            sale.getBalanceAmount(),
            DocumentType.SALES_INVOICE
        );
        tokens.put("SubtotalAmount", money(sale.getSubtotal()));
        tokens.put("TaxAmount", money(sale.getGstAmount()));
        tokens.put("DueDate", sale.getDueDate() != null ? BusinessClock.formatDate(sale.getDueDate()) : "On Receipt");
        tokens.put("PartyGstin", firstNonBlank(
                sale.getBillingGstin(),
                sale.getGstin(),
                sale.getCustomer() != null ? sale.getCustomer().getGstin() : "",
                "Unregistered / N/A"
        ));
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
        String partyName = purchase.getSupplier() == null ? "Supplier" : purchase.getSupplier().getName();
        Map<String, String> tokens = buildTokens(
            partyName,
            purchase.getInvoiceNo(),
            purchase.getInvoiceDate(),
            purchase.getTotalAmount(),
            purchase.getPaidAmount(),
            purchase.getBalanceAmount(),
            DocumentType.PURCHASE_BILL
        );
        tokens.put("SubtotalAmount", money(purchase.getSubtotal()));
        tokens.put("TaxAmount", money(purchase.getGstAmount()));
        tokens.put("DueDate", purchase.getDueDate() != null ? BusinessClock.formatDate(purchase.getDueDate()) : "As per terms");
        tokens.put("PartyGstin", firstNonBlank(
                purchase.getBillingGstin(),
                purchase.getSupplier() != null ? purchase.getSupplier().getGstin() : "",
                "Unregistered / N/A"
        ));
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
            amount,
            DocumentType.QUOTATION
        );
        tokens.put("ValidUntil", date != null ? BusinessClock.formatDate(date.plusDays(30)) : "30 Days from Quote Date");
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
            amount,
            DocumentType.SALES_RETURN
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
            amount,
            DocumentType.PURCHASE_RETURN
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
        tokens.put("SubtotalAmount", money(totalAmount));
        tokens.put("TaxAmount", money(0.0));
        tokens.put("DueDate", date == null ? "-" : BusinessClock.formatDate(date));
        tokens.put("ValidUntil", date == null ? "30 Days from Quote Date" : BusinessClock.formatDate(date.plusDays(30)));
        tokens.put("PartyGstin", "Unregistered / N/A");
        tokens.put("OriginalDocument", "-");
        tokens.put("ReturnReason", "Standard Return / Adjustment");
        tokens.put("PaymentMode", "Bank Transfer");
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

    public static String resolvePublicStatusBaseUrl() {
        // Tier 1: Explicit setting configured by administrator/developer in Settings (or public.portal.url)
        String configured = ConfigManager.get("company.publicPortalUrl", "").trim();
        if (!configured.isBlank()) {
            return sanitizePublicPortalUrl(configured);
        }
        String customPortal = ConfigManager.get("public.portal.url", "").trim();
        if (!customPortal.isBlank()) {
            return sanitizePublicPortalUrl(customPortal);
        }

        // Tier 2: Connected Backend Server (Local: http://127.0.0.1:58080, UAT: https://api-uat.jasviindustries.in, PROD: https://api.jasviindustries.in)
        String apiBase = ConfigManager.getDataApiBaseUrl().trim();
        return apiBase.isBlank() ? "http://127.0.0.1:58080" : sanitizePublicPortalUrl(apiBase);
    }

    private static boolean isLocalhostAddress(String url) {
        if (url == null) return true;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    public static String sanitizePublicPortalUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        String url = rawUrl.trim().replaceAll("/+$", "");
        // Fix single-slash or malformed protocol typos (e.g. https:/domain or http:/domain)
        if (url.startsWith("https:/") && !url.startsWith("https://")) {
            url = "https://" + url.substring("https:/".length()).replaceAll("^/+", "");
        } else if (url.startsWith("http:/") && !url.startsWith("http://")) {
            url = "http://" + url.substring("http:/".length()).replaceAll("^/+", "");
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        // Auto-heal common typo in domain name: jasviinduestries -> jasviindustries
        if (url.toLowerCase(Locale.ROOT).contains("jasviinduestries.in")) {
            url = url.replaceAll("(?i)jasviinduestries\\.in", "jasviindustries.in");
        }
        return url;
    }

    public static String buildPublicPaymentStatusUrl(String invoiceNo, double totalAmount) {
        if (invoiceNo == null || invoiceNo.isBlank()) return "";
        try {
            String token = org.example.shared.PaymentStatusTokenService.generateToken(invoiceNo, totalAmount);
            String publicDomain = resolvePublicStatusBaseUrl();
            return publicDomain + "/payment-status?inv=" + java.net.URLEncoder.encode(invoiceNo.trim(), java.nio.charset.StandardCharsets.UTF_8)
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
                includeInlineSignatureImage, null, RichEmailContext.empty()
        );
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
        return formatExecutiveHtmlEmail(
                docTitle, statusBadge, documentNo, date, partyName,
                totalAmount, paidAmount, balanceAmount, plainBody,
                hasDualBank, ourBankName, ourAccNo, ourIfsc,
                remitterDesc, remitterUtr, publicStatusUrl,
                includeInlineSignatureImage, signatureImageSrc, RichEmailContext.empty()
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
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})").matcher(s);
        if (m2.find()) {
            try {
                int day = Integer.parseInt(m2.group(1));
                int month = Integer.parseInt(m2.group(2));
                int year = Integer.parseInt(m2.group(3));
                return BusinessClock.formatDate(LocalDate.of(year, month, day));
            } catch (Exception ignored) {}
        }
        s = s.replaceAll("\\s*\\+[0-9]{2}:?[0-9]{2}$", "").trim();
        if (s.matches("^\\d{1,2}$")) {
            return BusinessClock.formatDate(LocalDate.now());
        }
        return s;
    }

    public static String formatExecutiveBreakdownHtml(String rawBody) {
        return formatExecutiveBreakdownHtml(rawBody, false);
    }

    public static String formatExecutiveBreakdownHtml(String rawBody, boolean omitKpiCards) {
        if (rawBody == null || rawBody.isBlank()) return "";
        String[] lines = rawBody.split("\\r?\\n");
        StringBuilder topText = new StringBuilder();
        List<KeyValueRow> detailRows = new java.util.ArrayList<>();
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
                    } else if (keyLower.contains("disbursed") || keyLower.contains("paid") || keyLower.contains("disburse") || keyLower.contains("received")) {
                        disbursedVal = val;
                    } else if (keyLower.contains("balance")) {
                        balanceVal = val;
                    } else if (keyLower.contains("reference") || keyLower.contains("utr")) {
                        detailRows.add(new KeyValueRow("🏷 " + key, val, true));
                    } else if (keyLower.contains("date") || keyLower.contains("valid")) {
                        detailRows.add(new KeyValueRow("📅 " + key, cleanExecutiveDate(val), false));
                    } else if (keyLower.contains("mode")) {
                        detailRows.add(new KeyValueRow("💳 " + key, val, false));
                    } else if (keyLower.contains("remarks") || keyLower.contains("narration") || keyLower.contains("notes")) {
                        detailRows.add(new KeyValueRow("📝 " + key, val, false));
                    } else {
                        detailRows.add(new KeyValueRow("📋 " + key, val, false));
                    }
                } else {
                    detailRows.add(new KeyValueRow("• Note", content, false));
                }
            } else if (inBreakdown && !trimmed.isBlank()) {
                if (!trimmed.equalsIgnoreCase("{Signature}")) {
                    bottomText.append("<p style=\"margin:8px 0; color:#334155;\">").append(escape(trimmed)).append("</p>\n");
                }
            } else if (!inBreakdown) {
                String lowerTrimmed = trimmed.toLowerCase(Locale.ROOT);
                if (lowerTrimmed.contains("breakdown:") || lowerTrimmed.contains("summary:")) {
                    // Section header owned by structured card below
                } else if (!trimmed.isBlank() && !trimmed.equalsIgnoreCase("{Signature}")) {
                    topText.append("<p style=\"margin:8px 0; color:#334155;\">").append(escape(trimmed)).append("</p>\n");
                }
            }
        }

        if (totalBillVal == null && disbursedVal == null && balanceVal == null && detailRows.isEmpty()) {
            return escape(rawBody).replace("\r\n", "\n").replace("\n", "<br/>\n");
        }

        StringBuilder out = new StringBuilder();
        if (topText.length() > 0) out.append(topText);

        if (!omitKpiCards && (totalBillVal != null || disbursedVal != null || balanceVal != null)) {
            String bVal = balanceVal == null ? "₹ 0.00" : balanceVal;
            boolean zeroBal = bVal.contains("0.00") || bVal.equals("₹ 0") || bVal.equals("0");
            out.append(TokenTemplateEngine.kpiGridHtml(
                    "📊 Remittance & Settlement Breakdown",
                    List.of(
                            new KpiTile("Total Bill Amount", totalBillVal == null ? "-" : totalBillVal, "Document Total", "#1e3a8a"),
                            new KpiTile("Amount Disbursed", disbursedVal == null ? "-" : disbursedVal, "SETTLED", "#16a34a"),
                            new KpiTile("Balance Due", bVal, zeroBal ? "Settled" : "Open Balance", zeroBal ? "#16a34a" : "#dc2626")
                    ),
                    3
            ));
        }

        if (!detailRows.isEmpty()) {
            out.append(TokenTemplateEngine.keyValueSectionHtml(
                    "📊 Remittance & Settlement Details",
                    "#1e3a8a",
                    detailRows
            ));
        }
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
            String signatureImageSrc,
            RichEmailContext context
    ) {
        return formatExecutiveHtmlEmail(
                docTitle, statusBadge, documentNo, date, partyName,
                totalAmount, paidAmount, balanceAmount, plainBody,
                hasDualBank, ourBankName, ourAccNo, ourIfsc,
                remitterDesc, remitterUtr, publicStatusUrl,
                includeInlineSignatureImage, signatureImageSrc, context, null
        );
    }

    public static String resolveSignatureForProfile(String profileKey, String documentTypeOrLabel) {
        if (profileKey != null && !profileKey.isBlank()) {
            String profileCustom = ConfigManager.get("email.signature." + profileKey.trim(), "").trim();
            if (!profileCustom.isBlank()) {
                return expandSignatureTokens(profileCustom);
            }
        }
        String key = resolveTemplateKeyForEntity(documentTypeOrLabel);
        for (DocumentType dt : DocumentType.values()) {
            if (dt.key().equals(key)) {
                return expandSignatureTokens(getSignature(dt));
            }
        }
        return expandSignatureTokens(getSignature());
    }

    private static String expandSignatureTokens(String signature) {
        if (signature == null || signature.isBlank()) return "";
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        return signature.replace("{CompanyName}", company)
                .replace("{CompanyPhone}", phone)
                .replace("{CompanyEmail}", email)
                .trim();
    }

    private record ExtractedSignature(String bodyWithoutSignature, String signatureText) {}

    private static ExtractedSignature extractBodyAndSignature(String rawBody, String preferredSignature) {
        String body = rawBody == null ? "" : rawBody.replace("\r\n", "\n");
        String fallbackSig = (preferredSignature != null && !preferredSignature.isBlank())
                ? expandSignatureTokens(preferredSignature)
                : expandSignatureTokens(getSignature());

        if (body.contains("{Signature}")) {
            return new ExtractedSignature(body.replace("{Signature}", "").stripTrailing(), fallbackSig);
        }

        List<String> candidates = new java.util.ArrayList<>();
        if (preferredSignature != null && !preferredSignature.isBlank()) {
            candidates.add(expandSignatureTokens(preferredSignature));
        }
        String globalSig = expandSignatureTokens(getSignature());
        if (!globalSig.isBlank()) candidates.add(globalSig);
        String defaultSig = expandSignatureTokens(defaultSignature());
        if (!defaultSig.isBlank()) candidates.add(defaultSig);
        for (DocumentType dt : DocumentType.values()) {
            String custom = ConfigManager.get("email.signature." + dt.key(), "").trim();
            if (!custom.isBlank()) candidates.add(expandSignatureTokens(custom));
        }

        String normalizedBody = body.stripTrailing();
        for (String candidate : candidates) {
            String cleanCand = candidate == null ? "" : candidate.replace("\r\n", "\n").trim();
            if (cleanCand.isBlank()) continue;
            int idx = normalizedBody.lastIndexOf(cleanCand);
            if (idx >= 0 && idx + cleanCand.length() >= normalizedBody.length() - 2) {
                String stripped = normalizedBody.substring(0, idx).stripTrailing();
                String effectiveSig = (preferredSignature != null && !preferredSignature.isBlank()) ? fallbackSig : cleanCand;
                return new ExtractedSignature(stripped, effectiveSig);
            }
        }

        // Preserve any user-edited trailing "Regards ..." sign-off block from composer body without duplicating it at top
        String[] blocks = normalizedBody.split("\\n\\s*\\n");
        if (blocks.length >= 2) {
            String tail = blocks[blocks.length - 1].trim();
            String firstLineLower = tail.lines().findFirst().orElse("").trim().toLowerCase(Locale.ROOT);
            if (firstLineLower.startsWith("regards")
                    || firstLineLower.startsWith("warm regards")
                    || firstLineLower.startsWith("best regards")
                    || firstLineLower.startsWith("thanks & regards")
                    || firstLineLower.startsWith("thanks and regards")
                    || firstLineLower.startsWith("sincerely")) {
                int tailIdx = normalizedBody.lastIndexOf(blocks[blocks.length - 1]);
                if (tailIdx > 0) {
                    return new ExtractedSignature(normalizedBody.substring(0, tailIdx).stripTrailing(), tail);
                }
            }
        }

        return new ExtractedSignature(normalizedBody, fallbackSig);
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
            String signatureImageSrc,
            RichEmailContext context,
            String signatureTextOverride
    ) {
        RichEmailContext ctx = context != null ? context : RichEmailContext.empty();
        String company = BrandingService.companyName();
        String phone = ConfigManager.get("company.phone", "").trim();
        String email = ConfigManager.get("company.email", "").trim();
        String website = ConfigManager.get("company.website", "").trim();
        String address = ConfigManager.get("company.address", "").trim();
        String tagline = BrandingService.tagline();
        if (tagline == null || tagline.isBlank() || tagline.equalsIgnoreCase("Business Management Suite")) {
            tagline = ConfigManager.get("company.tagline", "OFFICIAL PAYMENT & SETTLEMENT RECORD");
        }
        String primaryColor = ConfigManager.get("branding.primaryColor", "#1e3a8a").trim();
        if (!primaryColor.matches("^#[0-9a-fA-F]{6}$")) {
            primaryColor = "#1e3a8a";
        }

        String resolvedParty = firstNonBlank(ctx.partyName(), partyName, "Valued Client");
        String resolvedDate = cleanExecutiveDate(firstNonBlank(ctx.documentDate(), date, ""));
        String resolvedDocTitle = docTitle == null || docTitle.isBlank() ? "COMMERCIAL DOCUMENT" : docTitle.trim();
        String docUpper = resolvedDocTitle.toUpperCase(Locale.ROOT);

        boolean isQuotation = docUpper.contains("QUOT");
        boolean isPurchase = docUpper.contains("PURCHASE") || docUpper.contains("REMITTANCE");
        boolean isReturn = docUpper.contains("RETURN") || docUpper.contains("CREDIT NOTE")
                || docUpper.contains("DEBIT NOTE") || docUpper.contains("REFUND");
        boolean isSalesInvoice = !isQuotation && !isPurchase && !isReturn
                && (docUpper.contains("INVOICE") || docUpper.contains("SALE"));

        String resolvedBadge = statusBadge == null || statusBadge.isBlank() ? "OFFICIAL" : statusBadge.trim();
        String badgeUpper = resolvedBadge.toUpperCase(Locale.ROOT);
        String badgeBg = primaryColor;
        if (badgeUpper.contains("PAID") && !badgeUpper.contains("PARTIAL") && !badgeUpper.contains("UNPAID")) {
            badgeBg = "#16a34a";
        } else if (badgeUpper.contains("SETTLED") || badgeUpper.contains("APPROVED") || badgeUpper.contains("ACCEPTED")) {
            badgeBg = "#16a34a";
        } else if (badgeUpper.contains("PARTIAL") || badgeUpper.contains("PENDING") || badgeUpper.contains("SENT")) {
            badgeBg = "#d97706";
        } else if (badgeUpper.contains("OVERDUE") || badgeUpper.contains("CANCEL") || badgeUpper.contains("REJECT")) {
            badgeBg = "#dc2626";
        }

        String preferredSig = (signatureTextOverride != null && !signatureTextOverride.isBlank())
                ? signatureTextOverride
                : resolveSignatureForProfile(null, resolvedDocTitle);
        ExtractedSignature extracted = extractBodyAndSignature(plainBody, preferredSig);
        String safeBodyHtml = formatExecutiveBreakdownHtml(extracted.bodyWithoutSignature(), true);

        // Meta Bar Columns
        String partyMetaLabel = isPurchase ? "SUPPLIER"
                : (isQuotation ? "QUOTED FOR"
                : (docUpper.contains("RECEIPT") || docUpper.contains("REFUND") ? "PARTY" : "BILLED TO"));
        List<MetaColumn> metaColumns = new java.util.ArrayList<>();
        metaColumns.add(new MetaColumn("DOCUMENT", documentNo == null || documentNo.isBlank() ? "-" : documentNo));
        metaColumns.add(new MetaColumn("DATE", resolvedDate));
        metaColumns.add(new MetaColumn(partyMetaLabel, resolvedParty));
        if (ctx.dueDate() != null && !ctx.dueDate().isBlank()) {
            metaColumns.add(new MetaColumn(isQuotation ? "VALID UNTIL" : "DUE DATE", cleanExecutiveDate(ctx.dueDate())));
        } else if (ctx.originalDocumentNo() != null && !ctx.originalDocumentNo().isBlank()) {
            metaColumns.add(new MetaColumn("ORIGINAL DOC", ctx.originalDocumentNo().trim()));
        } else if (ctx.partyGstin() != null && !ctx.partyGstin().isBlank()) {
            metaColumns.add(new MetaColumn("GSTIN", ctx.partyGstin().trim()));
        }

        // Section Blocks
        List<String> sections = new java.util.ArrayList<>();
        if (safeBodyHtml != null && !safeBodyHtml.isBlank()) {
            sections.add("<div style=\"margin:0 0 14px 0; font-size:14px; line-height:1.6; color:#334155;\">" + safeBodyHtml + "</div>");
        }

        // Document-Type-Aware 3-Tile KPI Summary
        boolean zeroBal = balanceAmount <= 0.009;
        String balanceColor = zeroBal ? "#16a34a" : "#dc2626";
        List<KpiTile> kpiTiles;
        String kpiSectionTitle;
        if (isQuotation) {
            kpiSectionTitle = "📋 Quotation Summary & Estimate";
            String subText = ctx.subtotalAmount() != null ? money(ctx.subtotalAmount()) : money(totalAmount);
            String taxText = ctx.taxAmount() != null ? money(ctx.taxAmount()) : "As Applicable";
            kpiTiles = List.of(
                    new KpiTile("Taxable Estimate", subText, "Pre-tax value", primaryColor),
                    new KpiTile("Estimated Tax (GST)", taxText, firstNonBlank(ctx.taxBreakup(), "Standard GST"), "#0369a1"),
                    new KpiTile("Grand Quote Total", money(totalAmount), "Commercial Offer", "#16a34a")
            );
        } else if (isPurchase) {
            kpiSectionTitle = "📊 Purchase Bill & Disbursement Summary";
            kpiTiles = List.of(
                    new KpiTile("Total Bill Amount", money(totalAmount), "Verified Bill", primaryColor),
                    new KpiTile("Amount Disbursed", money(paidAmount), zeroBal && totalAmount > 0 ? "SETTLED" : (paidAmount > 0 ? "Part Settled" : "Unpaid"), "#16a34a"),
                    new KpiTile("Balance Payable", money(balanceAmount), zeroBal ? "Settled" : "Payable", balanceColor)
            );
        } else if (isReturn) {
            kpiSectionTitle = "🔄 Return & Credit Adjustment Summary";
            kpiTiles = List.of(
                    new KpiTile("Return / Credit Value", money(totalAmount), firstNonBlank(ctx.originalDocumentNo(), "Credit Note"), primaryColor),
                    new KpiTile("Adjusted / Refunded", money(paidAmount), zeroBal && totalAmount > 0 ? "SETTLED" : "Processed", "#16a34a"),
                    new KpiTile("Pending Settlement", money(balanceAmount), zeroBal ? "Closed" : "Open Balance", balanceColor)
            );
        } else {
            kpiSectionTitle = docUpper.contains("RECEIPT")
                    ? "📊 Remittance & Settlement Breakdown"
                    : "📊 Invoice & Payment Summary";
            kpiTiles = List.of(
                    new KpiTile("Total Invoice Amount", money(totalAmount), "Billed Total", primaryColor),
                    new KpiTile("Amount Received", money(paidAmount), zeroBal && totalAmount > 0 ? "SETTLED" : (paidAmount > 0 ? "Partial Receipt" : "Pending"), "#16a34a"),
                    new KpiTile("Balance Due", money(balanceAmount), zeroBal ? "Paid in Full" : "Balance Payable", balanceColor)
            );
        }
        sections.add(TokenTemplateEngine.kpiGridHtml(kpiSectionTitle, kpiTiles, 3));

        // Itemized Line Items Preview Table + GST Tax Split
        if (ctx.lineItems() != null && !ctx.lineItems().isEmpty()) {
            List<TableColumn> cols = List.of(
                    new TableColumn("Item / Description", false, "38%"),
                    new TableColumn("HSN/SAC", false, "14%"),
                    new TableColumn("Qty", true, "14%"),
                    new TableColumn("GST", true, "16%"),
                    new TableColumn("Amount", true, "18%")
            );
            List<List<String>> tableRows = new java.util.ArrayList<>();
            int limit = Math.min(8, ctx.lineItems().size());
            for (int i = 0; i < limit; i++) {
                LineItemPreview item = ctx.lineItems().get(i);
                tableRows.add(List.of(
                        firstNonBlank(item.description(), "-"),
                        firstNonBlank(item.hsnSac(), "-"),
                        firstNonBlank(item.quantityWithUnit(), "-"),
                        firstNonBlank(item.gstFormatted(), "-"),
                        firstNonBlank(item.totalFormatted(), "-")
                ));
            }
            StringBuilder note = new StringBuilder();
            if (ctx.subtotalAmount() != null || ctx.taxAmount() != null) {
                if (ctx.subtotalAmount() != null) note.append("Taxable Subtotal: ").append(money(ctx.subtotalAmount()));
                if (ctx.taxAmount() != null) {
                    if (note.length() > 0) note.append("  |  ");
                    note.append("Total GST: ").append(money(ctx.taxAmount()));
                }
                if (ctx.taxBreakup() != null && !ctx.taxBreakup().isBlank()) {
                    note.append(" (").append(ctx.taxBreakup().trim()).append(")");
                }
            }
            if (ctx.lineItems().size() > 8) {
                if (note.length() > 0) note.append("  •  ");
                note.append("Showing first 8 of ").append(ctx.lineItems().size()).append(" items — full schedule attached in PDF.");
            }
            sections.add(TokenTemplateEngine.dataTableHtml(
                    "📦 Itemized Line Schedule (" + ctx.lineItems().size() + ")",
                    isQuotation ? "Quoted Items" : "GST Included",
                    cols,
                    tableRows,
                    note.length() > 0 ? note.toString() : null
            ));
        } else if (ctx.subtotalAmount() != null || ctx.taxAmount() != null
                || (ctx.partyGstin() != null && !ctx.partyGstin().isBlank())
                || (ctx.paymentMode() != null && !ctx.paymentMode().isBlank())
                || (ctx.paymentReference() != null && !ctx.paymentReference().isBlank())) {
            List<KeyValueRow> metaRows = new java.util.ArrayList<>();
            if (ctx.partyGstin() != null && !ctx.partyGstin().isBlank()) {
                metaRows.add(new KeyValueRow("Party GSTIN", ctx.partyGstin().trim(), true));
            }
            if (ctx.subtotalAmount() != null) {
                metaRows.add(new KeyValueRow("Taxable Value", money(ctx.subtotalAmount()), false));
            }
            if (ctx.taxAmount() != null) {
                metaRows.add(new KeyValueRow("Tax / GST Total", money(ctx.taxAmount()) + (ctx.taxBreakup() != null && !ctx.taxBreakup().isBlank() ? " (" + ctx.taxBreakup().trim() + ")" : ""), false));
            }
            if (ctx.paymentMode() != null && !ctx.paymentMode().isBlank()) {
                metaRows.add(new KeyValueRow("Settlement Mode", ctx.paymentMode().trim(), false));
            }
            if (ctx.paymentReference() != null && !ctx.paymentReference().isBlank()) {
                metaRows.add(new KeyValueRow("Bank Reference / UTR", ctx.paymentReference().trim(), true));
            }
            if (ctx.originalDocumentNo() != null && !ctx.originalDocumentNo().isBlank()) {
                metaRows.add(new KeyValueRow("Original Reference Doc", ctx.originalDocumentNo().trim(), true));
            }
            if (!metaRows.isEmpty()) {
                sections.add(TokenTemplateEngine.keyValueSectionHtml("📑 Tax & Settlement Particulars", primaryColor, metaRows));
            }
        }

        // Dual Bank Card (Settlement Receipts) OR Company Payment Instructions (Unpaid Sales Invoices & Quotations)
        if (hasDualBank) {
            sections.add(TokenTemplateEngine.infoSplitPanelHtml(
                    "Bank Settlement & Reconciliation Record",
                    "Beneficiary Bank (" + company + ")",
                    primaryColor,
                    List.of(
                            new KeyValueRow("Bank", firstNonBlank(ourBankName, "Company Bank Account"), false),
                            new KeyValueRow("A/C No", firstNonBlank(ourAccNo, "-"), true),
                            new KeyValueRow("IFSC", firstNonBlank(ourIfsc, "-"), true)
                    ),
                    "Remitter / Bank Statement Match",
                    "#16a34a",
                    List.of(
                            new KeyValueRow("Payer", firstNonBlank(remitterDesc, resolvedParty), false),
                            new KeyValueRow("Reference / UTR", firstNonBlank(remitterUtr, ctx.paymentReference(), "-"), true),
                            new KeyValueRow("Channel", firstNonBlank(ctx.paymentMode(), "Bank Statement Reconciliation"), false)
                    )
            ));
        } else if ((isSalesInvoice && balanceAmount > 0.009) || isQuotation) {
            String payBank = firstNonBlank(ourBankName, ConfigManager.get("payment.bankName", ConfigManager.get("company.bank.name", "")));
            String payAcc = firstNonBlank(ourAccNo, ConfigManager.get("payment.accountNumber", ConfigManager.get("company.bank.account", "")));
            String payIfsc = firstNonBlank(ourIfsc, ConfigManager.get("payment.ifsc", ConfigManager.get("company.bank.ifsc", "")));
            String payHolder = ConfigManager.get("payment.accountHolder", company).trim();
            String payUpi = firstNonBlank(ConfigManager.get("payment.upiId", ""), ConfigManager.get("company.upiId", ""));
            if (!payBank.isBlank() || !payAcc.isBlank() || !payUpi.isBlank()) {
                List<KeyValueRow> bankRows = new java.util.ArrayList<>();
                bankRows.add(new KeyValueRow("Beneficiary Name", firstNonBlank(payHolder, company), false));
                if (!payBank.isBlank()) bankRows.add(new KeyValueRow("Bank Name", payBank, false));
                if (!payAcc.isBlank()) bankRows.add(new KeyValueRow("Account Number", payAcc, true));
                if (!payIfsc.isBlank()) bankRows.add(new KeyValueRow("IFSC Code", payIfsc, true));
                if (!payUpi.isBlank()) bankRows.add(new KeyValueRow("UPI Payment ID", payUpi, true, "#0369a1", "#e0f2fe"));
                bankRows.add(new KeyValueRow("Payment Reference", firstNonBlank(documentNo, "-"), true));
                sections.add(TokenTemplateEngine.keyValueSectionHtml(
                        isQuotation ? "🏦 Bank & Remittance Details (For Order Confirmation)" : "💳 Payment & Remittance Instructions",
                        primaryColor,
                        bankRows
                ));
            }
        }

        // Live Status Portal CTA
        if (TokenTemplateEngine.isSafeHttpUrl(publicStatusUrl)) {
            String safeUrl = TokenTemplateEngine.escapeHtml(publicStatusUrl.trim());
            sections.add("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:18px 0 8px 0;\">"
                    + "<tr><td align=\"center\">"
                    + "<a href=\"" + safeUrl + "\" target=\"_blank\" style=\"display:inline-block; background:" + primaryColor
                    + "; color:#ffffff; font-weight:700; font-size:13px; text-decoration:none; padding:12px 24px; border-radius:8px; letter-spacing:0.3px;\">&#128269; VIEW LIVE PAYMENT &amp; INVOICE STATUS</a>"
                    + "<div style=\"font-size:11px; color:#64748b; margin-top:6px;\">Click anytime to view real-time settlement and payment reconciliation in our portal</div>"
                    + "</td></tr></table>");
        }

        // Unified Executive Signature Block (Settings text with clickable contacts -> Banner/Stamp Image -> Office Address)
        String rawImgSrc = includeInlineSignatureImage
                ? ((signatureImageSrc != null && !signatureImageSrc.isBlank()) ? signatureImageSrc.trim() : "cid:signatureImage")
                : null;
        String signatureBlock = TokenTemplateEngine.signatureBlockHtml(
                extracted.signatureText(),
                rawImgSrc,
                address
        );
        if (!signatureBlock.isBlank()) {
            sections.add(signatureBlock);
        }

        // Rich Footer Meta preserving dynamic phone, email, website without repeating Office Address already shown under logo
        StringBuilder footerMeta = new StringBuilder();
        if (signatureBlock.isBlank() && !address.isBlank()) {
            footerMeta.append(address);
        }
        if (!phone.isBlank()) {
            if (footerMeta.length() > 0) footerMeta.append(" • ");
            footerMeta.append("Phone: ").append(phone);
        }
        if (!email.isBlank()) {
            if (footerMeta.length() > 0) footerMeta.append(" • ");
            footerMeta.append("Email: ").append(email);
        }
        if (!website.isBlank()) {
            String webUrl = (website.startsWith("http://") || website.startsWith("https://")) ? website : "https://" + website;
            if (footerMeta.length() > 0) footerMeta.append(" • ");
            footerMeta.append(webUrl);
        }

        String preheader = resolvedDocTitle + " " + (documentNo == null ? "" : documentNo)
                + " — " + resolvedParty + " • Total " + money(totalAmount)
                + (!zeroBal ? " • Balance " + money(balanceAmount) : " • Settled");

        return TokenTemplateEngine.wrapInRichCardHtml(new RichCardSpec(
                primaryColor,
                primaryColor,
                resolvedDocTitle.toUpperCase(Locale.ROOT),
                resolvedBadge.toUpperCase(Locale.ROOT),
                badgeBg,
                company,
                tagline.toUpperCase(Locale.ROOT),
                preheader,
                metaColumns,
                null,
                sections,
                List.of(),
                "Generated automatically by " + company + " ERP",
                footerMeta.toString()
        ));
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) return "";
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static FormattedMessage buildSmtpDiagnosticMessage(
            String smtpHost,
            String smtpPort,
            String senderEmail,
            String verifiedAt) {
        String appName = BrandingService.applicationName();
        String safeHost = smtpHost == null || smtpHost.isBlank() ? "-" : smtpHost.trim();
        String safePort = smtpPort == null || smtpPort.isBlank() ? "-" : smtpPort.trim();
        String safeSender = senderEmail == null ? "" : senderEmail.trim();
        String safeTime = verifiedAt == null ? "" : verifiedAt.trim();
        var diagnosticRows = List.of(
                new KeyValueRow("SMTP Host", safeHost, true),
                new KeyValueRow("SMTP Port", safePort, true),
                new KeyValueRow("Sender Address", safeSender, false),
                new KeyValueRow("Verified At", safeTime, false),
                new KeyValueRow("Connection Status", "AUTHENTICATED & HEALTHY", false, "#15803d", "#dcfce7")
        );
        return renderRichEmailByKey(
                "smtp_test",
                Map.of(
                        "SmtpHost", safeHost,
                        "SmtpPort", safePort,
                        "SenderEmail", safeSender,
                        "Recipient", safeSender,
                        "Timestamp", safeTime
                ),
                new RichCardSpec(
                        "#0f766e", "#115e59",
                        "SMTP DIAGNOSTICS",
                        "VERIFIED",
                        "#15803d",
                        appName + " SMTP Diagnostics",
                        "Outbound mail transport health check",
                        "SMTP relay verification passed for " + safeSender,
                        List.of(
                                new MetaColumn("HOST", safeHost),
                                new MetaColumn("PORT", safePort),
                                new MetaColumn("SENDER", safeSender)
                        ),
                        null,
                        List.of(TokenTemplateEngine.keyValueSectionHtml("SMTP Connection Diagnostics", "#0f766e", diagnosticRows)),
                        List.of(),
                        appName + " ERP",
                        buildCompanyFooterMeta()
                )
        );
    }

    public static String buildTemplatePreviewCardHtml(
            String categoryDisplayName,
            String specKey,
            String specDisplayName,
            String resolvedPlain,
            String partyName) {
        String company = BrandingService.companyName();
        String catLabel = categoryDisplayName == null ? "NOTIFICATION" : categoryDisplayName;
        return TokenTemplateEngine.wrapInRichCardHtml(new RichCardSpec(
                "#0284c7", "#0369a1",
                catLabel.toUpperCase(Locale.ROOT),
                "SAMPLE PREVIEW",
                "#0369a1",
                specDisplayName == null ? "Template Preview" : specDisplayName,
                company + " \u2022 Notification Template Preview",
                resolvedPlain,
                List.of(
                        new MetaColumn("TEMPLATE", specKey == null ? "" : specKey),
                        new MetaColumn("DATE", BusinessClock.formatDate(LocalDate.now())),
                        new MetaColumn("PARTY", partyName == null || partyName.isBlank() ? company : partyName)
                ),
                resolvedPlain,
                List.of(TokenTemplateEngine.keyValueSectionHtml(
                        "Active Template Tokens Sampled", "#0284c7",
                        List.of(
                                new KeyValueRow("Company", company, false),
                                new KeyValueRow("Category", catLabel, false),
                                new KeyValueRow("Format Mode", "HTML Card (Table Layout)", false, "#15803d", "#dcfce7")
                        )
                )),
                List.of(),
                company + " ERP",
                buildCompanyFooterMeta()
        ));
    }

    public static FormattedMessage buildGstRule36NoticeMessage(
            Map<String, String> tokens,
            String companyName,
            String supplierGstin,
            String recipientGstin,
            String taxableDisplay,
            String taxAmountDisplay,
            String gstBreakup,
            String registerStatus) {
        String safeCompany = companyName == null || companyName.isBlank() ? BrandingService.companyName() : companyName.trim();
        String safeStatus = registerStatus == null || registerStatus.isBlank() ? "MISMATCH" : registerStatus.trim();
        String invNo = tokens != null ? tokens.getOrDefault("InvoiceNo", "") : "";
        String invDate = tokens != null ? tokens.getOrDefault("InvoiceDate", "") : "";
        String supplierName = tokens != null ? tokens.getOrDefault("SupplierName", "Supplier") : "Supplier";
        var splitComparisonHtml = TokenTemplateEngine.infoSplitPanelHtml(
                "Books vs GSTR-2B Reconciliation Comparison",
                "Our Purchase Register",
                "#0369a1",
                List.of(
                        new KeyValueRow("Invoice No", invNo, true),
                        new KeyValueRow("Invoice Date", invDate, false),
                        new KeyValueRow("Taxable Value", taxableDisplay, false),
                        new KeyValueRow("Tax Breakup", gstBreakup, false)
                ),
                "GSTR-2B Portal Compliance",
                "#dc2626",
                List.of(
                        new KeyValueRow("Supplier GSTIN", supplierGstin, true),
                        new KeyValueRow("Recipient GSTIN", recipientGstin, true),
                        new KeyValueRow("ITC at Risk", taxAmountDisplay, false, "#b91c1c", "#fee2e2"),
                        new KeyValueRow("Status", safeStatus, false, "#b45309", "#fef3c7")
                )
        );
        var remediationCallout = TokenTemplateEngine.calloutBoxHtml(
                "STATUTORY ACTION REQUIRED — RULE 36(4) CGST RULES",
                "Upload B2B Invoice #" + invNo + " in GSTR-1 / IFF",
                "Please report this invoice in your GSTR-1 / IFF return immediately and share confirmation / ARN so eligible ITC of " + taxAmountDisplay + " is not deferred or reversed.",
                "#dc2626",
                "#fef2f2",
                "#991b1b"
        );
        return renderRichEmailByKey(
                "gst_rule36_notice",
                tokens,
                new RichCardSpec(
                        "#b91c1c",
                        "#7f1d1d",
                        "GST COMPLIANCE & ITC VERIFICATION",
                        safeStatus.toUpperCase(Locale.ROOT),
                        "#dc2626",
                        "GST Rule 36(4) Discrepancy Notice",
                        safeCompany + " \u2022 Statutory ITC Verification Demand",
                        "Rule 36(4) ITC notice for Invoice #" + invNo + " (ITC at risk: " + taxAmountDisplay + ")",
                        List.of(
                                new MetaColumn("SUPPLIER", supplierName),
                                new MetaColumn("INVOICE #", invNo),
                                new MetaColumn("ITC AT RISK", taxAmountDisplay, "#dc2626")
                        ),
                        null,
                        List.of(splitComparisonHtml, remediationCallout),
                        List.of(),
                        safeCompany + " Tax & Compliance",
                        buildCompanyFooterMeta()
                )
        );
    }

    public static Map<String, String> sampleTokens() {
        return buildTokens("Acme Enterprises", "INV-2026-0042", LocalDate.now(), 24500.00, 10000.00, 14500.00);
    }

    private static String money(double amount) {
        return "\u20B9" + String.format(Locale.of("en", "IN"), "%,.2f", amount);
    }
}
