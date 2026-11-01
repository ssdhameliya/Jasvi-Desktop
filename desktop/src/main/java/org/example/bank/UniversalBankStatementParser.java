package org.example.bank;

import org.apache.poi.ss.usermodel.*;
import org.example.api.bank.BankStatementApiClient;
import org.example.importing.ImportWorkbookBridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Universal Bank Statement Parser for Jasvi ERP Desktop.
 * Seamlessly parses .xlsx, .xls, and .csv bank statements across:
 * 1. Standard Jasvi ERP Bank Statement Template
 * 2. Kotak Mahindra Bank (preamble + Amount/Dr-Cr or Debit/Credit)
 * 3. HDFC, ICICI, SBI, Axis, and generic bank statement layouts.
 */
public final class UniversalBankStatementParser {

    private static final DateTimeFormatter TS_IN = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    public record AuditRow(
            int lineNo,
            int sourceIndex,
            String timestamp,
            String date,
            String valueDate,
            String description,
            String reference,
            double debit,
            double credit,
            double balance,
            String status,
            String error) {
    }

    public record AuditParseResult(
            ParsedStatement statement,
            List<AuditRow> auditRows,
            int totalRows,
            int errorCount) {
    }

    public record ParsedStatement(
            String bankName,
            String accountNumber,
            String accountHolder,
            String statementFrom,
            String statementTo,
            String currency,
            String branch,
            Double openingBalance,
            Double closingBalance,
            String sourceFingerprint,
            String sourceFileName,
            String sourceCsv,
            List<BankStatementApiClient.ImportRow> rows) {
    }

    public ParsedStatement parse(Path file) throws IOException {
        AuditParseResult result = parseWithAudit(file);
        if (result.errorCount() > 0) {
            List<String> invalidRows = result.auditRows().stream()
                    .filter(r -> !"PASSED".equalsIgnoreCase(r.status()))
                    .map(r -> "Row " + r.lineNo() + " / transaction " + r.sourceIndex() + ": "
                            + (r.error() == null ? "invalid values" : r.error()))
                    .toList();
            String detail = String.join("\n", invalidRows.stream().limit(8).toList());
            if (invalidRows.size() > 8)
                detail += "\n... and " + (invalidRows.size() - 8) + " more";
            throw new IllegalArgumentException("Bank statement contains " + invalidRows.size()
                    + " invalid transaction row(s). Nothing was imported.\n\n" + detail);
        }
        if (result.statement() == null || result.statement().rows().isEmpty()) {
            throw new IllegalArgumentException("No usable transactions were found in the bank statement file.");
        }
        return result.statement();
    }

    public AuditParseResult parseWithAudit(Path file) throws IOException {
        try (Workbook wb = ImportWorkbookBridge.openWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            return parseSheet(sheet, file);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse bank statement: " + e.getMessage(), e);
        }
    }

