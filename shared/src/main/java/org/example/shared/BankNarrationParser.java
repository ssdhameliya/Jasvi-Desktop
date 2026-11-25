package org.example.shared;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Indian banking narration analyzer for Jasvi ERP.
 * Extracts normalized transaction channel badges (UPI/NEFT/IMPS/RTGS/CHQ/ACH/POS/CHARGES),
 * authentic UTR/RRN/Cheque reference numbers, and clean counterparty names without mutating raw evidence.
 */
public final class BankNarrationParser {

    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern DIGITS_REF_PATTERN = Pattern.compile("\\b(\\d{9,20})\\b");
    private static final Pattern ALNUM_UTR_PATTERN = Pattern.compile("\\b([A-Z]{1,6}[A-Z0-9]*\\d{6,}[A-Z0-9]*)\\b");
    private static final Pattern CHEQUE_NO_PATTERN = Pattern.compile("(?i)(?:CHQ|CHEQUE|CLG|INST)(?:\\s*(?:NO\\.?|NUMBER|NUM|#))?[\\s\\-#/:.]*(\\d{5,10})\\b");
    private static final Pattern UPI_HANDLE_PATTERN = Pattern.compile("^[A-Za-z0-9._\\-]+@[A-Za-z0-9]+$");

    private static final Set<String> SKIP_TOKENS = Set.of(
            "UPI", "NEFT", "IMPS", "RTGS", "CHQ", "CHEQUE", "ACH", "NACH", "ECS", "POS", "ATM",
            "CR", "DR", "CREDIT", "DEBIT", "BY", "TO", "TR", "TRF", "TRANSFER", "P2A", "P2M", "P2P",
            "PAY", "PAYMENT", "PAID", "FROM", "IN", "OUT", "MB", "IB", "REV", "CHG", "CHRG", "CHARGES",
            "FEE", "GST", "IGST", "CGST", "SGST", "NA", "NULL", "YESB", "HDFC", "ICIC", "SBIN",
            "UTIB", "KKBK", "BARB", "CNRB", "PUNB", "IDFB", "INDUS", "PAYTM", "PHONEPE", "GPAY",
            "GOOGLEPAY", "BHARATPE", "OKICICI", "OKHDFCBANK", "OKSBI", "OKAXIS", "YBL", "AXL", "IBL",
            "SBI", "AXIS", "KOTAK", "ICICI", "HDFCBANK", "STATE BANK OF INDIA", "HDFC BANK",
            "ICICI BANK", "AXIS BANK", "KOTAK MAHINDRA BANK", "BANK OF BARODA", "PUNJAB NATIONAL BANK"
    );

    public record ParsedNarration(String channel, String utrOrRef, String counterparty) {
        public boolean hasChannel() {
            return channel != null && !channel.isBlank() && !"OTHER".equalsIgnoreCase(channel);
        }

        public boolean hasCounterparty() {
            return counterparty != null && !counterparty.isBlank();
        }

        public boolean hasUtrOrRef() {
            return utrOrRef != null && !utrOrRef.isBlank();
        }
    }

    private BankNarrationParser() {
    }

    public static ParsedNarration parse(String description, String existingReference) {
        String desc = description == null ? "" : description.replaceAll("\\s+", " ").trim();
        String existingRef = existingReference == null ? "" : existingReference.trim();
        if (desc.isBlank() && existingRef.isBlank()) {
            return new ParsedNarration("OTHER", "", "");
        }
        String channel = detectChannel(desc, existingRef);
        String utr = extractUtrOrRef(desc, existingRef, channel);
        String counterparty = extractCounterparty(desc, utr);
        return new ParsedNarration(channel, utr, counterparty);
    }

