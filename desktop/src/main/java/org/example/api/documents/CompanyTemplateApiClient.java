package org.example.api.documents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class CompanyTemplateApiClient {

    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;
    private final String base;

    public CompanyTemplateApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public List<Map<String, Object>> getCatalog(String operation) {
        try {
            String path = "/api/templates/catalog" + (operation != null && !operation.isBlank() ? "?operation=" + enc(operation) : "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + ApiSession.getAuthToken())
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return json.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    public List<Map<String, Object>> getCompanyMappings(Long companyId) {
        try {
            Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/companies/" + cid + "/templates"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + ApiSession.getAuthToken())
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                return json.readValue(resp.body(), new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    public boolean saveCompanyMappings(Long companyId, Map<String, String> mappings) {
        try {
            Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
            String payload = json.writeValueAsString(mappings);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/companies/" + cid + "/templates"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + ApiSession.getAuthToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    public String resolveTemplate(Long companyId, String operation) {
        try {
            Long cid = (companyId == null || companyId <= 0) ? 1L : companyId;
            String op = (operation == null ? "SALES" : operation).toUpperCase();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/companies/" + cid + "/templates/" + op + "/resolve"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + ApiSession.getAuthToken())
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                Map<String, String> map = json.readValue(resp.body(), new TypeReference<Map<String, String>>() {});
                if (map != null && map.containsKey("templateCode")) {
                    return map.get("templateCode");
                }
            }
        } catch (Exception ignored) {
        }
        return defaultFallback(operation);
    }

    private static String defaultFallback(String op) {
        if (op == null) return "Jasvi_Sale";
        return switch (op.toUpperCase()) {
            case "PURCHASE" -> "Jasvi_Purchase";
            case "QUOTATION" -> "Jasvi_Quotation";
            case "RETURNS" -> "Jasvi_Return";
            default -> "Jasvi_Sale";
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
