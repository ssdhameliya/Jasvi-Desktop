package org.example.server.licensing;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CloudflareTunnelService {

    private static final Logger log = LoggerFactory.getLogger(CloudflareTunnelService.class);
    private static final Pattern TUNNEL_URL_PATTERN = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com");
    private static final String DOWNLOAD_URL_WINDOWS = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe";

    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
    private final AtomicReference<String> status = new AtomicReference<>("OFFLINE");
    private final AtomicReference<String> liveUrl = new AtomicReference<>(null);
    private final AtomicReference<Instant> startedAt = new AtomicReference<>(null);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private int currentPort = 8080;

    public synchronized Map<String, Object> startTunnel(int port) {
        if ("ONLINE".equals(status.get()) && currentProcess.get() != null && currentProcess.get().isAlive()) {
            return getStatus();
        }

        stopTunnel();
        this.currentPort = port > 0 ? port : 8080;
        status.set("STARTING");
        lastError.set(null);
        liveUrl.set(null);

        Thread.ofVirtual().name("cloudflare-tunnel-worker").start(() -> {
            try {
                Path binaryPath = resolveOrDownloadBinary();
                if (binaryPath == null || !Files.exists(binaryPath)) {
                    status.set("OFFLINE");
                    lastError.set("Cloudflared binary not available and auto-download failed.");
                    log.error("[CloudflareTunnel] {}", lastError.get());
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(
                        binaryPath.toAbsolutePath().toString(),
                        "tunnel",
                        "--url", "http://localhost:" + currentPort,
                        "--no-autoupdate"
                );
                pb.redirectErrorStream(true);

                Process proc = pb.start();
                currentProcess.set(proc);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[cloudflared] {}", line);
                        Matcher m = TUNNEL_URL_PATTERN.matcher(line);
                        if (m.find()) {
                            String url = m.group();
                            liveUrl.set(url);
                            startedAt.set(Instant.now());
                            status.set("ONLINE");
                            log.info("[CloudflareTunnel] Live HTTPS endpoint established: {}", url);
                        }
                    }
                }

                int exitCode = proc.waitFor();
                log.info("[CloudflareTunnel] Process exited with code {}", exitCode);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                lastError.set("Tunnel error: " + e.getMessage());
                log.error("[CloudflareTunnel] Execution error", e);
            } finally {
                status.set("OFFLINE");
                liveUrl.set(null);
                startedAt.set(null);
                currentProcess.set(null);
            }
        });

        // Wait up to 3 seconds for initial detection
        long startWait = System.currentTimeMillis();
        while (System.currentTimeMillis() - startWait < 3000) {
            if ("ONLINE".equals(status.get())) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return getStatus();
    }

    public synchronized Map<String, Object> stopTunnel() {
        Process proc = currentProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            try {
                proc.destroy();
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }
        status.set("OFFLINE");
        liveUrl.set(null);
        startedAt.set(null);
        return getStatus();
    }

    public Map<String, Object> getStatus() {
        String currentStatus = status.get();
        String url = liveUrl.get();
        Instant start = startedAt.get();
        long uptime = (start != null && "ONLINE".equals(currentStatus))
                ? Duration.between(start, Instant.now()).getSeconds()
                : 0L;

        return Map.of(
                "status", currentStatus,
                "liveUrl", url != null ? url : "",
                "uptimeSeconds", uptime,
                "port", currentPort,
                "startedAt", start != null ? start.toString() : "",
                "error", lastError.get() != null ? lastError.get() : ""
        );
    }

    private Path resolveOrDownloadBinary() {
        // 1. Check local runtime/bin/cloudflared.exe
        Path p1 = Path.of("runtime", "bin", "cloudflared.exe");
        if (Files.isExecutable(p1)) return p1;

        // 2. Check user home .jasvi/bin
        String userHome = System.getProperty("user.home", ".");
        Path jasviBin = Path.of(userHome, ".jasvi", "bin", "cloudflared.exe");
        if (Files.isExecutable(jasviBin)) return jasviBin;

        // 3. Check system PATH
        try {
            Process which = new ProcessBuilder("where.exe", "cloudflared").start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(which.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.isBlank()) {
                    Path sysPath = Path.of(line.trim());
                    if (Files.exists(sysPath)) return sysPath;
                }
            }
        } catch (Exception ignored) {}

        // 4. Download single portable binary
        try {
            log.info("[CloudflareTunnel] Downloading official portable cloudflared binary to: {}", jasviBin);
            Files.createDirectories(jasviBin.getParent());
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DOWNLOAD_URL_WINDOWS))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 200) {
                try (InputStream in = resp.body()) {
                    Files.copy(in, jasviBin, StandardCopyOption.REPLACE_EXISTING);
                }
                jasviBin.toFile().setExecutable(true);
                log.info("[CloudflareTunnel] Download complete ({} bytes)", Files.size(jasviBin));
                return jasviBin;
            } else {
                log.error("[CloudflareTunnel] Download failed with HTTP {}", resp.statusCode());
            }
        } catch (Exception e) {
            log.error("[CloudflareTunnel] Failed downloading cloudflared", e);
        }

        return null;
    }

    @PreDestroy
    public void cleanup() {
        stopTunnel();
    }
}