    private AuditParseResult parseSheet(Sheet sheet, Path file) throws Exception {
        String fileName = file.getFileName().toString();
        int headerRowIdx = -1;
        int dateCol = -1, valDateCol = -1, descCol = -1, refCol = -1, drCol = -1, crCol = -1, amtCol = -1, dirCol = -1,
                balCol = -1, slCol = -1;

        String detectedBank = "Primary Bank";
        String account = "";
        String accountHolder = "Jasvi Industries Pvt Ltd";
        String currency = "INR";
        String branch = "Main Branch";
        String periodFrom = "";
        String periodTo = "";

        int maxHeaderScan = Math.min(35, sheet.getLastRowNum());
        for (int r = 0; r <= maxHeaderScan; r++) {
            Row row = sheet.getRow(r);
            if (row == null)
                continue;

            // Extract preamble metadata before header row
            for (int c = 0; c + 1 < row.getLastCellNum(); c++) {
                String k = cellStr(row.getCell(c)).trim();
                String v = cellStr(row.getCell(c + 1)).trim();
                if (k.equalsIgnoreCase("Account No.") || k.equalsIgnoreCase("Account Number")) {
                    account = v;
                } else if (k.equalsIgnoreCase("Currency")) {
                    if (!v.isBlank())
                        currency = v;
                } else if (k.equalsIgnoreCase("Branch")) {
                    if (!v.isBlank())
                        branch = v;
                } else if (k.equalsIgnoreCase("Period")) {
                    String[] p = v.replace("From ", "").split(" To ");
                    if (p.length == 2) {
                        String pf = normalizeIsoDate(p[0]);
                        String pt = normalizeIsoDate(p[1]);
                        if (pf != null)
                            periodFrom = pf;
                        if (pt != null)
                            periodTo = pt;
                    }
                }
            }
            if (r == 1 && row.getCell(0) != null) {
                String maybeHolder = cellStr(row.getCell(0)).trim();
                if (!maybeHolder.isBlank() && !maybeHolder.toLowerCase(Locale.ROOT).contains("date")
                        && !maybeHolder.toLowerCase(Locale.ROOT).contains("statement")
                        && !maybeHolder.toLowerCase(Locale.ROOT).contains("template")) {
                    accountHolder = maybeHolder;
                }
            }

            int d = -1, vd = -1, de = -1, rf = -1, dr = -1, cr = -1, am = -1, di = -1, bl = -1, sl = -1;
            for (int j = 0; j < row.getLastCellNum(); j++) {
                String h = cellStr(row.getCell(j)).toLowerCase(Locale.ROOT).trim();
                if (h.isBlank())
                    continue;
                if (h.equals("sl. no.") || h.equals("sr. no.") || h.equals("s.no") || h.equals("serial no"))
                    sl = j;
                else if (h.contains("value") && h.contains("date"))
                    vd = j;
                else if (h.contains("date") || h.contains("txn date") || h.contains("trans date")) {
                    if (d < 0)
                        d = j;
                } else if (h.contains("narrat") || h.contains("desc") || h.contains("partic") || h.contains("remark"))
                    de = j;
                else if (h.contains("ref") || h.contains("chq") || h.contains("cheque") || h.contains("utr"))
                    rf = j;
                else if (h.contains("debit") || h.contains("withdrawal") || h.equals("dr"))
                    dr = j;
                else if (h.contains("credit") || h.contains("deposit") || h.equals("cr"))
                    cr = j;
                else if (h.contains("amount"))
                    am = j;
                else if (h.contains("direction") || h.equals("dr/cr") || h.equals("dr / cr") || h.equals("type"))
                    di = j;
                else if (h.contains("balance") || h.contains("closing"))
                    bl = j;
            }
            if (d >= 0 && (de >= 0 || rf >= 0) && (dr >= 0 || cr >= 0 || am >= 0)) {
                headerRowIdx = r;
                dateCol = d;
                valDateCol = vd >= 0 ? vd : d;
                descCol = de;
                refCol = rf;
                drCol = dr;
                crCol = cr;
                amtCol = am;
                dirCol = di;
                balCol = bl;
                slCol = sl;
                if (sl >= 0 && am >= 0 && di >= 0) {
                    detectedBank = "Kotak Mahindra Bank";
                }
                break;
            }
        }

        if (headerRowIdx < 0) {
            throw new IllegalArgumentException(
                    "Could not detect valid bank transaction table headers (Date, Description/Narration, Debit/Credit or Amount). Please ensure columns exist or use the Standard Bank Statement Template.");
        }

        List<BankStatementApiClient.ImportRow> rows = new ArrayList<>();
        List<AuditRow> auditRows = new ArrayList<>();
        Double runningOpening = null, runningClosing = null;
        int errorCount = 0;
        int sourceIdx = 1;

        for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null)
                continue;

            if (slCol >= 0) {
                String slText = cellStr(row.getCell(slCol)).trim();
                if (slText.isBlank() || !slText.matches("\\d+")) {
                    continue;
                }
                sourceIdx = Integer.parseInt(slText);
            }

