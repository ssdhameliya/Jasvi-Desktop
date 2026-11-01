package org.example.server.system;

import com.sun.management.OperatingSystemMXBean;
import org.example.server.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/system/telemetry")
public class ServerTelemetryController {

    @Value("${dse.deployment.environment:LOCAL}")
    private String deploymentEnv;

    @Value("${dse.server.address:127.0.0.1}")
    private String serverAddress;

    public record ServerTelemetryDto(
            String environment,
            String serverIp,
            long totalRamBytes,
            long availableRamBytes,
            double ramUsagePercent,
            long totalDiskBytes,
            long freeDiskBytes,
            double diskUsagePercent,
            String status,
            String timestamp
    ) {}

    @GetMapping
    public ServerTelemetryDto getTelemetry() {
        if (!"ADMIN".equalsIgnoreCase(CurrentUser.require().role()) && !"SUPER_ADMIN".equalsIgnoreCase(CurrentUser.require().role())) {
            CurrentUser.requirePermission("SETTINGS.MANAGE", "View server telemetry");
        }

        // 1. RAM / Memory Metrics (checks /proc/meminfo on Linux for true MemAvailable including reclaimable cache)
        long totalRam = 0;
        long availableRam = 0;

        try {
            File meminfo = new File("/proc/meminfo");
            if (meminfo.exists() && meminfo.canRead()) {
                List<String> lines = java.nio.file.Files.readAllLines(meminfo.toPath());
                for (String line : lines) {
                    if (line.startsWith("MemTotal:")) {
                        totalRam = parseMeminfoKb(line) * 1024L;
                    } else if (line.startsWith("MemAvailable:")) {
                        availableRam = parseMeminfoKb(line) * 1024L;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (totalRam <= 0 || availableRam <= 0) {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof OperatingSystemMXBean sunOs) {
                if (totalRam <= 0) totalRam = sunOs.getTotalMemorySize();
                if (availableRam <= 0) availableRam = sunOs.getFreeMemorySize();
            } else {
                if (totalRam <= 0) totalRam = Runtime.getRuntime().totalMemory();
                if (availableRam <= 0) availableRam = Runtime.getRuntime().freeMemory();
            }
        }

        long usedRam = Math.max(0, totalRam - availableRam);
        double ramUsagePercent = totalRam > 0
                ? Math.round(((double) usedRam / (double) totalRam) * 1000.0) / 10.0
                : 0.0;

        // 2. Disk Metrics
        File root = new File("/");
        long totalDisk = root.getTotalSpace();
        long freeDisk = root.getUsableSpace();
        if (totalDisk <= 0) {
            File[] roots = File.listRoots();
            if (roots != null && roots.length > 0) {
                totalDisk = roots[0].getTotalSpace();
                freeDisk = roots[0].getUsableSpace();
            }
        }
        long usedDisk = Math.max(0, totalDisk - freeDisk);
        double diskUsagePercent = totalDisk > 0
                ? Math.round(((double) usedDisk / (double) totalDisk) * 1000.0) / 10.0
                : 0.0;

        // 3. Environment & Server IP
        String env = deploymentEnv != null && !deploymentEnv.isBlank()
                ? deploymentEnv.trim().toUpperCase(Locale.ROOT)
                : "LOCAL";
        String serverIp;
        if ("PROD".equals(env)) {
            serverIp = "130.210.35.1";
        } else if ("UAT".equals(env)) {
            serverIp = "144.24.109.1";
        } else {
            serverIp = (serverAddress != null && !serverAddress.isBlank()) ? serverAddress.trim() : "127.0.0.1";
        }

        // 4. Overall Health Status
        String status = "HEALTHY";
        if (diskUsagePercent >= 85.0 || ramUsagePercent >= 92.0) {
            status = "CRITICAL";
        } else if (diskUsagePercent >= 75.0 || ramUsagePercent >= 85.0) {
            status = "WARNING";
        }

        return new ServerTelemetryDto(
                env,
                serverIp,
                totalRam,
                availableRam,
                ramUsagePercent,
                totalDisk,
                freeDisk,
                diskUsagePercent,
                status,
                Instant.now().toString()
        );
    }

    private static long parseMeminfoKb(String line) {
        try {
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return Long.parseLong(parts[1].trim());
            }
        } catch (Exception ignored) {}
        return 0L;
    }
}