    /**
     * Produces a concise single-line business remark stripped of tokens already shown
     * in adjacent columns (Channel pill, Ref/UTR, IFSC codes, masked card/account numbers, counterparty).
     */
    public static String compactSummary(String description, String existingReference, ParsedNarration parsed) {
        if (description == null || description.isBlank()) return "";
        String normDesc = description.replaceAll("\\s+", " ").trim();
        String normRef = existingReference == null ? "" : existingReference.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        String normRefNoZero = normRef.replaceFirst("^0+", "");
        String channelUpper = parsed != null && parsed.channel() != null ? parsed.channel().toUpperCase(Locale.ROOT) : "";
        String partyUpper = parsed != null && parsed.counterparty() != null ? parsed.counterparty().trim().toUpperCase(Locale.ROOT) : "";
        String utrUpper = parsed != null && parsed.utrOrRef() != null ? parsed.utrOrRef().trim().toUpperCase(Locale.ROOT) : "";

        String[] segments = normDesc.split("[/|\\\\]+|\\s-\\s|-");
        if (segments.length <= 1) {
            return normDesc;
        }
        StringBuilder out = new StringBuilder();
        for (String raw : segments) {
            String seg = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
            if (seg.length() < 2) continue;
            String upper = seg.toUpperCase(Locale.ROOT);
            if (!channelUpper.isBlank() && upper.equals(channelUpper)) continue;
            if (!partyUpper.isBlank() && upper.equals(partyUpper)) continue;
            if (!utrUpper.isBlank() && upper.equals(utrUpper)) continue;
            String alnum = upper.replaceAll("[^A-Z0-9]", "");
            String alnumNoZero = alnum.replaceFirst("^0+", "");
            if (!alnumNoZero.isBlank() && alnumNoZero.length() >= 6
                    && (alnumNoZero.equals(normRefNoZero) || normRefNoZero.endsWith(alnumNoZero))) continue;
            if (IFSC_PATTERN.matcher(upper).matches()) continue;
            if (upper.matches("^[X*]{3,}\\d{0,6}$") || upper.matches("^\\d{10,20}$")) continue;
            if (Set.of("UPI", "NEFT", "IMPS", "RTGS", "CHQ", "ACH", "NACH", "POS", "P2A", "P2M", "P2P",
                    "CR", "DR", "MB", "IB", "UTIB", "HDFC", "SBIN", "ICIC", "CNRB", "KKBK", "BARB",
                    "PUNB", "YESB", "IDFB", "NA", "NULL").contains(upper)) continue;
            if (!out.isEmpty()) out.append(" · ");
            out.append(seg);
        }
        return out.toString();
    }

    public static String detectChannel(String description, String reference) {
        String hay = ((description == null ? "" : description) + " " + (reference == null ? "" : reference))
                .toUpperCase(Locale.ROOT);
        if (containsAnyWord(hay, "CHRG", "CHARGE", "CHARGES", "SMS CHG", "MIN BAL", "FOLIO CHG", "ANNUAL FEE", "BANK FEE", "MAINT CHG", "PENALTY", "PENAL", "INTEREST", "INT PAID", "INT.PD")) {
            if (!containsWord(hay, "UPI") || containsWord(hay, "CHG") || containsWord(hay, "CHRG") || containsWord(hay, "CHARGES") || containsWord(hay, "FEE")) {
                if (containsWord(hay, "CHG") || containsWord(hay, "CHRG") || containsWord(hay, "CHARGES")
                        || containsWord(hay, "FEE") || containsWord(hay, "PENALTY") || containsWord(hay, "MIN BAL")) {
                    return "CHARGES";
                }
            }
        }
        if (containsWord(hay, "UPI") || hay.contains("@OK") || hay.contains("@YBL") || hay.contains("@AXL") || hay.contains("@IBL") || hay.contains("@PAYTM")) {
            return "UPI";
        }
        if (containsWord(hay, "NEFT")) return "NEFT";
        if (containsWord(hay, "RTGS")) return "RTGS";
        if (containsWord(hay, "IMPS")) return "IMPS";
        if (containsWord(hay, "CHQ") || containsWord(hay, "CHEQUE") || containsWord(hay, "CLG") || containsWord(hay, "CLEARING")) {
            return "CHQ";
        }
        if (containsWord(hay, "ACH") || containsWord(hay, "NACH") || containsWord(hay, "ECS") || containsWord(hay, "MANDATE")) {
            return "ACH";
        }
        if (containsWord(hay, "POS") || containsWord(hay, "ATM") || containsWord(hay, "DEBIT CARD") || containsWord(hay, "CARD")) {
            return "POS";
        }
        if (containsWord(hay, "INT") || containsWord(hay, "INTEREST")) {
            return "CHARGES";
        }
        return "OTHER";
    }

