package org.example.api.tally;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.config.ConfigManager;
import org.example.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public class TallyAutomationApiClient {
    private static final Logger log = LoggerFactory.getLogger(TallyAutomationApiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String base;

    public TallyAutomationApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public record ClientProfileDto(
            Long id,
            String clientName,
            String tallyCompanyName,
            String gstin,
            String tradeName,
            String legalName,
            String address,
            String gstStatus,
            String taxpayerType,
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

    public record ClientDirectoryRowDto(
            Long id,
            String clientName,
            String tradeName,
            String legalName,
            String gstin,
            String stateCode,
            String address,
            String gstStatus,
            String taxpayerType,
            long templateCount,
            OffsetDateTime updatedAt
    ) {}

    public record GstPortalProfileDto(
            String gstin,
            String legalName,
            String tradeName,
            String address,
            String status,
            String taxpayerType,
            String stateCode,
            boolean isValid
    ) {}

    public record GstinVerificationRequest(
            String gstin
    ) {}

    public record TemplateColumnDef(
            int colOrder,
            String colKey,
            String colHeader,
            String canonicalField,
            String dataType,
            boolean isRequired
    ) {}

    public record TemplateHeaderFieldDef(
            String fieldKey,
            String label,
            String sampleValue,
            boolean isRequired
    ) {}

    public record TemplateVersionDto(
            Long id,
            Long clientProfileId,
            String templateName,
            String versionCode,
            boolean isActive,
            boolean isLocked,
            String vendorGstin,
            String matchingKeywords,
            String headerFieldsJson,
            String itemColumnsJson,
            String sampleFilePath,
            OffsetDateTime createdAt
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

    public List<ClientDirectoryRowDto> listClientsDirectory() {
        return get("/api/tally-automation/clients/directory", new TypeReference<List<ClientDirectoryRowDto>>() {});
    }

    public GstPortalProfileDto verifyGstin(String gstin) {
        return post("/api/tally-automation/clients/verify-gstin", new GstinVerificationRequest(gstin), GstPortalProfileDto.class);
    }

    public List<TemplateVersionDto> listTemplates(Long clientProfileId) {
        return get("/api/tally-automation/clients/" + clientProfileId + "/templates", new TypeReference<List<TemplateVersionDto>>() {});
    }

    public List<TemplateVersionDto> listTemplatesByGstin(String gstin) {
        return get("/api/tally-automation/templates/by-gstin/" + enc(gstin), new TypeReference<List<TemplateVersionDto>>() {});
    }

    public TemplateVersionDto saveTemplate(TemplateVersionDto dto) {
        return post("/api/tally-automation/templates", dto, TemplateVersionDto.class);
    }

    public TemplateVersionDto lockTemplate(Long templateId) {
        return post("/api/tally-automation/templates/" + templateId + "/lock", null, TemplateVersionDto.class);
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

    /* =========================================================================
       INTERNAL HTTP HELPERS
       ========================================================================= */

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        org.example.api.ApiSession.authorize(b);
        return b;
    }

    private <T> T get(String path, Class<T> clazz) {
        try {
            HttpRequest req = request(path).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            return mapper.readValue(resp.body(), clazz);
        } catch (Exception e) {
            throw new RuntimeException("API GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    private <T> T get(String path, TypeReference<T> ref) {
        try {
            HttpRequest req = request(path).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            return mapper.readValue(resp.body(), ref);
        } catch (Exception e) {
            throw new RuntimeException("API GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    private <T> T post(String path, Object body, Class<T> clazz) {
        try {
            String json = body != null ? mapper.writeValueAsString(body) : "";
            HttpRequest req = request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
            return mapper.readValue(resp.body(), clazz);
        } catch (Exception e) {
            throw new RuntimeException("API POST " + path + " failed: " + e.getMessage(), e);
        }
    }

    private void delete(String path) {
        try {
            HttpRequest req = request(path).DELETE().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        } catch (Exception e) {
            throw new RuntimeException("API DELETE " + path + " failed: " + e.getMessage(), e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
