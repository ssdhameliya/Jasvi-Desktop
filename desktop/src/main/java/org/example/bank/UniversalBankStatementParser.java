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
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yy"),
            DateTimeFormatter.ofPattern("dd-MM-yy"),
            DateTimeFormatter.ofPattern("d-M-yy"),
            DateTimeFormatter.ofPattern("MM/dd/yy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH));

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
        return parse(file, null);
    }

    public ParsedStatement parse(Path file, String password) throws IOException {
        AuditParseResult result = parseWithAudit(file, password);
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

    /**
     * Normalizes a bank account string by removing spaces, dashes, dots, and converting to uppercase.
     */
    public static boolean isFooterOrSummaryRow(String dateStr, String desc, String ref) {
        return isFooterOrSummaryRow(dateStr, desc, ref, null);
    }

    public static boolean isFooterOrSummaryRow(String dateStr, String desc, String ref, Row row) {
        String d = dateStr == null ? "" : dateStr.trim().toLowerCase(Locale.ROOT);
        String de = desc == null ? "" : desc.trim().toLowerCase(Locale.ROOT);
        String r = ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
        String combined = d + " " + de + " " + r;

        if (combined.contains("generated on")
                || combined.contains("generated by")
                || combined.contains("state account branch")
                || (combined.contains("state") && combined.contains("branch"))
                || combined.contains("statement summary")
                || combined.contains("opening balance")
                || combined.contains("closing balance")
                || combined.contains("computer generated")
                || combined.contains("contents of this statement")
                || combined.contains("registered office")
                || combined.contains("end of statement")
                || combined.contains("closing balance includes")
                || combined.contains("requesting branch")
                || combined.contains("rtgs/neft")
                || combined.contains("micr :")
                || combined.contains("micr:")
                || combined.contains("nomination :")
                || combined.contains("nomination:")
                || combined.contains("joint holders")
                || combined.contains("debit count")
                || combined.contains("credit count")
                || combined.contains("dr count")
                || combined.contains("cr count")
                || combined.contains("total debit")
                || combined.contains("total credit")
                || d.startsWith("gstin")
                || d.startsWith("gstn")
                || de.startsWith("gstin")
                || de.startsWith("gstn")) {
            return true;
        }

        if (row != null) {
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String val = cellStr(row.getCell(c)).trim().toLowerCase(Locale.ROOT);
                if (val.isBlank()) continue;
                if (val.contains("generated on")
                        || val.contains("generated by")
                        || val.contains("state account branch")
                        || (val.contains("state") && val.contains("branch"))
                        || val.contains("statement summary")
                        || val.contains("opening balance")
                        || val.contains("closing balance")
                        || val.contains("computer generated")
                        || val.contains("contents of this statement")
                        || val.contains("registered office")
                        || val.contains("end of statement")
                        || val.contains("closing balance includes")
                        || val.contains("requesting branch")
                        || val.contains("micr")
                        || val.contains("nomination")
                        || val.startsWith("gstin")
                        || val.startsWith("gstn")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String normalizeAccount(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Compares two bank accounts with tolerance for masking (e.g. "XXXX1234") or suffix matching.
     */
    public static boolean accountsMatch(String acc1, String acc2) {
        if (acc1 == null || acc2 == null) return false;
        String clean1 = normalizeAccount(acc1);
        String clean2 = normalizeAccount(acc2);
        if (clean1.isEmpty() || clean2.isEmpty()) return false;
        if (clean1.equalsIgnoreCase(clean2)) return true;

        String digits1 = clean1.replaceAll("[^0-9]", "");
        String digits2 = clean2.replaceAll("[^0-9]", "");
        if (!digits1.isEmpty() && digits1.equals(digits2)) return true;
        if (digits1.length() >= 4 && digits2.length() >= 4) {
            String tail1 = digits1.length() > 4 ? digits1.substring(digits1.length() - 4) : digits1;
            String tail2 = digits2.length() > 4 ? digits2.substring(digits2.length() - 4) : digits2;
            return tail1.equals(tail2);
        }
        return false;
    }

    public AuditParseResult parseWithAudit(Path file) throws IOException {
        return parseWithAudit(file, null);
    }

    public AuditParseResult parseWithAudit(Path file, String password) throws IOException {
        try (Workbook wb = ImportWorkbookBridge.openWorkbook(file, password)) {
            Sheet sheet = wb.getSheetAt(0);
            return parseSheet(sheet, file);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof org.apache.poi.EncryptedDocumentException ede) {
                    throw ede;
                }
                cause = cause.getCause();
            }
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
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String cellVal = cellStr(row.getCell(c)).trim();
                if (cellVal.isBlank()) continue;
                String lower = cellVal.toLowerCase(Locale.ROOT);

                if (detectedBank.equals("Primary Bank")) {
                    if (lower.contains("hdfc")) detectedBank = "HDFC Bank";
                    else if (lower.contains("kotak")) detectedBank = "Kotak Mahindra Bank";
                    else if (lower.contains("icici")) detectedBank = "ICICI Bank";
                    else if (lower.contains("axis")) detectedBank = "Axis Bank";
                    else if (lower.contains("state bank") || lower.contains("sbin")) detectedBank = "State Bank of India";
                    else if (lower.contains("canara")) detectedBank = "Canara Bank";
                    else if (lower.contains("bank of baroda") || lower.contains("barb0")) detectedBank = "Bank of Baroda";
                    else if (lower.contains("punjab national") || lower.contains("punb0")) detectedBank = "Punjab National Bank";
                }

                if (account.isBlank()) {
                    var m = java.util.regex.Pattern.compile("(?i)(?:account|a/c|ac)\\s*(?:no\\.?|number)?\\s*[:\\-]?\\s*([0-9A-Za-z*X]{6,25})").matcher(cellVal);
                    if (m.find()) {
                        account = m.group(1).trim();
                    }
                }

                if (c + 1 < row.getLastCellNum()) {
                    String k = cellVal;
                    String v = cellStr(row.getCell(c + 1)).trim();
                    if (account.isBlank() && (k.equalsIgnoreCase("Account No.") || k.equalsIgnoreCase("Account Number") || k.toLowerCase().contains("account no"))) {
                        String digits = v.replaceAll("[^0-9A-Za-z]", "").trim();
                        if (!digits.isBlank()) account = digits;
                    } else if (k.equalsIgnoreCase("Currency")) {
                        if (!v.isBlank()) currency = v;
                    } else if (k.equalsIgnoreCase("Branch")) {
                        if (!v.isBlank()) branch = v;
                    } else if (k.equalsIgnoreCase("Period")) {
                        String[] p = v.replace("From ", "").split(" To ");
                        if (p.length == 2) {
                            String pf = normalizeIsoDate(p[0]);
                            String pt = normalizeIsoDate(p[1]);
                            if (pf != null) periodFrom = pf;
                            if (pt != null) periodTo = pt;
                        }
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

        if (account.isBlank() && fileName != null) {
            var m = java.util.regex.Pattern.compile("(?i)(?:acct_statement_|account_|acct_)?([0-9A-Za-z*X]{6,25})").matcher(fileName);
            if (m.find()) {
                account = m.group(1).trim();
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
            if (isFooterOrSummaryRow(dateStr, desc, ref, row)) {
                continue;
            }
            // Skip bank privacy separator / asterisk divider rows (e.g. HDFC "********")
            if (dateStr.replace("*", "").isBlank() || desc.replace("*", "").isBlank()) {
                continue;
            }
            if (dateStr.matches("^[\\*\\-\\=\\_\\.]+$") || desc.matches("^[\\*\\-\\=\\_\\.]+$")) {
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
                // If amounts are zero or negative, this is trailing footer/summary metadata
                if (debit <= 0 && credit <= 0) {
                    continue;
                }
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
            if (clean.matches("^\\d{1,2}-\\d{1,2}-\\d{2}$")) {
                String[] p = clean.split("-");
                int yr = Integer.parseInt(p[2]);
                int fullYr = yr <= 69 ? 2000 + yr : 1900 + yr;
                return String.format(Locale.ROOT, "%04d-%02d-%02d", fullYr, Integer.parseInt(p[1]),
                        Integer.parseInt(p[0]));
            }
            if (clean.matches("^\\d{2}-\\d{1,2}-\\d{1,2}$")) {
                String[] p = clean.split("-");
                int yr = Integer.parseInt(p[0]);
                int fullYr = yr <= 69 ? 2000 + yr : 1900 + yr;
                return String.format(Locale.ROOT, "%04d-%02d-%02d", fullYr, Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]));
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