            String dateStr = cellStr(row.getCell(dateCol));
            String desc = descCol >= 0 ? cellStr(row.getCell(descCol)) : "";
            String ref = refCol >= 0 ? cellStr(row.getCell(refCol)) : "";

            if (dateStr.isBlank() && desc.isBlank() && ref.isBlank())
                continue;
            if (dateStr.toLowerCase(Locale.ROOT).contains("opening balance")
                    || dateStr.toLowerCase(Locale.ROOT).contains("closing balance")
                    || desc.toLowerCase(Locale.ROOT).contains("statement summary")) {
                continue;
            }

            if (desc.isBlank())
                desc = "Bank Transaction";
            if (ref.isBlank())
                ref = "REF-" + sourceIdx;

            String valDateStr = (valDateCol >= 0 && valDateCol != dateCol) ? cellStr(row.getCell(valDateCol)) : dateStr;
            if (valDateStr.isBlank())
                valDateStr = dateStr;

            double debit = 0, credit = 0;
            String dirError = null;
            if (drCol >= 0 || crCol >= 0) {
                debit = drCol >= 0 ? num(row.getCell(drCol)) : 0;
                credit = crCol >= 0 ? num(row.getCell(crCol)) : 0;
            } else if (amtCol >= 0) {
                double rawAmt = num(row.getCell(amtCol));
                String dir = dirCol >= 0 ? cellStr(row.getCell(dirCol)).trim().toUpperCase(Locale.ROOT) : "";
                if (dirCol >= 0 && !dir.isBlank() && !dir.startsWith("DR") && !dir.startsWith("CR")
                        && !dir.contains("DEBIT") && !dir.contains("CREDIT")) {
                    dirError = "Direction must be DR or CR, got '" + dir + "'";
                } else if (dir.startsWith("CR") || dir.contains("CREDIT") || rawAmt < 0) {
                    credit = Math.abs(rawAmt);
                } else {
                    debit = Math.abs(rawAmt);
                }
            }

            double balance = balCol >= 0 ? num(row.getCell(balCol)) : 0;

            String parsedTimestamp = normalizeIsoTimestamp(row.getCell(dateCol), dateStr);
            String parsedDate = parsedTimestamp != null ? parsedTimestamp.substring(0, 10) : normalizeIsoDate(dateStr);
            String parsedValDate = normalizeIsoDate(valDateStr);
            if (parsedValDate == null)
                parsedValDate = parsedDate;

            if (dirError != null) {
                auditRows.add(new AuditRow(r + 1, sourceIdx, "", dateStr, valDateStr, desc, ref, debit, credit, balance,
                        "INVALID_ERROR", dirError));
                errorCount++;
                sourceIdx++;
                continue;
            }

            if (parsedDate == null) {
                auditRows.add(new AuditRow(r + 1, sourceIdx, "", dateStr, valDateStr, desc, ref, debit, credit, balance,
                        "INVALID_ERROR", "Invalid date format '" + dateStr + "'"));
                errorCount++;
                sourceIdx++;
                continue;
            }

            if (debit <= 0 && credit <= 0) {
                auditRows.add(new AuditRow(r + 1, sourceIdx, parsedDate, parsedDate, parsedValDate, desc, ref, debit,
                        credit, balance, "INVALID_ERROR", "Both debit and credit amounts are zero"));
                errorCount++;
                sourceIdx++;
                continue;
            }

            if (runningOpening == null)
                runningOpening = balance - credit + debit;
            runningClosing = balance;

            String ts = parsedTimestamp != null ? parsedTimestamp : (parsedDate + "T00:00:00");
            String fpKey = (!account.isBlank() ? account : fileName) + "|" + ts + "|" + ref + "|"
                    + String.format(Locale.ROOT, "%.2f", debit) + "|"
                    + String.format(Locale.ROOT, "%.2f", credit) + "|"
                    + String.format(Locale.ROOT, "%.2f", balance);
            String fp = sha256Hex(fpKey);

