package org.example.service;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import org.example.config.ConfigManager;
import org.example.util.BusinessClock;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Generates a company-branded Party Aging & Outstanding Statement PDF from live
 * Spring Boot / PostgreSQL invoice balances for WhatsApp & Email collection dispatch.
 */
public final class AgingStatementPdfService {
    static { org.example.util.ITextRuntimeSupport.configure(); }

    private static final DeviceRgb BRAND_BLUE = new DeviceRgb(15, 76, 129);
    private static final DeviceRgb PALE_BG = new DeviceRgb(241, 245, 249);
    private static final DeviceRgb ACCENT_RED = new DeviceRgb(185, 28, 28);
    private static final DeviceRgb ACCENT_AMBER = new DeviceRgb(180, 83, 9);
    private static final DeviceRgb ACCENT_GREEN = new DeviceRgb(21, 128, 61);
    private static final NumberFormat INR = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));

    private AgingStatementPdfService() {}

    public record InvoiceLine(
            String invoiceNo,
            String invoiceDate,
            long daysOverdue,
            String bucketLabel,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal balanceDue
    ) {}

    public record StatementPayload(
            Long partyId,
            String partyName,
            String partyType,
            String gstin,
            String phone,
            String email,
            String address,
            String asOfDate,
            BigDecimal totalOutstanding,
            BigDecimal bucket0To30,
            BigDecimal bucket31To60,
            BigDecimal bucket61To90,
            BigDecimal bucket90Plus,
            List<InvoiceLine> invoices
    ) {}

    public static Path generateTempStatement(StatementPayload payload) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "jasvi-collections");
        Files.createDirectories(dir);
        String safeName = (payload.partyName() == null ? "Party" : payload.partyName())
                .replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safeName.isBlank()) safeName = "Party";
        String dateStamp = BusinessClock.today().toString();
        Path target = dir.resolve("Outstanding_Statement_" + safeName + "_" + dateStamp + ".pdf");
        export(target, payload);
        return target;
    }

    public static void export(Path target, StatementPayload s) throws IOException {
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(target.toFile()));
             Document doc = new Document(pdf, PageSize.A4)) {
            doc.setMargins(28, 28, 28, 28);

            // 1. Company Header Banner
            Table banner = new Table(new float[]{3.2f, 2.0f}).useAllAvailableWidth();
            banner.addCell(bannerCell(
                    BrandingService.companyName() + "\n" + ConfigManager.get("company.tagline", "Account Statement"),
                    15, TextAlignment.LEFT));
            String asOfText = (s.asOfDate() != null && !s.asOfDate().isBlank())
                    ? BusinessClock.formatDate(s.asOfDate())
                    : BusinessClock.formatDate(BusinessClock.today());
            banner.addCell(bannerCell(
                    "OUTSTANDING STATEMENT\nAs of " + asOfText,
                    12, TextAlignment.RIGHT));
            doc.add(banner);

            String companyMeta = joinNonBlank("  |  ",
                    ConfigManager.get("company.address", ""),
                    ConfigManager.get("company.phone", ""),
                    ConfigManager.get("company.email", ""),
                    nonBlankPrefixed("GSTIN: ", ConfigManager.get("company.gstin", "")));
            if (!companyMeta.isBlank()) {
                doc.add(new Paragraph(companyMeta).setFontSize(8.5f).setFontColor(BRAND_BLUE).setMarginTop(4).setMarginBottom(10));
            }

            // 2. Party Identity Block
            Table partyBox = new Table(new float[]{1.2f, 3.8f}).useAllAvailableWidth().setMarginBottom(12);
            partyBox.addCell(metaLabelCell("Account / Party")).addCell(metaValueCell(nonEmpty(s.partyName(), "Party")));
            if (!clean(s.gstin()).isBlank()) {
                partyBox.addCell(metaLabelCell("Party GSTIN")).addCell(metaValueCell(s.gstin().trim()));
            }
            String contact = joinNonBlank("  |  ",
                    nonBlankPrefixed("Phone: ", s.phone()),
                    nonBlankPrefixed("Email: ", s.email()));
            if (!contact.isBlank()) {
                partyBox.addCell(metaLabelCell("Contact")).addCell(metaValueCell(contact));
            }
            if (!clean(s.address()).isBlank()) {
                partyBox.addCell(metaLabelCell("Address")).addCell(metaValueCell(s.address().trim()));
            }
            doc.add(partyBox);

            // 3. Aging Bucket Summary Strip
            Table summary = new Table(new float[]{1, 1, 1, 1, 1.2f}).useAllAvailableWidth().setMarginBottom(12);
            summary.addHeaderCell(summaryHeader("0-30 Days"));
            summary.addHeaderCell(summaryHeader("31-60 Days"));
            summary.addHeaderCell(summaryHeader("61-90 Days"));
            summary.addHeaderCell(summaryHeader("90+ Days"));
            summary.addHeaderCell(summaryHeader("Total Outstanding"));
            summary.addCell(summaryAmount(s.bucket0To30(), ACCENT_GREEN));
            summary.addCell(summaryAmount(s.bucket31To60(), ACCENT_AMBER));
            summary.addCell(summaryAmount(s.bucket61To90(), ACCENT_AMBER));
            summary.addCell(summaryAmount(s.bucket90Plus(), ACCENT_RED));
            summary.addCell(summaryAmount(s.totalOutstanding(), BRAND_BLUE));
            doc.add(summary);

            // 4. Itemized Unpaid / Partial Invoices Table
            doc.add(new Paragraph("Unpaid & Partially Paid Invoices").setBold().setFontSize(10.5f).setFontColor(BRAND_BLUE).setMarginBottom(4));
            Table table = new Table(new float[]{1.8f, 1.2f, 0.8f, 1.1f, 1.3f, 1.2f, 1.4f}).useAllAvailableWidth();
            for (String h : new String[]{"Invoice #", "Date", "Days", "Bucket", "Invoice Amt", "Paid", "Balance Due"}) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setBold().setFontSize(8.5f))
                        .setBackgroundColor(BRAND_BLUE).setFontColor(ColorConstants.WHITE)
                        .setPadding(5).setTextAlignment(TextAlignment.CENTER));
            }
            boolean shaded = false;
            List<InvoiceLine> lines = s.invoices() == null ? List.of() : s.invoices();
            for (InvoiceLine line : lines) {
                addRowCell(table, nonEmpty(line.invoiceNo(), "-"), TextAlignment.LEFT, shaded);
                addRowCell(table, line.invoiceDate() != null && !line.invoiceDate().isBlank() ? BusinessClock.formatDate(line.invoiceDate()) : "-", TextAlignment.CENTER, shaded);
                addRowCell(table, line.daysOverdue() + "d", TextAlignment.CENTER, shaded);
                addRowCell(table, nonEmpty(line.bucketLabel(), "-"), TextAlignment.CENTER, shaded);
                addRowCell(table, money(line.totalAmount()), TextAlignment.RIGHT, shaded);
                addRowCell(table, money(line.paidAmount()), TextAlignment.RIGHT, shaded);
                addRowCell(table, money(line.balanceDue()), TextAlignment.RIGHT, shaded);
                shaded = !shaded;
            }
            if (lines.isEmpty()) {
                table.addCell(new Cell(1, 7).add(new Paragraph("No itemized open invoices found for this account.").setFontSize(9))
                        .setTextAlignment(TextAlignment.CENTER).setPadding(12));
            }
            doc.add(table);

            // 5. Payment Bank / UPI Details Footer (authentic settings only)
            String bankLine = joinNonBlank("  |  ",
                    nonBlankPrefixed("Bank: ", ConfigManager.get("payment.bankName", "")),
                    nonBlankPrefixed("A/C: ", ConfigManager.get("payment.accountNumber", "")),
                    nonBlankPrefixed("IFSC: ", ConfigManager.get("payment.ifsc", "")),
                    nonBlankPrefixed("UPI: ", ConfigManager.get("payment.upiId", "")));
            if (!bankLine.isBlank()) {
                doc.add(new Paragraph("\nRemittance Details: " + bankLine).setBold().setFontSize(8.5f).setFontColor(BRAND_BLUE));
            }
            doc.add(new Paragraph("Generated by " + BrandingService.companyName() + " on " + BusinessClock.formatDate(BusinessClock.today())
                    + ". If payment has already been made, please ignore this statement or share payment reference.")
                    .setFontSize(8f).setMarginTop(8));
        }
    }

    private static Cell bannerCell(String text, float fontSize, TextAlignment align) {
        return new Cell().add(new Paragraph(text).setBold().setFontSize(fontSize))
                .setBackgroundColor(BRAND_BLUE).setFontColor(ColorConstants.WHITE)
                .setPadding(10).setBorder(null).setTextAlignment(align);
    }

    private static Cell metaLabelCell(String label) {
        return new Cell().add(new Paragraph(label).setBold().setFontSize(8.5f).setFontColor(BRAND_BLUE))
                .setBackgroundColor(PALE_BG).setPadding(5);
    }

    private static Cell metaValueCell(String value) {
        return new Cell().add(new Paragraph(value).setFontSize(9f)).setPadding(5);
    }

    private static Cell summaryHeader(String label) {
        return new Cell().add(new Paragraph(label).setBold().setFontSize(8.5f))
                .setBackgroundColor(PALE_BG).setFontColor(BRAND_BLUE)
                .setPadding(5).setTextAlignment(TextAlignment.CENTER);
    }

    private static Cell summaryAmount(BigDecimal value, DeviceRgb color) {
        return new Cell().add(new Paragraph(money(value)).setBold().setFontSize(9.5f).setFontColor(color))
                .setPadding(6).setTextAlignment(TextAlignment.CENTER);
    }

    private static void addRowCell(Table table, String text, TextAlignment align, boolean shaded) {
        Cell cell = new Cell().add(new Paragraph(text).setFontSize(8.5f)).setPadding(5).setTextAlignment(align);
        if (shaded) cell.setBackgroundColor(PALE_BG);
        table.addCell(cell);
    }

    private static String money(BigDecimal value) {
        return INR.format(value == null ? BigDecimal.ZERO : value);
    }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }

    private static String nonEmpty(String v, String fallback) {
        return clean(v).isBlank() ? fallback : v.trim();
    }

    private static String nonBlankPrefixed(String prefix, String value) {
        return clean(value).isBlank() ? "" : prefix + value.trim();
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) sb.append(delimiter);
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }
}
