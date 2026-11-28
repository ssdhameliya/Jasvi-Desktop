package org.example.server.communication;

import org.example.server.persistence.JpaNativeRepository;
import org.example.shared.communication.NotificationTemplateCatalog;
import org.example.shared.communication.NotificationTemplateCatalog.EmailFormatMode;
import org.example.shared.communication.NotificationTemplateCatalog.TemplateSpec;
import org.example.shared.communication.TokenTemplateEngine;
import org.example.shared.communication.TokenTemplateEngine.ActionButton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves company-customizable Email and WhatsApp templates from PostgreSQL {@code application_setting}
 * and renders emails cleanly in either rich HTML Card mode or Standard Plain Text mode according to
 * {@code email.format.mode} and optional per-template overrides.
 */
@Service
public class ServerNotificationTemplateService {

    public record CardStyle(
            String headerStartHex,
            String headerEndHex,
            String badgeText,
            String title,
            List<ActionButton> buttons,
            String footerText
    ) {
        public static CardStyle standardBlue(String badgeText, String title, String footerText) {
            return new CardStyle("#0f172a", "#1e3a8a", badgeText, title, List.of(), footerText);
        }
    }

    public record RichCardStyle(
            String headerStartHex,
            String headerEndHex,
            String kickerText,
            String badgeText,
            String badgeColorHex,
            String title,
            String subtitle,
            String preheader,
            List<TokenTemplateEngine.MetaColumn> metaColumns,
            List<String> sectionHtmlBlocks,
            List<ActionButton> buttons,
            String footerTitle,
            String footerMeta
    ) {}

    public record RenderedEmail(String subject, String body, boolean html) {}

    private final JpaNativeRepository jdbc;

    @Autowired
    public ServerNotificationTemplateService(JpaNativeRepository jdbc) {
        this.jdbc = jdbc;
    }

    /** Factory helper for unit tests or legacy constructors without Spring injection. */
    public static ServerNotificationTemplateService forJdbc(JpaNativeRepository jdbc) {
        return new ServerNotificationTemplateService(jdbc);
    }

