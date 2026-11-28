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

    public record MetaColumn(String label, String value, String valueColorHex) {
        public MetaColumn(String label, String value) {
            this(label, value, "#0f172a");
        }
    }

    public record KpiTile(String label, String value, String note, String valueColorHex) {
        public KpiTile(String label, String value, String valueColorHex) {
            this(label, value, "", valueColorHex);
        }
    }

    public record KeyValueRow(String label, String value, boolean monospace, String badgeColorHex, String badgeBgHex) {
        public KeyValueRow(String label, String value) {
            this(label, value, false, null, null);
        }

        public KeyValueRow(String label, String value, boolean monospace) {
            this(label, value, monospace, null, null);
        }
    }

    public record TableColumn(String label, boolean rightAlign, String widthPercent) {
        public TableColumn(String label, boolean rightAlign) {
            this(label, rightAlign, "");
        }
    }

    public record RichCardSpec(
            String headerStartHex,
            String headerEndHex,
            String kickerText,
            String badgeText,
            String badgeColorHex,
            String title,
            String subtitle,
            String preheader,
            List<MetaColumn> metaColumns,
            String resolvedPlainBody,
            List<String> sectionHtmlBlocks,
            List<ActionButton> buttons,
            String footerTitle,
            String footerMeta
    ) {}

    public static String wrapInCardHtml(
            String headerStartHex,
            String headerEndHex,
            String badgeText,
            String title,
            String resolvedPlainBody,
            List<ActionButton> buttons,
            String footerText
    ) {
        String preheader = extractPreheader(resolvedPlainBody, title);
        return wrapInRichCardHtml(new RichCardSpec(
                headerStartHex,
                headerEndHex,
                "",
                badgeText,
                "#0f172a",
                title,
                "",
                preheader,
                List.of(),
                resolvedPlainBody,
                List.of(),
                buttons,
                footerText,
                ""
        ));
    }

    /**
     * Renders an Outlook Desktop, Gmail, and mobile-safe table-based HTML email card
     * with hidden inbox preheader, header banner, metadata strip, body blocks,
     * pre-escaped structured HTML sections, CTA buttons, and branded footer.
     */
    public static String wrapInRichCardHtml(RichCardSpec spec) {
        if (spec == null) {
            return "";
        }
        String startHex = safeHexColor(spec.headerStartHex(), "#0284c7");
        String endHex = safeHexColor(spec.headerEndHex(), "#0369a1");
        String badgeBg = safeHexColor(spec.badgeColorHex(), endHex);
        String safeKicker = escapeHtml(nonNull(spec.kickerText()));
        String safeTitle = escapeHtml(nonNull(spec.title()));
        String safeSubtitle = escapeHtml(nonNull(spec.subtitle()));
        String safeBadge = escapeHtml(nonNull(spec.badgeText()));
        String safePreheader = escapeHtml(nonNull(spec.preheader()));
        String formattedBody = plainTextToCardHtml(stripWhatsappMarkdown(nonNull(spec.resolvedPlainBody())));

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("</head><body style=\"margin:0; padding:0; background-color:#f1f5f9; font-family:-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color:#1e293b;\">");

        if (!safePreheader.isBlank()) {
            html.append("<div style=\"display:none; font-size:1px; color:#f1f5f9; line-height:1px; max-height:0px; max-width:0px; opacity:0; overflow:hidden; mso-hide:all;\">")
                    .append(safePreheader)
                    .append("&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;&#847;&zwnj;&nbsp;")
                    .append("</div>");
        }

        html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%; background-color:#f1f5f9;\">")
                .append("<tr><td align=\"center\" style=\"padding:20px 12px;\">")
                .append("<table role=\"presentation\" width=\"640\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%; max-width:640px; background-color:#ffffff; border-radius:12px; border:1px solid #e2e8f0; overflow:hidden;\">");

        // Header banner (table layout works in Outlook Desktop + Gmail + Mobile)
        html.append("<tr><td bgcolor=\"").append(startHex).append("\" style=\"background-color:").append(startHex)
                .append("; background:linear-gradient(135deg, ").append(startHex).append(" 0%, ").append(endHex).append(" 100%); padding:20px 24px; color:#ffffff;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                .append("<td style=\"vertical-align:middle; text-align:left;\">");
        if (!safeKicker.isBlank()) {
            html.append("<div style=\"font-size:10px; font-weight:700; letter-spacing:1px; text-transform:uppercase; color:#e2e8f0; margin-bottom:4px;\">")
                    .append(safeKicker).append("</div>");
        }
        html.append("<h2 style=\"margin:0; font-size:20px; line-height:1.25; font-weight:700; color:#ffffff;\">").append(safeTitle).append("</h2>");
        if (!safeSubtitle.isBlank()) {
            html.append("<div style=\"font-size:12px; color:#e2e8f0; margin-top:4px; letter-spacing:0.2px;\">").append(safeSubtitle).append("</div>");
        }
        html.append("</td>");
        if (!safeBadge.isBlank()) {
            html.append("<td style=\"vertical-align:middle; text-align:right; padding-left:12px; white-space:nowrap;\">")
                    .append("<span style=\"display:inline-block; background-color:").append(badgeBg)
                    .append("; border:1px solid #cbd5e1; color:#ffffff; padding:5px 12px; border-radius:9999px; font-size:11px; font-weight:700; letter-spacing:0.4px; text-transform:uppercase;\">")
                    .append(safeBadge).append("</span></td>");
        }
        html.append("</tr></table></td></tr>");

        // Optional metadata summary bar
        if (spec.metaColumns() != null && !spec.metaColumns().isEmpty()) {
            html.append("<tr><td style=\"padding:0;\">").append(headerMetaBarHtml(spec.metaColumns())).append("</td></tr>");
        }

        // Main content cell
        html.append("<tr><td style=\"padding:22px 24px; font-size:14px; line-height:1.6; color:#334155;\">");
        if (!formattedBody.isBlank()) {
            html.append(formattedBody);
        }
        if (spec.sectionHtmlBlocks() != null) {
            for (String block : spec.sectionHtmlBlocks()) {
                if (block != null && !block.isBlank()) {
                    html.append(block);
                }
            }
        }

        // Action CTA buttons (HTTP/HTTPS only)
        if (spec.buttons() != null && !spec.buttons().isEmpty()) {
            StringBuilder validButtons = new StringBuilder();
            for (ActionButton btn : spec.buttons()) {
                if (btn == null || !isSafeHttpUrl(btn.url())) {
                    continue;
                }
                String btnHex = safeHexColor(btn.colorHex(), "#2563eb");
                String label = escapeHtml(btn.label() == null || btn.label().isBlank() ? "Open Link" : btn.label());
                String safeUrl = escapeHtml(btn.url().trim());
                validButtons.append("<tr><td align=\"center\" style=\"padding-bottom:8px;\">")
                        .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                        .append("<td align=\"center\" bgcolor=\"").append(btnHex).append("\" style=\"background-color:").append(btnHex).append("; border-radius:8px;\">")
                        .append("<a href=\"").append(safeUrl).append("\" target=\"_blank\" style=\"display:block; padding:12px 18px; color:#ffffff; font-weight:700; font-size:13px; text-decoration:none; letter-spacing:0.2px;\">")
                        .append(label).append("</a></td></tr></table></td></tr>");
            }
            if (!validButtons.isEmpty()) {
                html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:18px; padding-top:14px; border-top:1px solid #e2e8f0;\">")
                        .append(validButtons)
                        .append("</table>");
            }
        }

        html.append("</td></tr>");

        // Branded footer
        String safeFooterTitle = escapeHtml(nonNull(spec.footerTitle()));
        String safeFooterMeta = escapeHtml(nonNull(spec.footerMeta()));
        if (!safeFooterTitle.isBlank() || !safeFooterMeta.isBlank()) {
            html.append("<tr><td bgcolor=\"#f8fafc\" style=\"background-color:#f8fafc; border-top:1px solid #e2e8f0; padding:14px 24px; text-align:center;\">");
            if (!safeFooterTitle.isBlank()) {
                html.append("<div style=\"font-size:12px; font-weight:600; color:#475569;\">").append(safeFooterTitle).append("</div>");
            }
            if (!safeFooterMeta.isBlank()) {
                html.append("<div style=\"font-size:11px; color:#64748b; margin-top:4px;\">").append(safeFooterMeta).append("</div>");
            }
            html.append("</td></tr>");
        }

        html.append("</table></td></tr></table></body></html>");
        return html.toString();
    }

    public static String headerMetaBarHtml(List<MetaColumn> columns) {
        if (columns == null || columns.isEmpty()) return "";
        int pct = Math.max(20, 100 / columns.size());
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#f8fafc; border-bottom:1px solid #e2e8f0;\"><tr>");
        for (int i = 0; i < columns.size(); i++) {
            MetaColumn col = columns.get(i);
            String valHex = safeHexColor(col.valueColorHex(), "#0f172a");
            String borderLeft = i > 0 ? " border-left:1px solid #e2e8f0;" : "";
            sb.append("<td width=\"").append(pct).append("%\" style=\"width:").append(pct).append("%; padding:12px 16px; vertical-align:top;").append(borderLeft).append("\">")
                    .append("<div style=\"font-size:10px; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.5px;\">")
                    .append(escapeHtml(nonNull(col.label()))).append("</div>")
                    .append("<div style=\"font-size:13px; font-weight:700; color:").append(valHex).append("; margin-top:3px; word-break:break-word;\">")
                    .append(escapeHtml(nonNull(col.value()))).append("</div>")
                    .append("</td>");
        }
        sb.append("</tr></table>");
        return sb.toString();
    }

    public static String kpiGridHtml(String sectionTitle, List<KpiTile> tiles, int colsPerRow) {
        if (tiles == null || tiles.isEmpty()) return "";
        int cols = Math.max(1, Math.min(4, colsPerRow));
        int pct = 100 / cols;
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0;\">");
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            sb.append("<div style=\"font-size:11px; font-weight:700; color:#475569; text-transform:uppercase; letter-spacing:0.6px; margin-bottom:8px;\">")
                    .append(escapeHtml(sectionTitle)).append("</div>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"6\" border=\"0\" style=\"width:100%; border-collapse:separate; border-spacing:6px; margin:-6px;\">");
        for (int i = 0; i < tiles.size(); i += cols) {
            sb.append("<tr>");
            for (int c = 0; c < cols; c++) {
                int idx = i + c;
                if (idx < tiles.size()) {
                    KpiTile tile = tiles.get(idx);
                    String valHex = safeHexColor(tile.valueColorHex(), "#0f172a");
                    sb.append("<td width=\"").append(pct).append("%\" style=\"width:").append(pct)
                            .append("%; vertical-align:top; background-color:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:11px 13px;\">")
                            .append("<div style=\"font-size:10px; font-weight:700; color:#64748b; text-transform:uppercase; letter-spacing:0.4px;\">")
                            .append(escapeHtml(nonNull(tile.label()))).append("</div>")
                            .append("<div style=\"font-size:16px; font-weight:700; color:").append(valHex).append("; margin-top:4px; word-break:break-word;\">")
                            .append(escapeHtml(nonNull(tile.value()))).append("</div>");
                    if (tile.note() != null && !tile.note().isBlank()) {
                        sb.append("<div style=\"font-size:11px; color:#475569; margin-top:3px;\">")
                                .append(escapeHtml(tile.note())).append("</div>");
                    }
                    sb.append("</td>");
                } else {
                    sb.append("<td width=\"").append(pct).append("%\"></td>");
                }
            }
            sb.append("</tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    public static String keyValueSectionHtml(String sectionTitle, String accentHex, List<KeyValueRow> rows) {
        if (rows == null || rows.isEmpty()) return "";
        String accent = safeHexColor(accentHex, "#1e3a8a");
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0; border:1px solid #e2e8f0; border-radius:8px; overflow:hidden; background-color:#ffffff;\">");
        if (sectionTitle != null && !sectionTitle.isBlank()) {
            sb.append("<div style=\"background-color:#f1f5f9; padding:9px 14px; border-bottom:1px solid #e2e8f0; font-size:11px; font-weight:700; color:")
                    .append(accent).append("; text-transform:uppercase; letter-spacing:0.5px;\">")
                    .append(escapeHtml(sectionTitle)).append("</div>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%; border-collapse:collapse;\">");
        for (int i = 0; i < rows.size(); i++) {
            KeyValueRow row = rows.get(i);
            String border = i + 1 < rows.size() ? " border-bottom:1px solid #f1f5f9;" : "";
            String rowBg = (i % 2 == 1) ? " background-color:#fafbfc;" : "";
            sb.append("<tr style=\"").append(rowBg).append("\">")
                    .append("<td style=\"padding:8px 14px; width:38%; font-size:12px; font-weight:600; color:#64748b;").append(border).append("\">")
                    .append(escapeHtml(nonNull(row.label()))).append("</td>")
                    .append("<td style=\"padding:8px 14px; font-size:13px; color:#0f172a; word-break:break-word;").append(border).append("\">");
            String escapedVal = escapeHtml(nonNull(row.value()));
            if (row.badgeColorHex() != null || row.badgeBgHex() != null) {
                String fg = safeHexColor(row.badgeColorHex(), "#1e293b");
                String bg = safeHexColor(row.badgeBgHex(), "#f1f5f9");
                sb.append("<span style=\"display:inline-block; background-color:").append(bg)
                        .append("; color:").append(fg).append("; border:1px solid #cbd5e1; padding:2px 8px; border-radius:4px; font-size:12px; font-weight:700;")
                        .append(row.monospace() ? " font-family:ui-monospace, Consolas, monospace;" : "")
                        .append("\">").append(escapedVal).append("</span>");
            } else if (row.monospace()) {
                sb.append("<span style=\"font-family:ui-monospace, Consolas, monospace; font-weight:700; background-color:#f1f5f9; border:1px solid #cbd5e1; padding:2px 8px; border-radius:4px; font-size:12px; color:#334155;\">")
                        .append(escapedVal).append("</span>");
            } else {
                sb.append("<strong>").append(escapedVal).append("</strong>");
            }
            sb.append("</td></tr>");
        }
        sb.append("</table></div>");
        return sb.toString();
    }

    public static String dataTableHtml(
            String sectionTitle,
            String badgeRight,
            List<TableColumn> columns,
            List<List<String>> rows,
            String footerNote
    ) {
        if (columns == null || columns.isEmpty() || rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0; border:1px solid #e2e8f0; border-radius:8px; overflow:hidden; background-color:#ffffff;\">");
        if ((sectionTitle != null && !sectionTitle.isBlank()) || (badgeRight != null && !badgeRight.isBlank())) {
            sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color:#f1f5f9; border-bottom:1px solid #e2e8f0;\"><tr>")
                    .append("<td style=\"padding:9px 14px; font-size:11px; font-weight:700; color:#1e3a8a; text-transform:uppercase; letter-spacing:0.5px;\">")
                    .append(escapeHtml(nonNull(sectionTitle))).append("</td>")
                    .append("<td style=\"padding:9px 14px; text-align:right; font-size:11px; font-weight:600; color:#475569;\">")
                    .append(escapeHtml(nonNull(badgeRight))).append("</td></tr></table>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%; border-collapse:collapse;\"><thead><tr style=\"background-color:#f8fafc;\">");
        for (TableColumn col : columns) {
            String align = col.rightAlign() ? "right" : "left";
            String wStyle = col.widthPercent() != null && !col.widthPercent().isBlank()
                    ? " width:" + escapeHtml(col.widthPercent()) + ";" : "";
            sb.append("<th style=\"padding:8px 10px; font-size:11px; font-weight:700; color:#475569; text-align:")
                    .append(align).append("; border-bottom:1px solid #cbd5e1;").append(wStyle).append("\">")
                    .append(escapeHtml(nonNull(col.label()))).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String bg = (r % 2 == 1) ? " background-color:#f8fafc;" : "";
            String border = r + 1 < rows.size() ? " border-bottom:1px solid #f1f5f9;" : "";
            sb.append("<tr style=\"").append(bg).append("\">");
            for (int c = 0; c < columns.size(); c++) {
                TableColumn col = columns.get(c);
                String val = c < row.size() ? nonNull(row.get(c)) : "";
                String align = col.rightAlign() ? "right" : "left";
                String weight = col.rightAlign() ? " font-weight:600; color:#0f172a;" : " color:#1e293b;";
                sb.append("<td style=\"padding:8px 10px; font-size:12px; text-align:").append(align)
                        .append(";").append(weight).append(border).append(" word-break:break-word;\">")
                        .append(escapeHtml(val)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        if (footerNote != null && !footerNote.isBlank()) {
            sb.append("<div style=\"background-color:#f8fafc; padding:8px 12px; border-top:1px solid #e2e8f0; font-size:11px; color:#64748b;\">")
                    .append(escapeHtml(footerNote)).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    public static String calloutBoxHtml(
            String eyebrow,
            String codeValue,
            String helperText,
            String borderHex,
            String bgHex,
            String textHex
    ) {
        String border = safeHexColor(borderHex, "#2563eb");
        String bg = safeHexColor(bgHex, "#eff6ff");
        String fg = safeHexColor(textHex, "#1e3a8a");
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0; padding:18px 16px; text-align:center; background-color:")
                .append(bg).append("; border:2px dashed ").append(border).append("; border-radius:10px;\">");
        if (eyebrow != null && !eyebrow.isBlank()) {
            sb.append("<div style=\"font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:0.8px; color:#475569; margin-bottom:6px;\">")
                    .append(escapeHtml(eyebrow)).append("</div>");
        }
        sb.append("<div style=\"font-family:ui-monospace, Consolas, monospace; font-size:26px; font-weight:800; letter-spacing:4px; color:")
                .append(fg).append("; word-break:break-all;\">").append(escapeHtml(nonNull(codeValue))).append("</div>");
        if (helperText != null && !helperText.isBlank()) {
            sb.append("<div style=\"font-size:12px; color:#475569; margin-top:8px;\">")
                    .append(escapeHtml(helperText)).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    public static String progressBarHtml(String label, double percent, String rightLabel, String fillHex) {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        int fillPct = (int) Math.round(clamped);
        String fill = safeHexColor(fillHex, fillPct >= 85 ? "#dc2626" : (fillPct >= 75 ? "#d97706" : "#0284c7"));
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:14px 0; padding:12px 14px; background-color:#f8fafc; border:1px solid #e2e8f0; border-radius:8px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                .append("<td style=\"font-size:12px; font-weight:700; color:#334155;\">").append(escapeHtml(nonNull(label))).append("</td>")
                .append("<td style=\"font-size:12px; font-weight:700; color:").append(fill).append("; text-align:right;\">")
                .append(escapeHtml(nonNull(rightLabel))).append("</td></tr></table>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin-top:8px; background-color:#e2e8f0; border-radius:9999px; overflow:hidden; height:12px;\"><tr>");
        if (fillPct > 0) {
            sb.append("<td width=\"").append(fillPct).append("%\" bgcolor=\"").append(fill)
                    .append("\" style=\"width:").append(fillPct).append("%; background-color:").append(fill).append("; height:12px; font-size:1px; line-height:1px;\">&nbsp;</td>");
        }
        if (fillPct < 100) {
            sb.append("<td width=\"").append(100 - fillPct).append("%\" style=\"width:").append(100 - fillPct)
                    .append("%; height:12px; font-size:1px; line-height:1px;\">&nbsp;</td>");
        }
        sb.append("</tr></table></div>");
        return sb.toString();
    }

    public static String infoSplitPanelHtml(
            String cardHeading,
            String leftTitle,
            String leftColorHex,
            List<KeyValueRow> leftRows,
            String rightTitle,
            String rightColorHex,
            List<KeyValueRow> rightRows
    ) {
        String lColor = safeHexColor(leftColorHex, "#1e3a8a");
        String rColor = safeHexColor(rightColorHex, "#15803d");
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0; background-color:#f8fafc; border:1px solid #cbd5e1; border-radius:8px; overflow:hidden;\">");
        if (cardHeading != null && !cardHeading.isBlank()) {
            sb.append("<div style=\"background-color:#f1f5f9; padding:9px 14px; border-bottom:1px solid #cbd5e1; font-size:12px; font-weight:700; color:#0f172a; text-transform:uppercase; letter-spacing:0.4px;\">")
                    .append(escapeHtml(cardHeading)).append("</div>");
        }
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>")
                .append("<td width=\"50%\" style=\"width:50%; padding:14px 16px; vertical-align:top;\">")
                .append("<div style=\"font-size:12px; font-weight:700; color:").append(lColor).append("; margin-bottom:6px;\">")
                .append(escapeHtml(nonNull(leftTitle))).append("</div>");
        if (leftRows != null) {
            for (KeyValueRow r : leftRows) {
                sb.append("<div style=\"font-size:12px; color:#334155; margin-bottom:3px;\">")
                        .append(escapeHtml(nonNull(r.label()))).append(": <strong>").append(escapeHtml(nonNull(r.value()))).append("</strong></div>");
            }
        }
        sb.append("</td>")
                .append("<td width=\"50%\" style=\"width:50%; padding:14px 16px; vertical-align:top; border-left:1px solid #cbd5e1;\">")
                .append("<div style=\"font-size:12px; font-weight:700; color:").append(rColor).append("; margin-bottom:6px;\">")
                .append(escapeHtml(nonNull(rightTitle))).append("</div>");
        if (rightRows != null) {
            for (KeyValueRow r : rightRows) {
                sb.append("<div style=\"font-size:12px; color:#334155; margin-bottom:3px;\">")
                        .append(escapeHtml(nonNull(r.label()))).append(": <strong>").append(escapeHtml(nonNull(r.value()))).append("</strong></div>");
            }
        }
        sb.append("</td></tr></table></div>");
        return sb.toString();
    }

    public static String inlineImageCardHtml(
            String heading,
            String caption,
            String imgSrc,
            int maxWidthPx,
            int maxHeightPx
    ) {
        if (!isSafeImageSource(imgSrc)) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin:16px 0; padding:14px 16px; background-color:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; text-align:center;\">");
        if (heading != null && !heading.isBlank()) {
            sb.append("<div style=\"font-size:11px; font-weight:700; color:#475569; text-transform:uppercase; letter-spacing:0.5px; margin-bottom:8px;\">")
                    .append(escapeHtml(heading)).append("</div>");
        }
        sb.append("<img src=\"").append(escapeHtml(imgSrc.trim())).append("\" alt=\"")
                .append(escapeHtml(heading == null || heading.isBlank() ? "Embedded image" : heading))
                .append("\" style=\"max-width:").append(Math.max(60, maxWidthPx))
                .append("px; max-height:").append(Math.max(60, maxHeightPx))
                .append("px; display:block; margin:0 auto; border:1px solid #e2e8f0; border-radius:6px; background-color:#ffffff; padding:4px;\" />");
        if (caption != null && !caption.isBlank()) {
            sb.append("<div style=\"font-size:11px; color:#64748b; margin-top:8px;\">")
                    .append(escapeHtml(caption)).append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    public static boolean isSafeHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String trimmed = url.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("https://") || trimmed.startsWith("http://");
    }

    public static boolean isSafeImageSource(String src) {
        if (src == null || src.isBlank()) return false;
        String lower = src.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("cid:")
                || lower.startsWith("data:image/png;base64,")
                || lower.startsWith("data:image/jpeg;base64,")
                || lower.startsWith("data:image/jpg;base64,")
                || lower.startsWith("file:/");
    }

    private static final Pattern SIGNATURE_CONTACT_PATTERN = Pattern.compile(
            "([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})"
                    + "|((?:\\+\\d{1,4}[\\s-]?|\\b)\\d[\\d\\s-]{7,15}\\d\\b)"
    );

    /**
     * Renders an email-safe HTML signature block in manual executive layout order:
     * 1) Signature text lines (with safe mailto/tel links),
     * 2) Wide/readable inline signature or banner image,
     * 3) Optional office address line directly beneath the banner image.
     */
    public static String signatureBlockHtml(String signatureText, String imageSrc, String officeAddress) {
        String cleanSig = signatureText == null ? "" : signatureText.trim();
        String cleanImg = imageSrc == null ? "" : imageSrc.trim();
        boolean hasImage = !cleanImg.isBlank() && isSafeImageSource(cleanImg);
        String cleanAddr = officeAddress == null ? "" : officeAddress.trim();
        if (cleanSig.isBlank() && !hasImage && cleanAddr.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin-top:20px; padding-top:14px; border-top:1px solid #e2e8f0; ")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;\">");

        if (!cleanSig.isBlank()) {
            String[] lines = cleanSig.split("\\r?\\n", -1);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    sb.append("<div style=\"height:6px; line-height:6px; font-size:1px;\">&nbsp;</div>");
                    continue;
                }
                sb.append("<div style=\"margin:0 0 2px 0; font-size:13px; line-height:1.55; color:#1e3a5f;\">")
                        .append(linkifySignatureLineHtml(line.stripTrailing()))
                        .append("</div>");
            }
        }

        if (hasImage) {
            sb.append("<div style=\"margin:").append(cleanSig.isBlank() ? "2px" : "10px").append(" 0 8px 0;\">")
                    .append("<img src=\"").append(escapeHtml(cleanImg))
                    .append("\" alt=\"Authorized Signature\" ")
                    .append("style=\"width:auto; max-width:100%; max-height:115px; height:auto; display:block; border:none;\" />")
                    .append("</div>");
        }

        if (!cleanAddr.isBlank()) {
            String strippedAddr = cleanAddr.replaceFirst("(?i)^office\\s*address\\s*:\\s*-?\\s*", "").trim();
            String sigLower = cleanSig.toLowerCase(Locale.ROOT);
            boolean alreadyIncluded = !strippedAddr.isBlank() && sigLower.contains(strippedAddr.toLowerCase(Locale.ROOT));
            if (!alreadyIncluded && !strippedAddr.isBlank()) {
                sb.append("<div style=\"margin:6px 0 0 0; font-size:12.5px; line-height:1.5; color:#1e3a5f;\">")
                        .append("<strong style=\"color:#0f2942;\">Office Address:</strong> - ")
                        .append(escapeHtml(strippedAddr))
                        .append("</div>");
            }
        }

        sb.append("</div>");
        return sb.toString();
    }

    public static String linkifySignatureLineHtml(String line) {
        if (line == null || line.isEmpty()) return "";
        Matcher m = SIGNATURE_CONTACT_PATTERN.matcher(line);
        StringBuilder out = new StringBuilder(line.length() + 32);
        int cursor = 0;
        while (m.find()) {
            out.append(preserveSpacingHtml(line.substring(cursor, m.start())));
            String email = m.group(1);
            String phone = m.group(2);
            if (email != null) {
                String safeEmail = escapeHtml(email);
                out.append("<a href=\"mailto:").append(safeEmail)
                        .append("\" style=\"color:#1d4ed8; text-decoration:underline;\">")
                        .append(safeEmail).append("</a>");
            } else if (phone != null) {
                String digits = phone.replaceAll("[^+0-9]", "");
                int digitCount = phone.replaceAll("[^0-9]", "").length();
                if (digitCount >= 10 || (digits.startsWith("+") && digitCount >= 8)) {
                    out.append("<a href=\"tel:").append(escapeHtml(digits))
                            .append("\" style=\"color:#1d4ed8; text-decoration:underline;\">")
                            .append(escapeHtml(phone)).append("</a>");
                } else {
                    out.append(escapeHtml(phone));
                }
            }
            cursor = m.end();
        }
        out.append(preserveSpacingHtml(line.substring(cursor)));
        return out.toString();
    }

    private static String preserveSpacingHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        return escapeHtml(text).replace("  ", " &nbsp;");
    }

    private static String extractPreheader(String plainBody, String fallbackTitle) {
        if (plainBody != null && !plainBody.isBlank()) {
            for (String line : plainBody.split("\\r?\\n")) {
                String clean = stripWhatsappMarkdown(line).replaceAll("^[•\\-*]\\s*", "").trim();
                if (clean.length() >= 12) {
                    return clean.length() > 140 ? clean.substring(0, 137) + "..." : clean;
                }
            }
        }
        return nonNull(fallbackTitle);
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
                    listRows.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:100%; border-collapse:collapse; margin:12px 0; background-color:#f8fafc; border-radius:8px; border:1px solid #e2e8f0;\">");
                    inTable = true;
                }
                String labelLower = label.toLowerCase(Locale.ROOT);
                boolean isCodeChip = labelLower.contains("ticket") || labelLower.contains("reference")
                        || labelLower.contains("utr") || labelLower.contains("machine")
                        || labelLower.contains("manual setup") || labelLower.contains("temporary");
                String renderedVal = isCodeChip
                        ? "<span style=\"font-family:ui-monospace, Consolas, monospace; font-weight:700; background-color:#eef2ff; border:1px solid #c7d2fe; padding:2px 8px; border-radius:4px; font-size:12px; color:#1e3a8a;\">" + escapeHtml(val) + "</span>"
                        : escapeHtml(val);
                listRows.append("<tr>")
                        .append("<td style=\"padding:8px 12px; font-size:13px; color:#64748b; font-weight:600; border-bottom:1px solid #e2e8f0; width:38%;\">")
                        .append(escapeHtml(label)).append("</td>")
                        .append("<td style=\"padding:8px 12px; font-size:13px; color:#0f172a; font-weight:600; border-bottom:1px solid #e2e8f0; word-break:break-word;\">")
                        .append(renderedVal).append("</td>")
                        .append("</tr>");
            } else {
                if (inTable) {
                    out.append(listRows).append("</table>");
                    listRows.setLength(0);
                    inTable = false;
                }
                out.append("<p style=\"margin:0 0 10px 0;\">").append(escapeHtml(rawLine)).append("</p>");
            }
        }
        if (inTable) {
            out.append(listRows).append("</table>");
        }
        return out.toString();
    }

    private static String safeHexColor(String value, String fallback) {
        return isSafeHexColor(value) ? value.trim() : fallback;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static boolean isSafeHexColor(String value) {
        return value != null && value.trim().matches("^#[0-9A-Fa-f]{6}$");
    }
}
