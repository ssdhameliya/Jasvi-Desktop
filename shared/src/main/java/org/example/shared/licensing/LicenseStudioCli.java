package org.example.shared.licensing;

import java.io.File;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class LicenseStudioCli {

    private static final String DEFAULT_PRIV_KEY =
            "MC4CAQAwBQYDK2VwBCIEIOzrtL2IsC8IKub6TbLVTvXk4kw/FmOmeA0UUEnHFvCA";

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equalsIgnoreCase(args[0])) {
            System.out.println("Jasvi License Studio CLI");
            System.out.println("Usage:");
            System.out.println("  java ... LicenseStudioCli issue <machineId> <companyName> <days> <tier> [outFile]");
            System.out.println("  java ... LicenseStudioCli verify <licenseJsonFile>");
            System.out.println("  java ... LicenseStudioCli machine");
            return;
        }

        String command = args[0].toLowerCase();
        switch (command) {
            case "machine" -> {
                System.out.println("MACHINE_HASH: " + HardwareFingerprintUtil.getMachineHash());
                System.out.println("DISPLAY_ID:   " + HardwareFingerprintUtil.getDisplayMachineId());
            }
            case "issue" -> {
                if (args.length < 5) {
                    System.err.println("Usage: issue <machineId> <companyName> <days> <tier> [outFile]");
                    System.exit(1);
                }
                String machineId = args[1].trim();
                String company = args[2].trim();
                int days = Integer.parseInt(args[3].trim());
                String tier = args[4].trim().toUpperCase();
                String outFile = args.length > 5 ? args[5].trim() : "jasvi.lic";

                Instant now = Instant.now();
                Instant validUntil = days < 0 ? now.minus(Math.abs(days), ChronoUnit.DAYS) : now.plus(days, ChronoUnit.DAYS);

                LicensePayload payload = new LicensePayload(
                        "JASVI-" + tier + "-" + System.currentTimeMillis() % 1000000,
                        company,
                        machineId,
                        tier,
                        now.minus(1, ChronoUnit.DAYS),
                        validUntil,
                        14,
                        "GOLD".equalsIgnoreCase(tier) ? 5 : 1,
                        List.of("GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP")
                );

                PrivateKey privKey = Ed25519CryptoUtil.loadPrivateKey(DEFAULT_PRIV_KEY);
                String sig = Ed25519CryptoUtil.signString(payload.toCanonicalString(), privKey);

                String json = String.format("""
                        {
                          "licenseKey": "%s",
                          "companyName": "%s",
                          "machineHash": "%s",
                          "tier": "%s",
                          "validFrom": "%s",
                          "validUntil": "%s",
                          "gracePeriodDays": %d,
                          "maxUsers": %d,
                          "features": ["GST_INVOICE", "EWAY_BILL", "CLOUD_BACKUP"],
                          "signature": "%s"
                        }
                        """,
                        payload.licenseKey(),
                        payload.companyName(),
                        payload.machineHash(),
                        payload.tier(),
                        payload.validFrom(),
                        payload.validUntil(),
                        payload.gracePeriodDays(),
                        payload.maxUsers(),
                        sig
                );

                File out = new File(outFile);
                Files.writeString(out.toPath(), json);
                System.out.println("SUCCESS: License issued to " + out.getAbsolutePath());
                System.out.println(json);
            }
            case "verify" -> {
                if (args.length < 2) {
                    System.err.println("Usage: verify <licenseJsonFile>");
                    System.exit(1);
                }
                String content = Files.readString(new File(args[1]).toPath());
                System.out.println("Verifying license content...");
                // Simple regex extraction for verification
                String sig = extractField(content, "signature");
                String canonical = extractField(content, "licenseKey") + "|"
                        + extractField(content, "companyName") + "|"
                        + extractField(content, "machineHash").toUpperCase() + "|"
                        + extractField(content, "tier") + "|"
                        + Instant.parse(extractField(content, "validFrom")).getEpochSecond() + "|"
                        + Instant.parse(extractField(content, "validUntil")).getEpochSecond() + "|"
                        + extractField(content, "gracePeriodDays") + "|"
                        + extractField(content, "maxUsers") + "|"
                        + "CLOUD_BACKUP,EWAY_BILL,GST_INVOICE";

                boolean valid = Ed25519CryptoUtil.verifyStringWithEmbeddedKey(canonical, sig);
                System.out.println("SIGNATURE_VALID: " + valid);
            }
            default -> System.err.println("Unknown command: " + command);
        }
    }

    private static String extractField(String json, String field) {
        String marker = "\"" + field + "\":";
        int idx = json.indexOf(marker);
        if (idx == -1) return "";
        int start = idx + marker.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\"')) start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != '\"' && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\n') end++;
        return json.substring(start, end).trim();
    }
}
