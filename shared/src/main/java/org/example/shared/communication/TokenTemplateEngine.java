package org.example.shared.communication;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free template formatting and HTML/Standard email card builder
 * shared between the Spring Boot backend and JavaFX desktop application.
 */
public final class TokenTemplateEngine {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private TokenTemplateEngine() {}

    /**
     * Replaces {@code {Token}} placeholders in a single pass so substituted values
     * containing braces are never re-interpreted as template tokens.
     */
    public static String format(String template, Map<String, String> tokens) {
        if (template == null || template.isBlank()) return "";
        if (tokens == null || tokens.isEmpty()) return template;
        Matcher matcher = TOKEN_PATTERN.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = tokens.containsKey(key)
                    ? (tokens.get(key) == null ? "" : tokens.get(key))
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Replaces {@code {Token}} placeholders inside an HTML document while escaping
     * HTML characters for dynamic values except keys explicitly listed as raw HTML.
     */
    public static String formatHtmlEscaped(String htmlTemplate, Map<String, String> tokens, Set<String> rawHtmlKeys) {
        if (htmlTemplate == null || htmlTemplate.isBlank()) return "";
        if (tokens == null || tokens.isEmpty()) return htmlTemplate;
        Matcher matcher = TOKEN_PATTERN.matcher(htmlTemplate);
        StringBuilder out = new StringBuilder(htmlTemplate.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!tokens.containsKey(key)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String value = tokens.get(key) == null ? "" : tokens.get(key);
            String safe = rawHtmlKeys != null && rawHtmlKeys.contains(key) ? value : escapeHtml(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(safe));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static boolean isHtmlContent(String content) {
        if (content == null || content.isBlank()) return false;
        String trimmed = content.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<")
                || trimmed.contains("<html")
                || trimmed.contains("<div")
                || trimmed.contains("<table")
                || trimmed.contains("<p>");
    }

    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Converts lightweight WhatsApp bold/italic markers to plain readable text for email output. */
    public static String stripWhatsappMarkdown(String text) {
        if (text == null || text.isBlank()) return "";
        return text
                .replaceAll("\\*([^\\s*][^*]*?)\\*", "$1")
                .replaceAll("_([^\\s_][^_]*?)_", "$1");
    }

    public record ActionButton(String label, String url, String colorHex) {}

    /**
     * Renders a clean, mobile-friendly branded HTML card from resolved plain-text body lines
     * plus optional call-to-action buttons, ensuring Standard-mode authoring also yields crisp HTML emails.
     */
    public static String wrapInCardHtml(
            String headerStartHex,
            String headerEndHex,
            String badgeText,
            String title,
            String resolvedPlainBody,
            List<ActionButton> buttons,
            String footerText
    ) {
        String startHex = isSafeHexColor(headerStartHex) ? headerStartHex : "#0284c7";
        String endHex = isSafeHexColor(headerEndHex) ? headerEndHex : "#0369a1";
        String safeTitle = escapeHtml(title == null ? "" : title);
        String safeBadge = escapeHtml(badgeText == null ? "" : badgeText);
        String safeFooter = escapeHtml(footerText == null ? "" : footerText);
        String formattedBody = plainTextToCardHtml(stripWhatsappMarkdown(resolvedPlainBody == null ? "" : resolvedPlainBody));

        StringBuilder btnHtml = new StringBuilder();
        if (buttons != null && !buttons.isEmpty()) {
            btnHtml.append("<div style=\"margin-top: 22px; padding-top: 16px; border-top: 1px solid #e2e8f0;\">");
            for (ActionButton btn : buttons) {
                if (btn == null || btn.url() == null || btn.url().isBlank()) continue;
                String bg = isSafeHexColor(btn.colorHex()) ? btn.colorHex() : "#2563eb";
                btnHtml.append("<a href=\"").append(escapeHtml(btn.url())).append("\" target=\"_blank\" ")
                        .append("style=\"display: block; text-align: center; background-color: ").append(bg)
                        .append("; color: #ffffff !important; padding: 12px 18px; border-radius: 8px; font-weight: 600; font-size: 14px; text-decoration: none; margin-bottom: 10px;\">")
                        .append(escapeHtml(btn.label() == null ? "Open Link" : btn.label()))
                        .append("</a>");
            }
            btnHtml.append("</div>");
        }

        String badgeHtml = safeBadge.isBlank()
                ? ""
                : "<div style=\"display: inline-block; margin-top: 6px; background: rgba(255,255,255,0.2); padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600;\">" + safeBadge + "</div>";

        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="utf-8"></head>
                <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b;">
                  <div style="max-width: 620px; margin: 0 auto; background: #ffffff; border-radius: 12px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.05);">
                    <div style="background: linear-gradient(135deg, %s, %s); color: #ffffff; padding: 20px 24px;">
                      <h2 style="margin: 0; font-size: 19px; font-weight: 700;">%s</h2>
                      %s
                    </div>
                    <div style="padding: 24px; font-size: 14px; line-height: 1.6; color: #1e293b;">
                      %s
                      %s
                    </div>
                    <div style="background: #f8fafc; padding: 14px 24px; font-size: 12px; color: #64748b; text-align: center; border-top: 1px solid #e2e8f0;">
                      %s
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(startHex, endHex, safeTitle, badgeHtml, formattedBody, btnHtml.toString(), safeFooter);
    }

    private static String plainTextToCardHtml(String raw) {
        String[] lines = raw.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        StringBuilder listRows = new StringBuilder();
        boolean inTable = false;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                if (inTable) {
                    out.append(listRows).append("</table>");
                    listRows.setLength(0);
                    inTable = false;
                }
                continue;
            }
            if ((trimmed.startsWith("•") || trimmed.startsWith("- ")) && trimmed.contains(":")) {
                String cleanLine = trimmed.startsWith("•") ? trimmed.substring(1).trim() : trimmed.substring(2).trim();
                int colon = cleanLine.indexOf(':');
                String label = cleanLine.substring(0, colon).trim();
                String val = cleanLine.substring(colon + 1).trim();
                if (!inTable) {
                    listRows.append("<table style=\"width: 100%; border-collapse: collapse; margin: 12px 0; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0;\">");
                    inTable = true;
                }
                listRows.append("<tr>")
                        .append("<td style=\"padding: 8px 12px; font-size: 13px; color: #64748b; font-weight: 600; border-bottom: 1px solid #e2e8f0; width: 38%;\">")
                        .append(escapeHtml(label)).append("</td>")
                        .append("<td style=\"padding: 8px 12px; font-size: 13px; color: #0f172a; font-weight: 500; border-bottom: 1px solid #e2e8f0; word-break: break-word;\">")
                        .append(escapeHtml(val)).append("</td>")
                        .append("</tr>");
            } else {
                if (inTable) {
                    out.append(listRows).append("</table>");
                    listRows.setLength(0);
                    inTable = false;
                }
                out.append("<p style=\"margin: 0 0 10px 0;\">").append(escapeHtml(rawLine)).append("</p>");
            }
        }
        if (inTable) {
            out.append(listRows).append("</table>");
        }
        return out.toString();
    }

    private static boolean isSafeHexColor(String value) {
        return value != null && value.matches("^#[0-9A-Fa-f]{6}$");
    }
}
