package org.example.controller;

/** One-shot selection used when the quotation register or enquiries register opens the full-page editor. */
public final class QuotationEditorContext {
    public record EnquirySeed(long enquiryId, int customerId, String customerName, String source, String remarks) {}

    private static Integer quotationId;
    private static EnquirySeed enquirySeed;

    private QuotationEditorContext() {}

    public static synchronized void open(Integer id) {
        quotationId = id;
        enquirySeed = null;
    }

    public static synchronized void openFromEnquiry(long enquiryId, int customerId, String customerName, String source, String remarks) {
        quotationId = null;
        enquirySeed = new EnquirySeed(enquiryId, customerId, customerName, source, remarks);
    }

    public static synchronized Integer consume() {
        Integer id = quotationId;
        quotationId = null;
        return id;
    }

    public static synchronized EnquirySeed consumeEnquirySeed() {
        EnquirySeed seed = enquirySeed;
        enquirySeed = null;
        return seed;
    }

    public static synchronized void clear() {
        quotationId = null;
        enquirySeed = null;
    }
}
