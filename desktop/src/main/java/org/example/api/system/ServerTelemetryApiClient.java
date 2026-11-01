package org.example.api.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class ServerTelemetryApiClient {
    private final ObjectMapper json = ApiRuntime.JSON;

    public record TelemetryDto(
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

    public TelemetryDto getTelemetry() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + "/api/system/telemetry"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
            ApiSession.authorize(builder);
            HttpResponse<String> response = ApiRuntime.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException(ApiRuntime.errorMessage("Telemetry API", response.statusCode(), response.body()));
            return json.readValue(response.body(), TelemetryDto.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read server telemetry: " + e.getMessage(), e);
        }
    }

    private static String base() {
        String base = ConfigManager.getDataApiBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }
}
