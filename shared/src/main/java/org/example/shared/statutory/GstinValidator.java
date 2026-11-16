package org.example.shared.statutory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Statutory Indian GSTIN (Goods and Services Tax Identification Number) Validator.
 * Implements 15-character alphanumeric parsing, state-code resolution, PAN extraction,
 * entity-type classification, and the official Luhn Modulo 36 checksum calculation.
 */
public final class GstinValidator {

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern GSTIN_FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

    private static final Map<String, String> STATE_CODES;
    private static final Map<Character, String> ENTITY_TYPES;

    static {
        Map<String, String> states = new HashMap<>();
        states.put("01", "Jammu and Kashmir");
        states.put("02", "Himachal Pradesh");
        states.put("03", "Punjab");
        states.put("04", "Chandigarh");
        states.put("05", "Uttarakhand");
        states.put("06", "Haryana");
        states.put("07", "Delhi");
        states.put("08", "Rajasthan");
        states.put("09", "Uttar Pradesh");
        states.put("10", "Bihar");
        states.put("11", "Sikkim");
        states.put("12", "Arunachal Pradesh");
        states.put("13", "Nagaland");
        states.put("14", "Manipur");
        states.put("15", "Mizoram");
        states.put("16", "Tripura");
        states.put("17", "Meghalaya");
        states.put("18", "Assam");
        states.put("19", "West Bengal");
        states.put("20", "Jharkhand");
        states.put("21", "Odisha");
        states.put("22", "Chhattisgarh");
        states.put("23", "Madhya Pradesh");
        states.put("24", "Gujarat");
        states.put("25", "Daman and Diu");
        states.put("26", "Dadra and Nagar Haveli");
        states.put("27", "Maharashtra");
        states.put("29", "Karnataka");
        states.put("30", "Goa");
        states.put("31", "Lakshadweep");
        states.put("32", "Kerala");
        states.put("33", "Tamil Nadu");
        states.put("34", "Puducherry");
        states.put("35", "Andaman and Nicobar Islands");
        states.put("36", "Telangana");
        states.put("37", "Andhra Pradesh");
        states.put("38", "Ladakh");
        states.put("97", "Other Territory");
        states.put("99", "Centre Jurisdiction");
        STATE_CODES = Collections.unmodifiableMap(states);

        Map<Character, String> entities = new HashMap<>();
        entities.put('C', "Company");
        entities.put('P', "Individual / Proprietorship");
        entities.put('H', "Hindu Undivided Family (HUF)");
        entities.put('F', "Partnership / LLP");
        entities.put('A', "Association of Persons (AOP)");
        entities.put('T', "Trust");
        entities.put('B', "Body of Individuals (BOI)");
        entities.put('L', "Local Authority");
        entities.put('J', "Artificial Juridical Person");
        entities.put('G', "Government Agency");
        ENTITY_TYPES = Collections.unmodifiableMap(entities);
    }

    private GstinValidator() {}

    public record GstinValidationResult(
        boolean valid,
        String gstin,
        String stateCode,
        String stateName,
        String pan,
        String entityType,
        String entityDescription,
        boolean checksumValid,
        char expectedChecksum,
        String message
    ) {}

    /**
     * Calculates the 15th check character using Indian GSTN Luhn Modulo 36 algorithm.
     */
    public static char calculateCheckDigit(String gstin14) {
        if (gstin14 == null || gstin14.length() < 14) {
            throw new IllegalArgumentException("GSTIN must have at least 14 characters to calculate check digit");
        }
        int sum = 0;
        int n = 36;
        for (int i = 0; i < 14; i++) {
            char c = Character.toUpperCase(gstin14.charAt(i));
            int codePoint = CHARS.indexOf(c);
            if (codePoint < 0) {
                throw new IllegalArgumentException("Invalid character in GSTIN at index " + i + ": " + c);
            }
            int factor = (i % 2 == 0) ? 1 : 2;
            int product = codePoint * factor;
            int addend = (product / n) + (product % n);
            sum += addend;
        }
        int remainder = sum % n;
        int checkCodePoint = (n - remainder) % n;
        return CHARS.charAt(checkCodePoint);
    }

    /**
     * Verifies the 15th check character of a 15-character GSTIN.
     */
    public static boolean verifyChecksum(String gstin15) {
        if (gstin15 == null || gstin15.length() != 15) return false;
        try {
            char expected = calculateCheckDigit(gstin15.substring(0, 14));
            return Character.toUpperCase(gstin15.charAt(14)) == expected;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Full statutory validation of an Indian GSTIN.
     */
    public static GstinValidationResult validate(String input) {
        if (input == null || input.trim().isBlank()) {
            return new GstinValidationResult(false, "", "", "Unspecified", "", "", "", false, ' ', "GSTIN is required");
        }
        String clean = input.trim().toUpperCase().replaceAll("\\s+", "");
        if (clean.length() < 2) {
            return new GstinValidationResult(false, clean, clean, "Incomplete", "", "", "", false, ' ', "State code required");
        }

        String stateCode = clean.substring(0, 2);
        String stateName = STATE_CODES.getOrDefault(stateCode, "Unknown State (" + stateCode + ")");

        if (clean.length() < 15) {
            return new GstinValidationResult(false, clean, stateCode, stateName, "", "", "", false, ' ',
                "Incomplete GSTIN (" + clean.length() + "/15 chars) • State: " + stateName);
        }

        if (clean.length() > 15) {
            return new GstinValidationResult(false, clean, stateCode, stateName, "", "", "", false, ' ',
                "GSTIN exceeds 15 characters (" + clean.length() + ")");
        }

        if (!GSTIN_FORMAT.matcher(clean).matches()) {
            return new GstinValidationResult(false, clean, stateCode, stateName, "", "", "", false, ' ',
                "Invalid GSTIN structure. Expected 2-digit state + 10-char PAN + 1 entity code + 'Z' + 1 checksum char");
        }

        String pan = clean.substring(2, 12);
        char entityCode = pan.charAt(3);
        String entityDesc = ENTITY_TYPES.getOrDefault(entityCode, "Commercial Entity (" + entityCode + ")");

        char expectedChecksum = calculateCheckDigit(clean.substring(0, 14));
        boolean checksumOk = clean.charAt(14) == expectedChecksum;

        if (!checksumOk) {
            return new GstinValidationResult(
                false, clean, stateCode, stateName, pan, String.valueOf(entityCode), entityDesc,
                false, expectedChecksum,
                "Luhn Checksum mismatch: expected '" + expectedChecksum + "', got '" + clean.charAt(14) + "' (Check for typos)"
            );
        }

        return new GstinValidationResult(
            true, clean, stateCode, stateName, pan, String.valueOf(entityCode), entityDesc,
            true, expectedChecksum,
            "✔ Valid GSTIN • " + stateName + " (" + stateCode + ") • " + entityDesc
        );
    }

    public static String getStateName(String stateCode) {
        return STATE_CODES.getOrDefault(stateCode, "");
    }
}