    public static String extractUtrOrRef(String description, String existingRef, String channel) {
        String ref = existingRef == null ? "" : existingRef.trim();
        if (!ref.isBlank() && !isSyntheticRef(ref) && !ref.matches("^0+$")) {
            return ref;
        }
        String desc = description == null ? "" : description;
        if ("CHQ".equals(channel)) {
            Matcher chq = CHEQUE_NO_PATTERN.matcher(desc);
            if (chq.find()) return chq.group(1);
        }
        Matcher digits = DIGITS_REF_PATTERN.matcher(desc);
        while (digits.find()) {
            String candidate = digits.group(1);
            if (candidate.length() >= 10 && candidate.length() <= 20 && !candidate.matches("^0+$")) {
                return candidate;
            }
        }
        String upperDesc = desc.toUpperCase(Locale.ROOT);
        Matcher alnum = ALNUM_UTR_PATTERN.matcher(upperDesc);
        while (alnum.find()) {
            String candidate = alnum.group(1);
            if (candidate.length() >= 10 && candidate.length() <= 24
                    && !IFSC_PATTERN.matcher(candidate).matches()
                    && candidate.matches(".*\\d.*")) {
                return candidate;
            }
        }
        Matcher anyDigits = DIGITS_REF_PATTERN.matcher(desc);
        if (anyDigits.find()) {
            return anyDigits.group(1);
        }
        return "";
    }

    public static boolean isSyntheticRef(String ref) {
        if (ref == null || ref.isBlank()) return true;
        String trimmed = ref.trim().toUpperCase(Locale.ROOT);
        if (trimmed.matches("^REF-\\d+$")) return true;
        return trimmed.equals("-") || trimmed.equals("--") || trimmed.equals("N/A") || trimmed.equals("NA")
                || trimmed.equals("NULL") || trimmed.equals("NONE") || trimmed.equals(".")
                || trimmed.matches("^0+$");
    }

    private static String extractCounterparty(String description, String extractedUtr) {
        if (description == null || description.isBlank()) return "";
        String[] segments = description.split("[/|\\\\]+|\\s-\\s|-");
        String best = "";
        int bestScore = -1;
        for (String rawSeg : segments) {
            String seg = rawSeg == null ? "" : rawSeg.replaceAll("\\s+", " ").trim();
            if (seg.length() < 3) continue;
            if (UPI_HANDLE_PATTERN.matcher(seg).matches()) continue;
            String upper = seg.toUpperCase(Locale.ROOT);
            if (IFSC_PATTERN.matcher(upper).matches()) continue;
            if (!extractedUtr.isBlank() && upper.equals(extractedUtr.toUpperCase(Locale.ROOT))) continue;
            if (seg.matches("^[0-9X*\\-]+$")) continue;
            if (SKIP_TOKENS.contains(upper)) continue;

            // Clean leading direction/channel noise if single long unsplit narration
            String cleaned = cleanLeadNoise(seg);
            if (cleaned.length() < 3) continue;
            String cleanedUpper = cleaned.toUpperCase(Locale.ROOT);
            if (SKIP_TOKENS.contains(cleanedUpper) || IFSC_PATTERN.matcher(cleanedUpper).matches()) continue;

            boolean hasLetters = cleaned.matches(".*[A-Za-z]{2,}.*");
            if (!hasLetters) continue;
            boolean maskedCard = cleanedUpper.contains("XXXX") || cleanedUpper.matches(".*\\d{6,}.*");
            int score = cleaned.length() + (maskedCard ? -25 : 20);
            if (score > bestScore) {
                bestScore = score;
                best = cleaned;
            }
        }
        if (best.length() > 42) {
            best = best.substring(0, 42).trim();
        }
        return best;
    }

    private static String cleanLeadNoise(String s) {
        String out = s.trim();
        for (String prefix : List.of("BY TRANSFER-", "TO TRANSFER-", "TO ", "BY ", "FROM ", "TRF TO ", "TRF FRM ")) {
            if (out.toUpperCase(Locale.ROOT).startsWith(prefix)) {
                out = out.substring(prefix.length()).trim();
            }
        }
        return out;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(word) + "(?![A-Z0-9])").matcher(text).find();
    }

    private static boolean containsAnyWord(String text, String... words) {
        for (String w : words) {
            if (containsWord(text, w) || (w.contains(" ") && text.contains(w))) return true;
        }
        return false;
    }
}
