package org.example.shared;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Shared reference-format validation used by normal ERP creation and import preflight. */
public final class ReferenceFormatRules {
    private ReferenceFormatRules() {}


    public static void requireValidFormat(String format) {
        if (format == null || format.isBlank()) throw new IllegalArgumentException("Reference format is required");
        java.util.regex.Matcher matcher = Pattern.compile("X{2,}").matcher(format.trim());
        if (!matcher.find()) throw new IllegalArgumentException("Reference format must contain exactly one sequence group of at least two X characters (for example XXXX). X defines the minimum zero-padding width and expands automatically when the sequence grows");
        if (matcher.find()) throw new IllegalArgumentException("Reference format must contain only one X sequence group");
    }

    public static java.util.regex.Matcher sequenceMatcher(String format) {
        requireValidFormat(format);
        java.util.regex.Matcher matcher = Pattern.compile("X{2,}").matcher(format.trim());
        matcher.find();
        return matcher;
    }

    public static boolean matches(String format, String value, LocalDate documentDate) {
        if (format == null || format.isBlank() || value == null || value.isBlank()) return false;
        return Pattern.compile(toRegex(format.trim(), documentDate), Pattern.CASE_INSENSITIVE).matcher(value.trim()).matches();
    }

    public static String format(String formatPattern, long sequenceNumber, LocalDate date) {
        if (formatPattern == null || formatPattern.isBlank()) return String.valueOf(sequenceNumber);
        String res = formatPattern.trim();
        LocalDate d = date == null ? LocalDate.now() : date;

        int month = d.getMonthValue();
        int year = d.getYear();
        int fyStart = (month >= 4) ? year : (year - 1);
        int fyEnd = fyStart + 1;
        String fyStartYy = String.format(java.util.Locale.ROOT, "%02d", fyStart % 100);
        String fyEndYy = String.format(java.util.Locale.ROOT, "%02d", fyEnd % 100);
        String fyStartYyyy = String.format(java.util.Locale.ROOT, "%04d", fyStart);
        String fyEndYyyy = String.format(java.util.Locale.ROOT, "%04d", fyEnd);

        // Composite Financial Year Tokens (Replaced BEFORE individual YYYY and YY)
        res = res.replaceAll("(?i)\\{?YYYY-YYYY\\}?", fyStartYyyy + "-" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{?YYYY/YYYY\\}?", fyStartYyyy + "/" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{?YY-YYYY\\}?", fyStartYy + "-" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{?YY/YYYY\\}?", fyStartYy + "/" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{?YYYY-YY\\}?", fyStartYyyy + "-" + fyEndYy);
        res = res.replaceAll("(?i)\\{?YYYY/YY\\}?", fyStartYyyy + "/" + fyEndYy);
        res = res.replaceAll("(?i)\\{?YY-YY\\}?", fyStartYy + "-" + fyEndYy);
        res = res.replaceAll("(?i)\\{?YY/YY\\}?", fyStartYy + "/" + fyEndYy);
        res = res.replaceAll("(?i)\\{YY\\}-\\{YYYY\\}", fyStartYy + "-" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{YY\\}/\\{YYYY\\}", fyStartYy + "/" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{YYYY\\}-\\{YYYY\\}", fyStartYyyy + "-" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{YYYY\\}/\\{YYYY\\}", fyStartYyyy + "/" + fyEndYyyy);
        res = res.replaceAll("(?i)\\{YYYY\\}-\\{YY\\}", fyStartYyyy + "-" + fyEndYy);
        res = res.replaceAll("(?i)\\{YYYY\\}/\\{YY\\}", fyStartYyyy + "/" + fyEndYy);
        res = res.replaceAll("(?i)\\{YY\\}-\\{YY\\}", fyStartYy + "-" + fyEndYy);
        res = res.replaceAll("(?i)\\{YY\\}/\\{YY\\}", fyStartYy + "/" + fyEndYy);

        // Pre-combined Calendar Dates
        res = res.replaceAll("(?i)\\{?DD-MM-YYYY\\}?", String.format(java.util.Locale.ROOT, "%02d-%02d-%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()));
        res = res.replaceAll("(?i)\\{?DD/MM/YYYY\\}?", String.format(java.util.Locale.ROOT, "%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()));
        res = res.replaceAll("(?i)\\{?YYYY-MM-DD\\}?", String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth()));

        // Single Calendar Tokens
        res = res.replaceAll("(?i)\\{?YYYY\\}?", String.format(java.util.Locale.ROOT, "%04d", d.getYear()));
        res = res.replaceAll("(?i)\\{?YY\\}?", String.format(java.util.Locale.ROOT, "%02d", d.getYear() % 100));
        res = res.replaceAll("(?i)\\{?DD\\}?", String.format(java.util.Locale.ROOT, "%02d", d.getDayOfMonth()));
        res = res.replaceAll("(?i)\\{?MM\\}?", String.format(java.util.Locale.ROOT, "%02d", d.getMonthValue()));

        java.util.regex.Matcher matcher = Pattern.compile("X{2,}", Pattern.CASE_INSENSITIVE).matcher(res);
        if (matcher.find()) {
            int width = matcher.end() - matcher.start();
            String formattedSeq = String.format(java.util.Locale.ROOT, "%0" + width + "d", sequenceNumber);
            res = res.substring(0, matcher.start()) + formattedSeq + res.substring(matcher.end());
        } else {
            res = res + sequenceNumber;
        }
        return res;
    }

    public static String validationMessage(String format, String value, LocalDate documentDate) {
        if (value == null || value.isBlank()) return "Reference is mandatory";
        if (format == null || format.isBlank()) return "Reference format is not configured";
        return matches(format, value, documentDate) ? "All validations passed" : "Does not match configured format " + format;
    }

    static String toRegex(String format, LocalDate date) {
        if (format == null) return "^.*$";
        StringBuilder out = new StringBuilder("^");
        LocalDate d = date;
        String cleanFmt = format.trim().replaceAll("\\{([A-Za-z0-9_-]+)\\}", "$1");

        int fyStart = 0;
        int fyEnd = 0;
        if (d != null) {
            int m = d.getMonthValue();
            int y = d.getYear();
            fyStart = (m >= 4) ? y : (y - 1);
            fyEnd = fyStart + 1;
        }

        for (int i = 0; i < cleanFmt.length();) {
            // Composite Financial Year Tokens
            if (cleanFmt.regionMatches(true, i, "YYYY-YYYY", 0, 9)) {
                out.append(d == null ? "\\d{4}-\\d{4}" : String.format(java.util.Locale.ROOT, "%04d-%04d", fyStart, fyEnd));
                i += 9; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YYYY/YYYY", 0, 9)) {
                out.append(d == null ? "\\d{4}/\\d{4}" : String.format(java.util.Locale.ROOT, "%04d/%04d", fyStart, fyEnd));
                i += 9; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YY-YYYY", 0, 7)) {
                out.append(d == null ? "\\d{2}-\\d{4}" : String.format(java.util.Locale.ROOT, "%02d-%04d", fyStart % 100, fyEnd));
                i += 7; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YY/YYYY", 0, 7)) {
                out.append(d == null ? "\\d{2}/\\d{4}" : String.format(java.util.Locale.ROOT, "%02d/%04d", fyStart % 100, fyEnd));
                i += 7; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YYYY-YY", 0, 7)) {
                out.append(d == null ? "\\d{4}-\\d{2}" : String.format(java.util.Locale.ROOT, "%04d-%02d", fyStart, fyEnd % 100));
                i += 7; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YYYY/YY", 0, 7)) {
                out.append(d == null ? "\\d{4}/\\d{2}" : String.format(java.util.Locale.ROOT, "%04d/%02d", fyStart, fyEnd % 100));
                i += 7; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YY-YY", 0, 5)) {
                out.append(d == null ? "\\d{2}-\\d{2}" : String.format(java.util.Locale.ROOT, "%02d-%02d", fyStart % 100, fyEnd % 100));
                i += 5; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YY/YY", 0, 5)) {
                out.append(d == null ? "\\d{2}/\\d{2}" : String.format(java.util.Locale.ROOT, "%02d/%02d", fyStart % 100, fyEnd % 100));
                i += 5; continue;
            }

            // Compound Calendar Dates
            if (cleanFmt.regionMatches(true, i, "DD-MM-YYYY", 0, 10)) {
                out.append(d == null ? "\\d{2}-\\d{2}-\\d{4}" : String.format(java.util.Locale.ROOT, "%02d-%02d-%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()));
                i += 10; continue;
            }
            if (cleanFmt.regionMatches(true, i, "DD/MM/YYYY", 0, 10)) {
                out.append(d == null ? "\\d{2}/\\d{2}/\\d{4}" : String.format(java.util.Locale.ROOT, "%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear()));
                i += 10; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YYYY-MM-DD", 0, 10)) {
                out.append(d == null ? "\\d{4}-\\d{2}-\\d{2}" : String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth()));
                i += 10; continue;
            }

            // Single Tokens
            if (cleanFmt.regionMatches(true, i, "YYYY", 0, 4)) {
                out.append(d == null ? "\\d{4}" : String.format(java.util.Locale.ROOT, "%04d", d.getYear()));
                i += 4; continue;
            }
            if (cleanFmt.regionMatches(true, i, "YY", 0, 2)) {
                out.append(d == null ? "\\d{2}" : String.format(java.util.Locale.ROOT, "%02d", d.getYear() % 100));
                i += 2; continue;
            }
            if (cleanFmt.regionMatches(true, i, "DD", 0, 2)) {
                out.append(d == null ? "\\d{2}" : String.format(java.util.Locale.ROOT, "%02d", d.getDayOfMonth()));
                i += 2; continue;
            }
            if (cleanFmt.regionMatches(true, i, "MM", 0, 2)) {
                out.append(d == null ? "\\d{2}" : String.format(java.util.Locale.ROOT, "%02d", d.getMonthValue()));
                i += 2; continue;
            }

            if (cleanFmt.charAt(i) == 'X' || cleanFmt.charAt(i) == 'x') {
                int j = i;
                while (j < cleanFmt.length() && (cleanFmt.charAt(j) == 'X' || cleanFmt.charAt(j) == 'x')) j++;
                out.append("\\d{").append(j - i).append(",}");
                i = j;
                continue;
            }

            char c = cleanFmt.charAt(i++);
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.append('$').toString();
    }
}