    public String setting(String key, String fallback) {
        if (jdbc == null || key == null || key.isBlank()) return fallback;
        try {
            List<String> rows = jdbc.query(
                    "SELECT setting_value FROM application_setting WHERE setting_key = ?",
                    (rs, i) -> rs.getString(1),
                    key.trim()
            );
            if (!rows.isEmpty() && rows.getFirst() != null && !rows.getFirst().isBlank()) {
                return rows.getFirst().trim();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public String companyName() {
        String configured = setting("company.name", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            if (jdbc != null) {
                List<String> names = jdbc.query(
                        "SELECT company_name FROM app_license WHERE status = 'ACTIVE' AND company_name IS NOT NULL AND TRIM(company_name) <> '' ORDER BY id DESC LIMIT 1",
                        (rs, i) -> rs.getString(1)
                );
                if (!names.isEmpty() && names.getFirst() != null && !names.getFirst().isBlank()) {
                    return names.getFirst().trim();
                }
            }
        } catch (Exception ignored) {}
        return "Your Organization";
    }

    public String companySupportEmail() {
        String support = setting("licensing.support_email", "");
        if (support != null && !support.isBlank()) return support.trim();
        String companyEmail = setting("company.email", "");
        if (companyEmail != null && !companyEmail.isBlank()) return companyEmail.trim();
        String smtpEmail = setting("smtp.email", "");
        return smtpEmail == null ? "" : smtpEmail.trim();
    }

    public EmailFormatMode emailFormatMode() {
        String raw = setting(NotificationTemplateCatalog.EMAIL_FORMAT_MODE_KEY, EmailFormatMode.HTML.name());
        EmailFormatMode parsed = EmailFormatMode.parse(raw, EmailFormatMode.HTML);
        return parsed == EmailFormatMode.INHERIT ? EmailFormatMode.HTML : parsed;
    }

    public EmailFormatMode emailFormatModeFor(String templateKey) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey).orElse(null);
        if (spec != null) {
            String rawOverride = setting(spec.emailFormatSettingKey(), EmailFormatMode.INHERIT.name());
            EmailFormatMode override = EmailFormatMode.parse(rawOverride, EmailFormatMode.INHERIT);
            if (override != EmailFormatMode.INHERIT) {
                return override;
            }
        }
        return emailFormatMode();
    }

    public boolean shouldUseHtml(String templateKey) {
        return emailFormatModeFor(templateKey) != EmailFormatMode.STANDARD;
    }

    public RenderedEmail renderEmail(
            String templateKey,
            Map<String, String> tokens,
            CardStyle cardStyle,
            Set<String> rawHtmlTokenKeys
    ) {
        CardStyle style = cardStyle != null
                ? cardStyle
                : CardStyle.standardBlue("", "", "");
        return renderRichEmail(templateKey, tokens, new RichCardStyle(
                style.headerStartHex(),
                style.headerEndHex(),
                "",
                style.badgeText(),
                "#0f172a",
                style.title(),
                "",
                "",
                List.of(),
                List.of(),
                style.buttons(),
                style.footerText(),
                ""
        ), rawHtmlTokenKeys);
    }

    public RenderedEmail renderRichEmail(String templateKey, Map<String, String> tokens, RichCardStyle richStyle) {
        return renderRichEmail(templateKey, tokens, richStyle, Set.of());
    }

    public RenderedEmail renderRichEmail(
            String templateKey,
            Map<String, String> tokens,
            RichCardStyle richStyle,
            Set<String> rawHtmlTokenKeys
    ) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification template key: " + templateKey));
        Map<String, String> merged = mergeDefaultTokens(tokens);

        String subjectTemplate = setting(spec.emailSubjectSettingKey(), spec.defaultEmailSubject());
        String bodyTemplate = setting(spec.emailBodySettingKey(), spec.defaultEmailBody());

        String subject = TokenTemplateEngine.format(subjectTemplate, merged);
        boolean htmlMode = shouldUseHtml(templateKey);
        boolean customIsHtml = TokenTemplateEngine.isHtmlContent(bodyTemplate);

        if (customIsHtml) {
            if (htmlMode) {
                String resolvedHtml = TokenTemplateEngine.formatHtmlEscaped(
                        bodyTemplate,
                        merged,
                        rawHtmlTokenKeys == null ? Set.of() : rawHtmlTokenKeys
                );
                return new RenderedEmail(subject, resolvedHtml, true);
            }
            // User switched to STANDARD mode while an HTML override existed: strip HTML tags cleanly
            String resolved = TokenTemplateEngine.format(bodyTemplate, merged)
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("(?i)</(p|div|tr|h[1-6]|li)>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();
            return new RenderedEmail(subject, resolved, false);
        }

        String plainBody = TokenTemplateEngine.stripWhatsappMarkdown(TokenTemplateEngine.format(bodyTemplate, merged));
        if (!htmlMode) {
            return new RenderedEmail(subject, plainBody, false);
        }

        String title = richStyle != null && richStyle.title() != null && !richStyle.title().isBlank()
                ? richStyle.title()
                : subject;
        String badge = richStyle != null && richStyle.badgeText() != null
                ? richStyle.badgeText()
                : merged.get("CompanyName");
        String footerTitle = richStyle != null && richStyle.footerTitle() != null && !richStyle.footerTitle().isBlank()
                ? richStyle.footerTitle()
                : "Automated notification from " + merged.get("AppName");
        String footerMeta = richStyle != null && richStyle.footerMeta() != null && !richStyle.footerMeta().isBlank()
                ? richStyle.footerMeta()
                : (!merged.getOrDefault("SupportEmail", "").isBlank() ? "Support: " + merged.get("SupportEmail") : "");
        String preheader = richStyle != null && richStyle.preheader() != null && !richStyle.preheader().isBlank()
                ? richStyle.preheader()
                : subject;

        String html = TokenTemplateEngine.wrapInRichCardHtml(new TokenTemplateEngine.RichCardSpec(
                richStyle != null ? richStyle.headerStartHex() : "#0f172a",
                richStyle != null ? richStyle.headerEndHex() : "#1e3a8a",
                richStyle != null ? richStyle.kickerText() : "",
                badge,
                richStyle != null ? richStyle.badgeColorHex() : "#1e3a8a",
                title,
                richStyle != null ? richStyle.subtitle() : "",
                preheader,
                richStyle != null ? richStyle.metaColumns() : List.of(),
                plainBody,
                richStyle != null ? richStyle.sectionHtmlBlocks() : List.of(),
                richStyle != null ? richStyle.buttons() : List.of(),
                footerTitle,
                footerMeta
        ));
        return new RenderedEmail(subject, html, true);
    }

    /** Generates clean high-contrast QR PNG bytes using bundled iText BarcodeQRCode. */
    public static byte[] generateQrPngBytes(String content, int pixels) {
        if (content == null || content.isBlank()) return null;
        int size = Math.max(180, Math.min(360, pixels));
        int pad = 12;
        try {
            var qrcode = new com.itextpdf.barcodes.BarcodeQRCode(content.trim());
            java.awt.Image awtImg = qrcode.createAwtImage(java.awt.Color.BLACK, java.awt.Color.WHITE);
            var buffered = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = buffered.createGraphics();
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, size, size);
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2d.drawImage(awtImg, pad, pad, size - (pad * 2), size - (pad * 2), null);
            g2d.dispose();
            var baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(buffered, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    public RenderedEmail renderEmail(String templateKey, Map<String, String> tokens, CardStyle cardStyle) {
        return renderEmail(templateKey, tokens, cardStyle, Set.of());
    }

    public String renderWhatsapp(String templateKey, Map<String, String> tokens) {
        TemplateSpec spec = NotificationTemplateCatalog.byKey(templateKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification template key: " + templateKey));
        Map<String, String> merged = mergeDefaultTokens(tokens);
        String bodyTemplate = setting(spec.whatsappBodySettingKey(), spec.defaultWhatsappBody());
        return TokenTemplateEngine.format(bodyTemplate, merged);
    }

    private Map<String, String> mergeDefaultTokens(Map<String, String> incoming) {
        String company = companyName();
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("CompanyName", company);
        merged.put("AppName", company + " ERP");
        merged.put("SupportEmail", companySupportEmail());
        if (incoming != null) {
            for (var entry : incoming.entrySet()) {
                if (entry.getKey() != null) {
                    merged.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
        return merged;
    }
}
