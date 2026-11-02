package org.example.api.tally;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class TallyAutomationApiClient {
    private final HttpClient http = org.example.api.ApiRuntime.HTTP;
    private final ObjectMapper json = org.example.api.ApiRuntime.JSON;
    private final String base;

    public TallyAutomationApiClient() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public record ClientProfileDto(
            Long id,
            String clientName,
            String tallyCompanyName,
            String gstin,
            String stateCode,
            String defaultMode,
            String defaultPurchaseLedger,
            String defaultRoundoffLedger,
            boolean showGstColumns,
            boolean showHsnColumn,
            boolean showCostCenter,
            boolean showPoReference,
            String excelColumnMappingJson
    ) {}

    public record UploadBatchDto(
            Long id,
            Long clientProfileId,
            String clientName,
            String batchReference,
            int totalFiles,
            int processedCount,
            int apiCallsConsumed,
            String status,
            String createdBy,
            OffsetDateTime createdAt
    ) {}

    public record BillLineItemDto(
            Long id,
            Long billRecordId,
            int lineNumber,
            String extractedItemName,
            String mappedTallyStockItem,
            String hsnSac,
            BigDecimal quantity,
            String uom,
            BigDecimal rate,
            BigDecimal discountAmount,
            BigDecimal taxRate,
            BigDecimal taxableAmount,
            BigDecimal lineAccuracyPercentage,
            String lineErrorNotes
    ) {}

    public record BillRecordDto(
            Long id,
            Long batchId,
            Long clientProfileId,
            String voucherMode,
            String invoiceNumber,
            LocalDate invoiceDate,
            String vendorNamePrinted,
            String vendorGstin,
            String tallyPartyLedger,
            String tallyPurchaseLedger,
            String costCenter,
            String poReference,
            BigDecimal taxableAmount,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            BigDecimal roundOff,
            BigDecimal grandTotal,
            BigDecimal accuracyPercentage,
            String validationNotes,
            String attachedFilePaths,
            String status,
            String tallyVoucherGuid,
            List<BillLineItemDto> lineItems
    ) {}

    public record BatchSummaryMetrics(
            int totalBills,
            int highAccuracyCount,
            int needsReviewCount,
            int exportedCount,
            BigDecimal totalTaxable,
            BigDecimal totalGrandTotal
    ) {}

    public record TallyExportRequest(
            Long batchId,
            List<Long> billIds,
            String exportFormat,
            String tallyUrl
    ) {}

    public record TallyExportResponse(
            boolean success,
            int exportedCount,
            int failedCount,
            String message,
            String payloadContent
    ) {}

    public List<ClientProfileDto> listProfiles() {
        return get("/api/tally-automation/profiles", new TypeReference<List<ClientProfileDto>>() {});
    }

    public ClientProfileDto saveProfile(ClientProfileDto dto) {
        return post("/api/tally-automation/profiles", dto, ClientProfileDto.class);
    }

    public void deleteProfile(Long id) {
        delete("/api/tally-automation/profiles/" + id);
    }

    public UploadBatchDto createBatch(Long clientProfileId, String reference, int totalFiles, String user) {
        String path = "/api/tally-automation/batches?totalFiles=" + totalFiles
                + (clientProfileId != null ? "&clientProfileId=" + clientProfileId : "")
                + (reference != null ? "&reference=" + enc(reference) : "")
                + (user != null ? "&user=" + enc(user) : "");
        return post(path, null, UploadBatchDto.class);
    }

    public UploadBatchDto getBatch(Long batchId) {
        return get("/api/tally-automation/batches/" + batchId, UploadBatchDto.class);
    }

    public List<BillRecordDto> listBills(Long batchId) {
        return get("/api/tally-automation/batches/" + batchId + "/bills", new TypeReference<List<BillRecordDto>>() {});
    }

    public BatchSummaryMetrics metrics(Long batchId) {
        return get("/api/tally-automation/batches/" + batchId + "/metrics", BatchSummaryMetrics.class);
    }

    public BillRecordDto saveBill(BillRecordDto dto) {
        return post("/api/tally-automation/bills", dto, BillRecordDto.class);
    }

    public void deleteBill(Long id) {
        delete("/api/tally-automation/bills/" + id);
    }

    public TallyExportResponse exportToTally(TallyExportRequest request) {
        return post("/api/tally-automation/export", request, TallyExportResponse.class);
    }

    /* Helper HTTP methods */
    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private <T> T get(String path, Class<T> clazz) {
        return req("GET", path, null, clazz, null);
    }

    private <T> T get(String path, TypeReference<T> type) {
        return req("GET", path, null, null, type);
    }

    private <T> T post(String path, Object body, Class<T> clazz) {
        return req("POST", path, body, clazz, null);
    }

    private void delete(String path) {
        req("DELETE", path, null, Void.class, null);
    }

    private <T> T req(String method, String path, Object body, Class<T> clazz, TypeReference<T> typeRef) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(20));
            org.example.api.ApiSession.authorize(b);

            if ("POST".equalsIgnoreCase(method)) {
                String payload = body == null ? "" : json.writeValueAsString(body);
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            } else if ("DELETE".equalsIgnoreCase(method)) {
                b.DELETE();
            } else {
                b.GET();
            }

            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new IOException("Server returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            if (clazz == Void.class) return null;
            if (clazz != null) return json.readValue(resp.body(), clazz);
            return json.readValue(resp.body(), typeRef);
        } catch (Exception ex) {
            throw new RuntimeException("Tally Automation API call failed: " + ex.getMessage(), ex);
        }
    }
}
