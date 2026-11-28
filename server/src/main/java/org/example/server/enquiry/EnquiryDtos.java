package org.example.server.enquiry;

import java.util.List;

/** Typed Java 25 DTOs for public website enquiries, feedback, Desktop register, and quotation handoff. */
public final class EnquiryDtos {
    private EnquiryDtos() {}

    public record PublicRfqRequest(
            String name,
            String company,
            String email,
            String phone,
            String cityState,
            String productSeries,
            String npsSize,
            String pressureClass,
            String bodyMaterial,
            String quantity,
            String subject,
            String message,
            String sourcePage,
            Boolean whatsappInitiated,
            String websiteUrl
    ) {}

    public record PublicFeedbackRequest(
            String name,
            String company,
            String email,
            Integer rating,
            String message,
            String sourcePage,
            String websiteUrl
    ) {}

    public record PublicSubmitResponse(
            boolean success,
            String enquiryNo,
            String message
    ) {}

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

    public record ActivityItem(
            long id,
            String actionType,
            String detail,
            String actorUsername,
            String createdAt
    ) {}

    public record EnquiryDetailResponse(
            EnquiryRow enquiry,
            List<ActivityItem> activity
    ) {}

    public record StatusUpdateRequest(
            String status,
            String priority,
            String internalNotes,
            String note,
            Integer assignedUserId,
            long rowVersion
    ) {}

    public record PrepareQuotationResponse(
            long enquiryId,
            String enquiryNo,
            int customerId,
            String customerCode,
            String customerName,
            String suggestedSource,
            String suggestedRemarks
    ) {}

    public record LinkQuotationRequest(
            int quotationId
    ) {}

    public record Ok(boolean success, String message) {}

    /** Domain event published inside @Transactional when a genuine public enquiry is saved. */
    public record EnquirySubmittedEvent(
            long enquiryId,
            String enquiryNo,
            String enquiryType,
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
            String message,
            Integer rating,
            String sourcePage,
            boolean whatsappInitiated,
            String mobileViewToken
    ) {}
}
