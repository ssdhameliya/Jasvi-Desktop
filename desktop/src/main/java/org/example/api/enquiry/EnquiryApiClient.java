package org.example.api.enquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.api.ApiRuntime;
import org.example.api.ApiSession;
import org.example.config.ConfigManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Typed HTTP client for /api/enquiries endpoints used by EnquiriesController. */
public final class EnquiryApiClient {
    private final HttpClient http = ApiRuntime.HTTP;
    private final ObjectMapper json = ApiRuntime.JSON;

    public EnquiryPage page(int page, int size, String q, String type, String status) {
        String path = "/api/enquiries/page?page=" + Math.max(0, page)
                + "&size=" + Math.max(10, Math.min(size, 200))
                + "&q=" + enc(q)
                + "&type=" + enc(type)
                + "&status=" + enc(status);
        return req("GET", path, null, EnquiryPage.class);
    }

    public EnquiryDetailResponse detail(long id) {
        return req("GET", "/api/enquiries/" + id, null, EnquiryDetailResponse.class);
    }

    public EnquiryDetailResponse byNumber(String no) {
        return req("GET", "/api/enquiries/by-number?no=" + enc(no), null, EnquiryDetailResponse.class);
    }

    public EnquiryRow updateStatus(long id, StatusUpdateRequest body) {
        return req("PUT", "/api/enquiries/" + id + "/status", body, EnquiryRow.class);
    }

    public PrepareQuotationResponse prepareQuotation(long id) {
        return req("POST", "/api/enquiries/" + id + "/prepare-quotation", null, PrepareQuotationResponse.class);
    }

    public EnquiryRow linkQuotation(long id, int quotationId) {
        return req("POST", "/api/enquiries/" + id + "/link-quotation", new LinkQuotationRequest(quotationId), EnquiryRow.class);
    }

    private <T> T req(String method, String path, Object body, Class<T> type) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            ApiSession.authorize(b);
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) throw ApiSession.rejected("Enquiries request", r.body());
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new IllegalStateException(ApiRuntime.errorMessage("Enquiries API", r.statusCode(), r.body()));
            }
            return json.readValue(r.body(), type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach Enquiries API at " + base(), e);
        }
    }

    private static String base() {
        String b = ConfigManager.getDataApiBaseUrl();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    public record EnquiryRow(
            long id,
            String enquiryNo,
            String enquiryType,
            String status,
            String priority,
            String fullName,
            String companyName,
            String email,
            String phone,
            String cityState,
            String productSeries,
            String npsSize,
            String pressureClass,
            String bodyMaterial,
            String quantityText,
            String subject,
            String message,
            Integer rating,
            String sourcePage,
            boolean whatsappInitiated,
            Integer assignedUserId,
            String assignedUsername,
            Integer convertedPartyId,
            String convertedPartyName,
            Integer convertedQuotationId,
            String convertedQuotationNo,
            String internalNotes,
            String createdAt,
            String updatedAt,
            long rowVersion
    ) {}

    public record Metrics(
            long totalCount,
            long newCount,
            long inReviewCount,
            long quotedCount,
            long wonCount,
            long rfqCount,
            long feedbackCount,
            double averageRating
    ) {}

    public record EnquiryPage(
            List<EnquiryRow> rows,
            int page,
            int size,
            long totalRows,
            int totalPages,
            Metrics metrics
    ) {}

    public record ActivityItem(long id, String actionType, String detail, String actorUsername, String createdAt) {}
    public record EnquiryDetailResponse(EnquiryRow enquiry, List<ActivityItem> activity) {}
    public record StatusUpdateRequest(String status, String priority, String internalNotes, String note, Integer assignedUserId, long rowVersion) {}
    public record PrepareQuotationResponse(long enquiryId, String enquiryNo, int customerId, String customerCode, String customerName, String suggestedSource, String suggestedRemarks) {}
    public record LinkQuotationRequest(int quotationId) {}
}
