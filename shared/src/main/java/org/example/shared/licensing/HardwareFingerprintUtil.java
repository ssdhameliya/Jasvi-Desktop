package org.example.shared.licensing;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise hardware identification utility.
 * Generates an immutable, composite SHA-256 hardware hash bound to the
 * physical machine (Motherboard UUID + CPU Processor ID + OS Drive Volume Serial).
 */
public final class HardwareFingerprintUtil {

    private static final AtomicReference<String> CACHED_HASH = new AtomicReference<>();
    private static final AtomicReference<String> CACHED_DISPLAY_ID = new AtomicReference<>();

    private HardwareFingerprintUtil() {}

    /**
     * Returns the 64-character SHA-256 composite machine hash.
     */
    public static String getMachineHash() {
        String cached = CACHED_HASH.get();
        if (cached != null) {
            return cached;
        }
        String computed = computeMachineHash();
        CACHED_HASH.compareAndSet(null, computed);
        return computed;
    }

    /**
     * Returns the human-readable 16-character Machine ID (e.g. A8F9-42E1-7BC3-9901).
     */
    public static String getDisplayMachineId() {
        String cached = CACHED_DISPLAY_ID.get();
        if (cached != null) {
            return cached;
        }
        String hash = getMachineHash();
        String display = formatDisplayId(hash);
        CACHED_DISPLAY_ID.compareAndSet(null, display);
        return display;
    }

    private static String computeMachineHash() {
        StringBuilder raw = new StringBuilder();

        if (isWindows()) {
            raw.append("MB:").append(readWindowsMotherboardUuid()).append("|");
            raw.append("CPU:").append(readWindowsCpuId()).append("|");
            raw.append("DISK:").append(readWindowsDriveSerial()).append("|");
        } else {
            raw.append("MAC:").append(readPrimaryMacAddress()).append("|");
            raw.append("HOST:").append(System.getProperty("user.name", "unknown")).append("|");
        }

        // Always append primary MAC as secondary stabilizer
        raw.append("MAC:").append(readPrimaryMacAddress());

        return sha256Hex(raw.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String readWindowsMotherboardUuid() {
        String out = runProcess("powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_ComputerSystemProduct).UUID");
        if (out.isBlank() || out.equalsIgnoreCase("unknown")) {
            out = runProcess("wmic", "csproduct", "get", "uuid");
        }
        return cleanValue(out, "DEFAULT-MB-UUID");
    }

    private static String readWindowsCpuId() {
        String out = runProcess("powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_Processor).ProcessorId");
        if (out.isBlank() || out.equalsIgnoreCase("unknown")) {
            out = runProcess("wmic", "cpu", "get", "processorid");
        }
        return cleanValue(out, "DEFAULT-CPU-ID");
    }

    private static String readWindowsDriveSerial() {
        String out = runProcess("powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_LogicalDisk -Filter 'DeviceID=''C:''').VolumeSerialNumber");
        if (out.isBlank() || out.equalsIgnoreCase("unknown")) {
            out = runProcess("wmic", "logicaldisk", "where", "DeviceID='C:'", "get", "volumeserialnumber");
        }
        return cleanValue(out, "DEFAULT-DRIVE-SERIAL");
    }

    private static String readPrimaryMacAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (!ni.isLoopback() && !ni.isVirtual() && ni.isUp()) {
                        byte[] mac = ni.getHardwareAddress();
                        if (mac != null && mac.length == 6) {
                            StringBuilder sb = new StringBuilder();
                            for (byte b : mac) {
                                sb.append(String.format("%02X", b));
                            }
                            return sb.toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fallback
        }
        return "DEFAULT-MAC";
    }

    private static String runProcess(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("UUID")
                            && !trimmed.equalsIgnoreCase("ProcessorId")
                            && !trimmed.equalsIgnoreCase("VolumeSerialNumber")) {
                        sb.append(trimmed);
                    }
                }
            }
            process.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanValue(String val, String fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        return val.replaceAll("[^a-zA-Z0-9-]", "").toUpperCase(Locale.ROOT);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not supported by JVM", e);
        }
    }

    private static String formatDisplayId(String hash) {
        if (hash == null || hash.length() < 16) {
            return "JASVI-UNASSIGNED";
        }
        return String.format("%s-%s-%s-%s",
                hash.substring(0, 4),
                hash.substring(4, 8),
                hash.substring(8, 12),
                hash.substring(12, 16)
        );
    }
}
