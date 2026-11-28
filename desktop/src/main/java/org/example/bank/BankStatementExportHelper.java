package org.example.bank;

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
import javafx.scene.Node;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.api.bank.BankStatementApiClient;
import org.example.config.ConfigManager;
import org.example.service.BrandedRegisterPdfService;
import org.example.service.BrandingService;
import org.example.shared.BankNarrationParser;
import org.example.util.AppDialogService;
import org.example.util.BusinessClock;
import org.example.util.ExportFeedbackManager;
import org.example.util.ITextRuntimeSupport;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exports filtered Bank Statement views and CA-compliant month-end Bank Reconciliation Statements (BRS)
 * in Excel (.xlsx) and PDF (.pdf) formats.
 */
public final class BankStatementExportHelper {

    static {
        ITextRuntimeSupport.configure();
    }

    private static final DeviceRgb BRAND_BLUE = new DeviceRgb(15, 76, 129);
    private static final DeviceRgb PALE_BLUE = new DeviceRgb(239, 246, 255);
    private static final DeviceRgb ACCENT_GREEN = new DeviceRgb(21, 128, 61);
    private static final DeviceRgb ACCENT_RED = new DeviceRgb(185, 28, 28);

    private BankStatementExportHelper() {
    }

    public static void exportFilteredExcel(Node owner, String scopeTitle, List<BankStatementApiClient.TransactionDto> rows) {
        if (rows == null || rows.isEmpty()) {
            AppDialogService.info(owner, "Export Bank Statement", "No rows to export", "Adjust your search or status filter to include at least one transaction.");
            return;
        }
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("Bank_Statements", sanitizeFilePart("Bank_Statement_" + scopeTitle), "xlsx");
        if (file == null || !ExportFeedbackManager.checkFileWriteable(owner, file)) return;

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(file)) {
            Sheet sheet = wb.createSheet("Bank Transactions");
            CellStyle headerStyle = headerStyle(wb);
            CellStyle moneyStyle = moneyStyle(wb);

            String[] headers = {
                    "Line Ref", "Date", "Mode / Channel", "Payee / Party",
                    "Reference / UTR", "Narration / Description", "Debit (₹)", "Credit (₹)",
                    "Balance (₹)", "Status", "Match / Link"
            };
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            int rIdx = 1;
            for (var t : rows) {
                var parsed = BankNarrationParser.parse(t.description(), t.reference());
                Row row = sheet.createRow(rIdx++);
                row.createCell(0).setCellValue(safe(t.statementReference()));
                row.createCell(1).setCellValue(BusinessClock.formatDate(t.transactionDate()));
                row.createCell(2).setCellValue(parsed.channel());
                row.createCell(3).setCellValue(parsed.counterparty());
                row.createCell(4).setCellValue(parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(t.reference()));
                row.createCell(5).setCellValue(safe(t.description()));

                setMoneyCell(row.createCell(6), t.debit(), moneyStyle);
                setMoneyCell(row.createCell(7), t.credit(), moneyStyle);
                setMoneyCell(row.createCell(8), t.balance(), moneyStyle);

                row.createCell(9).setCellValue(safe(t.status()));
                row.createCell(10).setCellValue(safe(t.matchLink()));
            }
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                int w = sheet.getColumnWidth(i);
                if (w > 14000) sheet.setColumnWidth(i, 14000);
            }
            wb.write(out);
            ExportFeedbackManager.notifyExportSuccess(owner, file, "Bank Statement Transactions (Excel)", rows.size());
        } catch (Exception ex) {
            AppDialogService.error(owner, "Export Excel Failed", ex.getMessage());
        }
    }

    public static void exportFilteredPdf(Node owner, String scopeTitle, List<BankStatementApiClient.TransactionDto> rows) {
        if (rows == null || rows.isEmpty()) {
            AppDialogService.info(owner, "Export Bank Statement", "No rows to export", "Adjust your search or status filter to include at least one transaction.");
            return;
        }
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("Bank_Statements", sanitizeFilePart("Bank_Statement_" + scopeTitle), "pdf");
        if (file == null || !ExportFeedbackManager.checkFileWriteable(owner, file)) return;

        try {
            String[] headings = { "Line Ref", "Date", "Channel", "Ref / UTR", "Description / Party", "Debit (₹)", "Credit (₹)", "Balance (₹)", "Status" };
            float[] widths = { 1.4f, 1.1f, 0.8f, 1.3f, 2.7f, 1.1f, 1.1f, 1.2f, 1.0f };
            List<String[]> data = new ArrayList<>(rows.size());
            for (var t : rows) {
                var parsed = BankNarrationParser.parse(t.description(), t.reference());
                String desc = parsed.hasCounterparty() ? (parsed.counterparty() + " — " + safe(t.description())) : safe(t.description());
                data.add(new String[] {
                        safe(t.statementReference()),
                        BusinessClock.formatDate(t.transactionDate()),
                        parsed.channel(),
                        parsed.hasUtrOrRef() ? parsed.utrOrRef() : safe(t.reference()),
                        desc,
                        t.debit() > 0 ? money(t.debit()) : "-",
                        t.credit() > 0 ? money(t.credit()) : "-",
                        money(t.balance()),
                        safe(t.status())
                });
            }
            BrandedRegisterPdfService.export(file.toPath(), "Bank Statement — " + scopeTitle, headings, data, widths);
            ExportFeedbackManager.notifyExportSuccess(owner, file, "Bank Statement Transactions (PDF)", rows.size());
        } catch (Exception ex) {
            AppDialogService.error(owner, "Export PDF Failed", ex.getMessage());
        }
    }

    public static void exportBrsPdf(Node owner, BankStatementApiClient.BrsSummaryDto brs) {
        if (brs == null) return;
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("Bank_Statements", sanitizeFilePart("BRS_Report_" + brs.bankAccount()), "pdf");
        if (file == null || !ExportFeedbackManager.checkFileWriteable(owner, file)) return;

        try (PdfDocument pdf = new PdfDocument(new PdfWriter(file));
             Document doc = new Document(pdf, PageSize.A4)) {
            doc.setMargins(28, 28, 28, 28);

            Table banner = new Table(new float[] { 3, 2 }).useAllAvailableWidth();
            banner.addCell(bannerCell(BrandingService.companyName() + "\n" + ConfigManager.get("company.tagline", "ERP Solutions"), 14, TextAlignment.LEFT));
            banner.addCell(bannerCell("BANK RECONCILIATION STATEMENT (BRS)\nAs on " + BusinessClock.formatDate(brs.periodTo().isBlank() ? brs.asOfDate() : brs.periodTo()), 12, TextAlignment.RIGHT));
            doc.add(banner);

            doc.add(new Paragraph(brs.scopeLabel() + "   |   Period: " + BusinessClock.formatDate(brs.periodFrom()) + " to " + BusinessClock.formatDate(brs.periodTo()))
                    .setFontSize(9).setBold().setFontColor(BRAND_BLUE).setMarginTop(8).setMarginBottom(8));

            Table summary = new Table(new float[] { 3.5f, 1.5f }).useAllAvailableWidth();
            summary.addHeaderCell(headerCell("Particulars (Reconciliation Summary)"));
            summary.addHeaderCell(headerCell("Amount (₹)"));

            addBrsRow(summary, "Opening Balance as per Bank Statement", "₹ " + money(brs.openingBalance()), false);
            addBrsRow(summary, "(+) Total Deposits / Credits Imported", "₹ " + money(brs.totalCredits()), false);
            addBrsRow(summary, "(-) Total Withdrawals / Debits Imported", "₹ " + money(brs.totalDebits()), false);
            addBrsRow(summary, "Closing Balance as per Bank Statement", "₹ " + money(brs.statementClosingBalance()), true);
            addBrsRow(summary, "Statement Continuity Variance (Bank Closing - Calculated)", "₹ " + money(brs.balanceVariance()), false);
            addBrsRow(summary, "Matched Receipts (Sales / Returns / Bank Deposits)", "₹ " + money(brs.matchedCredits()), false);
            addBrsRow(summary, "Matched Payments (Purchases / Returns / Withdrawals)", "₹ " + money(brs.matchedDebits()), false);
            addBrsRow(summary, "Booked Expenses from Statement", "₹ " + money(brs.expenseDebits()), false);
            addBrsRow(summary, "Less: Uncleared / Pending Statement Credits (" + brs.unclearedCredits().size() + " items)", "₹ " + money(brs.pendingCredits()), false);
            addBrsRow(summary, "Add: Uncleared / Pending Statement Debits (" + brs.unclearedDebits().size() + " items)", "₹ " + money(brs.pendingDebits()), false);
            addBrsRow(summary, "Net Reconciled Balance Represented in ERP Books", "₹ " + money(brs.impliedBooksBalance()), true);
            if (brs.glAccountCode() != null && !brs.glAccountCode().isBlank()) {
                addBrsRow(summary, "Mapped Chart of Accounts GL Ledger (" + brs.glAccountCode() + ")", "₹ " + money(brs.glLedgerBalance()), false);
                addBrsRow(summary, "3-Way Variance (Bank Closing - GL Ledger)", "₹ " + money(brs.glVsStatementDiff()), false);
            }
            doc.add(summary);

            Paragraph statusPara = new Paragraph(brs.pendingCount() == 0
                    ? "CERTIFICATION: 100% FULLY RECONCILED (" + brs.reconciledCount() + " / " + brs.totalCount() + " transactions settled)"
                    : "PENDING ITEMS: " + brs.pendingCount() + " open items remaining (" + brs.reconciledCount() + " / " + brs.totalCount() + " reconciled)")
                    .setFontSize(9).setBold()
                    .setFontColor(brs.pendingCount() == 0 ? ACCENT_GREEN : ACCENT_RED)
                    .setMarginTop(8).setMarginBottom(10);
            doc.add(statusPara);

            appendUnclearedTable(doc, "Schedule A: Pending / Unreconciled Bank Credits (Money In)", brs.unclearedCredits(), true);
            appendUnclearedTable(doc, "Schedule B: Pending / Unreconciled Bank Debits (Money Out)", brs.unclearedDebits(), false);

            doc.add(new Paragraph("Generated by Jasvi ERP  •  " + BusinessClock.formatDate(BusinessClock.today()))
                    .setFontSize(8).setFontColor(BRAND_BLUE).setTextAlignment(TextAlignment.RIGHT).setMarginTop(12));
            ExportFeedbackManager.notifyExportSuccess(owner, file, "CA-Ready BRS Audit Report (PDF)", brs.totalCount());
        } catch (Exception ex) {
            AppDialogService.error(owner, "Export BRS PDF Failed", ex.getMessage());
        }
    }

    public static void exportBrsExcel(Node owner, BankStatementApiClient.BrsSummaryDto brs) {
        if (brs == null) return;
        File file = ExportFeedbackManager.resolveWorkspaceExportFile("Bank_Statements", sanitizeFilePart("BRS_Workbook_" + brs.bankAccount()), "xlsx");
        if (file == null || !ExportFeedbackManager.checkFileWriteable(owner, file)) return;

        try (Workbook wb = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(file)) {
            CellStyle hdr = headerStyle(wb);
            CellStyle mny = moneyStyle(wb);

            Sheet sSummary = wb.createSheet("BRS Summary");
            Row title = sSummary.createRow(0);
            title.createCell(0).setCellValue("Bank Reconciliation Statement (BRS) — " + brs.scopeLabel());
            Row period = sSummary.createRow(1);
            period.createCell(0).setCellValue("Period: " + BusinessClock.formatDate(brs.periodFrom()) + " to " + BusinessClock.formatDate(brs.periodTo()));

            String[][] metrics = {
                    { "Opening Balance (Statement)", String.format(Locale.ROOT, "%.2f", brs.openingBalance()) },
                    { "Total Credits", String.format(Locale.ROOT, "%.2f", brs.totalCredits()) },
                    { "Total Debits", String.format(Locale.ROOT, "%.2f", brs.totalDebits()) },
                    { "Closing Balance (Bank Statement)", String.format(Locale.ROOT, "%.2f", brs.statementClosingBalance()) },
                    { "Statement Continuity Variance", String.format(Locale.ROOT, "%.2f", brs.balanceVariance()) },
                    { "Matched Credits", String.format(Locale.ROOT, "%.2f", brs.matchedCredits()) },
                    { "Matched Debits", String.format(Locale.ROOT, "%.2f", brs.matchedDebits()) },
                    { "Expense Entries", String.format(Locale.ROOT, "%.2f", brs.expenseDebits()) },
                    { "Uncleared Credits (" + brs.unclearedCredits().size() + ")", String.format(Locale.ROOT, "%.2f", brs.pendingCredits()) },
                    { "Uncleared Debits (" + brs.unclearedDebits().size() + ")", String.format(Locale.ROOT, "%.2f", brs.pendingDebits()) },
                    { "Implied ERP Books Balance", String.format(Locale.ROOT, "%.2f", brs.impliedBooksBalance()) },
                    { "Mapped GL Account (" + safe(brs.glAccountCode()) + ") Balance", String.format(Locale.ROOT, "%.2f", brs.glLedgerBalance()) },
                    { "3-Way Variance (Statement - GL)", String.format(Locale.ROOT, "%.2f", brs.glVsStatementDiff()) }
            };
            Row sh = sSummary.createRow(3);
            sh.createCell(0).setCellValue("Particulars");
            sh.getCell(0).setCellStyle(hdr);
            sh.createCell(1).setCellValue("Amount (INR)");
            sh.getCell(1).setCellStyle(hdr);

            int r = 4;
            for (String[] rowVals : metrics) {
                Row row = sSummary.createRow(r++);
                row.createCell(0).setCellValue(rowVals[0]);
                setMoneyCell(row.createCell(1), Double.parseDouble(rowVals[1]), mny);
            }
            sSummary.autoSizeColumn(0);
            sSummary.autoSizeColumn(1);

            writeBrsScheduleSheet(wb, "Uncleared Credits", brs.unclearedCredits(), true, hdr, mny);
            writeBrsScheduleSheet(wb, "Uncleared Debits", brs.unclearedDebits(), false, hdr, mny);

            wb.write(out);
            ExportFeedbackManager.notifyExportSuccess(owner, file, "BRS Reconciliation Workbook (Excel)", brs.totalCount());
        } catch (Exception ex) {
            AppDialogService.error(owner, "Export BRS Excel Failed", ex.getMessage());
        }
    }

    private static void writeBrsScheduleSheet(Workbook wb, String title, List<BankStatementApiClient.BrsLineItemDto> items, boolean credit, CellStyle hdr, CellStyle mny) {
        Sheet sheet = wb.createSheet(title);
        String[] cols = { "Line Ref", "Date", "Ref / UTR", "Narration", "Status", credit ? "Credit (INR)" : "Debit (INR)" };
        Row h = sheet.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            org.apache.poi.ss.usermodel.Cell c = h.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(hdr);
        }
        int r = 1;
        for (var it : items) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(safe(it.statementReference()));
            row.createCell(1).setCellValue(BusinessClock.formatDate(it.transactionDate()));
            row.createCell(2).setCellValue(safe(it.reference()));
            row.createCell(3).setCellValue(safe(it.description()));
            row.createCell(4).setCellValue(safe(it.status()));
            setMoneyCell(row.createCell(5), credit ? it.credit() : it.debit(), mny);
        }
        for (int i = 0; i < cols.length; i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 13000) sheet.setColumnWidth(i, 13000);
        }
    }

    private static void appendUnclearedTable(Document doc, String heading, List<BankStatementApiClient.BrsLineItemDto> items, boolean credit) {
        doc.add(new Paragraph(heading).setFontSize(10).setBold().setFontColor(BRAND_BLUE).setMarginTop(8).setMarginBottom(4));
        Table tbl = new Table(new float[] { 1.3f, 1.0f, 1.3f, 3.0f, 0.9f, 1.2f }).useAllAvailableWidth();
        for (String h : new String[] { "Line Ref", "Date", "Ref / UTR", "Narration", "Status", credit ? "Credit (₹)" : "Debit (₹)" }) {
            tbl.addHeaderCell(headerCell(h));
        }
        if (items == null || items.isEmpty()) {
            tbl.addCell(new Cell(1, 6).add(new Paragraph("None — all items reconciled.")).setFontSize(8).setTextAlignment(TextAlignment.CENTER).setPadding(6));
        } else {
            for (var item : items) {
                tbl.addCell(textCell(safe(item.statementReference())));
                tbl.addCell(textCell(BusinessClock.formatDate(item.transactionDate())));
                tbl.addCell(textCell(safe(item.reference())));
                tbl.addCell(textCell(safe(item.description())));
                tbl.addCell(textCell(safe(item.status())));
                tbl.addCell(textCell("₹ " + money(credit ? item.credit() : item.debit())).setTextAlignment(TextAlignment.RIGHT));
            }
        }
        doc.add(tbl);
    }

    private static Cell bannerCell(String text, float fontSize, TextAlignment align) {
        return new Cell().add(new Paragraph(text).setBold().setFontSize(fontSize))
                .setBackgroundColor(BRAND_BLUE).setFontColor(ColorConstants.WHITE)
                .setPadding(10).setBorder(null).setTextAlignment(align);
    }

    private static Cell headerCell(String text) {
        return new Cell().add(new Paragraph(text).setBold().setFontSize(8))
                .setBackgroundColor(BRAND_BLUE).setFontColor(ColorConstants.WHITE)
                .setPadding(5);
    }

    private static Cell textCell(String text) {
        return new Cell().add(new Paragraph(text == null ? "" : text).setFontSize(8)).setPadding(4);
    }

    private static void addBrsRow(Table tbl, String label, String val, boolean emphasize) {
        Cell c1 = new Cell().add(new Paragraph(label).setFontSize(8.5f)).setPadding(5);
        Cell c2 = new Cell().add(new Paragraph(val).setFontSize(8.5f)).setPadding(5).setTextAlignment(TextAlignment.RIGHT);
        if (emphasize) {
            c1.setBold().setBackgroundColor(PALE_BLUE);
            c2.setBold().setBackgroundColor(PALE_BLUE);
        }
        tbl.addCell(c1);
        tbl.addCell(c2);
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private static CellStyle moneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private static void setMoneyCell(org.apache.poi.ss.usermodel.Cell cell, double val, CellStyle style) {
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private static String sanitizeFilePart(String s) {
        String out = safe(s).replaceAll("[^A-Za-z0-9_\\-]+", "_").replaceAll("^_+|_+$", "");
        return out.isBlank() ? "Report" : (out.length() > 42 ? out.substring(0, 42) : out);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String money(double v) { return String.format(Locale.ENGLISH, "%,.2f", v); }
}
