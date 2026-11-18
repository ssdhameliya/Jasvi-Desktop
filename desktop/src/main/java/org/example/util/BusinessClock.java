package org.example.util;

import org.example.config.ConfigManager;

import java.time.*;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for user-facing business dates and times.
 * Saved company settings win; system defaults are used only when no valid setting exists.
 */
public final class BusinessClock {
    private static final String DEFAULT_DATE_FORMAT = "dd/MM/yyyy";

    private BusinessClock() { }

    public static ZoneId zone() {
        String configured = ConfigManager.runtimeBusinessZone();
        if (configured == null || configured.isBlank()) configured = ConfigManager.get("company.timeZone", "");
        if (configured != null && !configured.isBlank()) {
            try { return ZoneId.of(configured.trim()); } catch (Exception ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        }
        return ZoneId.systemDefault();
    }

    public static String normalizeDatePattern(String candidate) {
        if (candidate == null || candidate.isBlank()) return DEFAULT_DATE_FORMAT;
        String s = candidate.trim();
        if ("DD-MM-YYYY".equalsIgnoreCase(s)) return "dd-MM-yyyy";
        if ("DD/MM/YYYY".equalsIgnoreCase(s)) return "dd/MM/yyyy";
        if ("YYYY-MM-DD".equalsIgnoreCase(s)) return "yyyy-MM-dd";
        if ("MM/DD/YYYY".equalsIgnoreCase(s)) return "MM/dd/yyyy";
        if ("DD MMM YYYY".equalsIgnoreCase(s)) return "dd MMM yyyy";
        String cleaned = s.replace("YYYY", "yyyy").replace("DD", "dd");
        try {
            DateTimeFormatter.ofPattern(cleaned, Locale.getDefault());
            return cleaned;
        } catch (IllegalArgumentException ignored) {
            return DEFAULT_DATE_FORMAT;
        }
    }

    public static String normalizeTimePattern(String candidate) {
        if (candidate == null || candidate.isBlank()) return "hh:mm a";
        String s = candidate.trim();
        if ("12-hour".equalsIgnoreCase(s) || "12h".equalsIgnoreCase(s) || "12_HOUR".equalsIgnoreCase(s)) return "hh:mm a";
        if ("24-hour".equalsIgnoreCase(s) || "24h".equalsIgnoreCase(s) || "24_HOUR".equalsIgnoreCase(s)) return "HH:mm";
        try {
            DateTimeFormatter.ofPattern(s, Locale.getDefault());
            return s;
        } catch (IllegalArgumentException ignored) {
            return "hh:mm a";
        }
    }

    public static String datePattern() {
        String configured = ConfigManager.runtimeBusinessDateFormat();
        if (configured == null || configured.isBlank()) configured = ConfigManager.get("company.dateFormat", "");
        if (configured == null || configured.isBlank()) configured = ConfigManager.get("date.format", DEFAULT_DATE_FORMAT);
        return normalizeDatePattern(configured);
    }

    public static DateTimeFormatter dateFormatter() {
        return DateTimeFormatter.ofPattern(datePattern(), Locale.getDefault());
    }

    public static LocalDate today() { return LocalDate.now(zone()); }
    public static LocalDateTime now() { return LocalDateTime.now(zone()); }
    public static ZonedDateTime zonedNow() { return ZonedDateTime.now(zone()); }

    /** Returns April 1st of the current Indian financial year. */
    public static LocalDate startOfCurrentFinancialYear() {
        LocalDate today = today();
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return LocalDate.of(startYear, 4, 1);
    }

    /** Short display label for the saved business timezone, e.g. IST, UTC or GST. */
    public static String zoneAbbreviation() {
        try {
            return DateTimeFormatter.ofPattern("z", Locale.ENGLISH).format(zonedNow());
        } catch (Exception ignored) {
            return zone().getId();
        }
    }
    public static Instant nowUtc() { return Instant.now(); }
    public static YearMonth currentMonth() { return YearMonth.from(today()); }

    public static String formatDate(LocalDate value) {
        return value == null ? "" : value.format(dateFormatter());
    }

    /**
     * Formats ISO and legacy day/month date strings through the configured
     * business date pattern. This guarantees leading zeroes for dd/MM/yyyy.
     */
    public static String formatDate(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        String candidate = text.length() > 10 && (text.contains(" ") || text.contains("T")) ? text.substring(0, 10) : text;
        for (DateTimeFormatter parser : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("d/M/uuuu"),
                DateTimeFormatter.ofPattern("dd/MM/uuuu"),
                DateTimeFormatter.ofPattern("dd-MM-uuuu"),
                DateTimeFormatter.ofPattern("d-M-uuuu"),
                DateTimeFormatter.ofPattern("MM/dd/uuuu"),
                DateTimeFormatter.ofPattern("M/d/uuuu"),
                DateTimeFormatter.ofPattern("uuuu/MM/dd"),
                DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH))) {
            try {
                return formatDate(LocalDate.parse(candidate, parser));
            } catch (DateTimeParseException ignored) { }
            try {
                return formatDate(LocalDate.parse(text, parser));
            } catch (DateTimeParseException ignored) { }
        }
        return text;
    }

    /**
     * Parses a strict 8-digit compact date (e.g. {@code 18112026}, {@code 11182026}, or {@code 20261118})
     * using active company Settings date format priority, system OS locale tie-breaking, and automatic
     * unambiguous fallback when day > 12. Returns {@code null} for invalid calendar dates (e.g. {@code 31022026}).
     */
    public static LocalDate parseCompactEightDigitDate(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() != 8 || !isAllAsciiDigits(trimmed)) return null;
        return parseCompactDigits(trimmed, false);
    }

    /**
     * Parses ISO, Indian, UK, US, Excel, compact digit-only (8-digit & 6-digit), dot/space separated,
     * and legacy date strings into a LocalDate using strict calendar validation, returning null if invalid.
     */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (isAllAsciiDigits(text)) {
            return parseCompactDigits(text, true);
        }
        String candidate = text.length() > 10 && (text.contains(" ") || text.contains("T")) ? text.substring(0, 10) : text;
        String normalizedText = normalizeNumericSeparators(text);
        String normalizedCandidate = normalizeNumericSeparators(candidate);

        boolean monthFirst = prefersMonthFirst();
        List<DateTimeFormatter> parsers = new ArrayList<>();
        try {
            String dp = datePattern();
            if (dp != null && !dp.isBlank()) {
                parsers.add(strictFormatter(dp, Locale.ENGLISH));
            }
        } catch (Exception ignored) {}

        parsers.add(DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT));
        if (monthFirst) {
            parsers.addAll(List.of(
                    strictFormatter("M/d/uuuu", Locale.ENGLISH),
                    strictFormatter("MM/dd/uuuu", Locale.ENGLISH),
                    strictFormatter("M-d-uuuu", Locale.ENGLISH),
                    strictFormatter("MM-dd-uuuu", Locale.ENGLISH),
                    strictFormatter("d/M/uuuu", Locale.ENGLISH),
                    strictFormatter("dd/MM/uuuu", Locale.ENGLISH),
                    strictFormatter("dd-MM-uuuu", Locale.ENGLISH),
                    strictFormatter("d-M-uuuu", Locale.ENGLISH),
                    strictFormatter("uuuu/MM/dd", Locale.ENGLISH),
                    strictFormatter("M/d/uu", Locale.ENGLISH),
                    strictFormatter("M-d-uu", Locale.ENGLISH),
                    strictFormatter("d/M/uu", Locale.ENGLISH),
                    strictFormatter("d-M-uu", Locale.ENGLISH)));
        } else {
            parsers.addAll(List.of(
                    strictFormatter("d/M/uuuu", Locale.ENGLISH),
                    strictFormatter("dd/MM/uuuu", Locale.ENGLISH),
                    strictFormatter("dd-MM-uuuu", Locale.ENGLISH),
                    strictFormatter("d-M-uuuu", Locale.ENGLISH),
                    strictFormatter("M/d/uuuu", Locale.ENGLISH),
                    strictFormatter("MM/dd/uuuu", Locale.ENGLISH),
                    strictFormatter("M-d-uuuu", Locale.ENGLISH),
                    strictFormatter("MM-dd-uuuu", Locale.ENGLISH),
                    strictFormatter("uuuu/MM/dd", Locale.ENGLISH),
                    strictFormatter("d/M/uu", Locale.ENGLISH),
                    strictFormatter("d-M-uu", Locale.ENGLISH),
                    strictFormatter("M/d/uu", Locale.ENGLISH),
                    strictFormatter("M-d-uu", Locale.ENGLISH)));
        }
        parsers.addAll(List.of(
                strictFormatter("dd-MMM-uuuu", Locale.ENGLISH),
                strictFormatter("d-MMM-uuuu", Locale.ENGLISH),
                strictFormatter("dd MMM uuuu", Locale.ENGLISH),
                strictFormatter("d MMM uuuu", Locale.ENGLISH)));

        for (DateTimeFormatter parser : parsers) {
            for (String sample : List.of(normalizedCandidate, normalizedText, candidate, text)) {
                try {
                    return LocalDate.parse(sample, parser);
                } catch (DateTimeParseException ignored) { }
            }
        }
        return null;
    }

    private static DateTimeFormatter strictFormatter(String pattern, Locale locale) {
        String strictPattern = pattern.replace('y', 'u');
        return DateTimeFormatter.ofPattern(strictPattern, locale).withResolverStyle(ResolverStyle.STRICT);
    }

    private static boolean prefersMonthFirst() {
        String dp = datePattern();
        if (dp != null && !dp.isBlank()) {
            char first = dp.charAt(0);
            if (first == 'M') return true;
            if (first == 'd') return false;
        }
        try {
            String localShort = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    FormatStyle.SHORT, null, IsoChronology.INSTANCE, Locale.getDefault());
            int mIdx = localShort.indexOf('M');
            int dIdx = localShort.indexOf('d');
            if (mIdx >= 0 && dIdx >= 0) {
                return mIdx < dIdx;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static boolean isAllAsciiDigits(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String normalizeNumericSeparators(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.matches("\\d{1,4}[.\\s]\\d{1,2}[.\\s]\\d{2,4}")) {
            return value.replace('.', '-').replace(' ', '-');
        }
        return value;
    }

    private static LocalDate parseCompactDigits(String digits, boolean allowSixDigit) {
        if (digits == null) return null;
        boolean monthFirst = prefersMonthFirst();
        if (digits.length() == 8) {
            int first4 = Integer.parseInt(digits.substring(0, 4));
            int a = Integer.parseInt(digits.substring(0, 2));
            int b = Integer.parseInt(digits.substring(2, 4));
            int last4 = Integer.parseInt(digits.substring(4, 8));
            int mYmd = Integer.parseInt(digits.substring(4, 6));
            int dYmd = Integer.parseInt(digits.substring(6, 8));

            String dp = datePattern();
            boolean yearFirstSetting = dp != null && !dp.isBlank() && (dp.charAt(0) == 'y' || dp.charAt(0) == 'u');

            LocalDate preferred = monthFirst ? safeDate(last4, a, b) : safeDate(last4, b, a);
            LocalDate alternate = monthFirst ? safeDate(last4, b, a) : safeDate(last4, a, b);
            LocalDate ymd = safeDate(first4, mYmd, dYmd);

            if (yearFirstSetting) {
                if (ymd != null) return ymd;
                if (preferred != null) return preferred;
                return alternate;
            }
            if (preferred != null) return preferred;
            if (alternate != null) return alternate;
            return ymd;
        }
        if (allowSixDigit && digits.length() == 6) {
            int a = Integer.parseInt(digits.substring(0, 2));
            int b = Integer.parseInt(digits.substring(2, 4));
            int year = 2000 + Integer.parseInt(digits.substring(4, 6));
            LocalDate preferred = monthFirst ? safeDate(year, a, b) : safeDate(year, b, a);
            if (preferred != null) return preferred;
            return monthFirst ? safeDate(year, b, a) : safeDate(year, a, b);
        }
        return null;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        if (year < 1900 || year > 2199 || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    public static String timePattern() {
        String configured = ConfigManager.runtimeBusinessTimeFormat();
        if (configured == null || configured.isBlank()) configured = ConfigManager.get("company.timeFormat", "hh:mm a");
        return normalizeTimePattern(configured);
    }

    public static String formatInstant(Instant value, String timePattern) {
        if (value == null) return "";
        String pattern = datePattern() + (timePattern == null || timePattern.isBlank() ? "" : " " + timePattern.trim());
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(zone()).format(value);
    }

    public static String formatTimestamp(Instant value) {
        if (value == null) return "";
        return formatInstant(value, timePattern());
    }

    /** Formats canonical or legacy persisted timestamps in the Application Settings timezone. */
    public static String formatTimestamp(String value) {
        if (value == null || value.isBlank()) return "";
        try { return formatInstant(parseTimestamp(value), timePattern()); }
        catch (RuntimeException ignored) { return value; }
    }

    public static String formatTime(Instant value) {
        if (value == null) return "";
        return DateTimeFormatter.ofPattern(timePattern(), Locale.getDefault()).withZone(zone()).format(value);
    }

    public static String formatTime(LocalTime value) {
        if (value == null) return "";
        return DateTimeFormatter.ofPattern(timePattern(), Locale.getDefault()).format(value);
    }

    public static String formatTime(LocalDateTime value) {
        if (value == null) return "";
        return DateTimeFormatter.ofPattern(timePattern(), Locale.getDefault()).format(value);
    }

    /** Returns the configured business-local date represented by a canonical or legacy timestamp. */
    public static LocalDate localDateOfTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return parseTimestamp(value).atZone(zone()).toLocalDate(); }
        catch (RuntimeException ignored) { return null; }
    }

    /** Parses canonical UTC/offset timestamps and legacy local TEXT timestamps using the configured business zone. */
    public static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try { return Instant.parse(text); } catch (DateTimeParseException ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        try { return OffsetDateTime.parse(text).toInstant(); } catch (DateTimeParseException ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        String offsetCompatible = text.replace(' ', 'T');
        if (offsetCompatible.matches(".*[+-]\\d{2}$")) offsetCompatible += ":00";
        try { return OffsetDateTime.parse(offsetCompatible).toInstant(); } catch (DateTimeParseException ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        try { return LocalDateTime.parse(offsetCompatible, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone()).toInstant(); } catch (DateTimeParseException ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a z", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH))) {
            try { return ZonedDateTime.parse(text, formatter).toInstant(); }
            catch (Exception ignored) { }
            try { return LocalDateTime.parse(text, formatter).atZone(zone()).toInstant(); }
            catch (DateTimeParseException ignored) { java.lang.System.getLogger("org.example").log(java.lang.System.Logger.Level.DEBUG, "Suppressed exception: " + ignored.getMessage(), ignored); }
        }
        throw new IllegalArgumentException("Unsupported timestamp format: " + value);
    }

    /** Formats a raw time string (e.g. HH:mm or hh:mm a) using the configured company timePattern. */
    public static String formatTime(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.trim();
        for (DateTimeFormatter parser : List.of(
                DateTimeFormatter.ofPattern("HH:mm"),
                DateTimeFormatter.ofPattern("H:m"),
                DateTimeFormatter.ofPattern("HH:mm:ss"),
                DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))) {
            try {
                LocalTime time = LocalTime.parse(text, parser);
                return DateTimeFormatter.ofPattern(timePattern(), Locale.getDefault()).format(time);
            } catch (DateTimeParseException ignored) { }
        }
        return text;
    }
}