            // Standardize valueDate = parsedDate if not explicitly distinct
            String effectiveValueDate = parsedValDate != null ? parsedValDate : parsedDate;

            rows.add(new BankStatementApiClient.ImportRow(sourceIdx, ts, parsedDate, effectiveValueDate, desc, ref,
                    debit, credit, balance, fp));
            auditRows.add(new AuditRow(r + 1, sourceIdx, ts, parsedDate, effectiveValueDate, desc, ref, debit, credit,
                    balance, "PASSED", null));
            sourceIdx++;
        }

        if (rows.isEmpty() && errorCount > 0) {
            return new AuditParseResult(null, auditRows, auditRows.size(), errorCount);
        }

        String statementFrom = !periodFrom.isBlank() ? periodFrom
                : (rows.isEmpty() ? "" : rows.getFirst().transactionDate());
        String statementTo = !periodTo.isBlank() ? periodTo : (rows.isEmpty() ? "" : rows.getLast().transactionDate());

        byte[] rawBytes = Files.exists(file) ? Files.readAllBytes(file) : fileName.getBytes(StandardCharsets.UTF_8);
        String fileFingerprint = sha256Bytes(rawBytes);
        String rawCsvText = (fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".txt")) && Files.exists(file)
                        ? Files.readString(file, StandardCharsets.UTF_8)
                        : "";

        ParsedStatement statement = new ParsedStatement(
                detectedBank,
                account,
                accountHolder,
                statementFrom,
                statementTo,
                currency,
                branch,
                runningOpening != null ? runningOpening : 0.0,
                runningClosing != null ? runningClosing : 0.0,
                fileFingerprint,
                fileName,
                rawCsvText,
                rows);

        return new AuditParseResult(statement, auditRows, auditRows.size(), errorCount);
    }

    private static String cellStr(Cell cell) {
        if (cell == null)
            return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                LocalDate ld = cell.getLocalDateTimeCellValue().toLocalDate();
                return ld.toString();
            } catch (Exception ignored) {
            }
        }
        DataFormatter df = new DataFormatter();
        return df.formatCellValue(cell).trim();
    }

    private static double num(Cell cell) {
        if (cell == null)
            return 0;
        try {
            if (cell.getCellType() == CellType.NUMERIC)
                return cell.getNumericCellValue();
            String s = cellStr(cell).replace(",", "").replace("₹", "").replaceAll("[^0-9.\\-]", "");
            return s.isBlank() ? 0 : Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normalizeIsoTimestamp(Cell cell, String raw) {
        if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().withNano(0).toString();
            } catch (Exception ignored) {
            }
        }
        if (raw == null || raw.isBlank())
            return null;
        String clean = raw.trim().replace('/', '-');
        try {
            return LocalDateTime.parse(clean, TS_IN).toString();
        } catch (Exception ignored) {
        }
        String d = normalizeIsoDate(clean);
        return d != null ? d + "T00:00:00" : null;
    }

    private static String normalizeIsoDate(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        String clean = raw.trim().replace('/', '-');
        if (clean.contains(" ") && clean.matches("^\\d{1,4}-\\d{1,2}-\\d{1,4}\\s+\\d{1,2}:.*")) {
            clean = clean.split("\\s+")[0];
        }
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(clean, fmt).toString();
            } catch (Exception ignored) {
            }
        }
        try {
            if (clean.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
                String[] p = clean.split("-");
                return String.format(Locale.ROOT, "%04d-%02d-%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]));
            }
            if (clean.matches("^\\d{1,2}-\\d{1,2}-\\d{4}$")) {
                String[] p = clean.split("-");
                return String.format(Locale.ROOT, "%04d-%02d-%02d", Integer.parseInt(p[2]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[0]));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String sha256(Path p) throws IOException {
        return sha256Bytes(Files.readAllBytes(p));
    }

    private static String sha256Hex(String text) {
        return sha256Bytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
