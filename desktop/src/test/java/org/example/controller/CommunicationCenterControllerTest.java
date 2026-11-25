package org.example.controller;

import org.example.api.support.SupportApiClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommunicationCenterControllerTest {

    @Test
    void buildRowsNumbersAttemptsChronologicallyEvenWhenApiReturnsNewestFirst() {
        List<SupportApiClient.CommunicationRow> rawNewestFirst = List.of(
                new SupportApiClient.CommunicationRow(30, "SALE", 42, "IN/17-11-2026/0001", "EMAIL",
                        "ssdhameliya@gmail.com, ssdhameliya123@gmail.com", "Sales Invoice IN/17-11-2026/0001",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-18T00:07:00+05:30"),
                new SupportApiClient.CommunicationRow(20, "SALE", 42, "IN/17-11-2026/0001", "EMAIL",
                        "ssdhameliya@gmail.com, ssdhameliya123@gmail.com", "Sales Invoice IN/17-11-2026/0001",
                        "FAILED", "SMTP rejected", "Shailesh Dhameliya", "2026-11-17T23:40:00+05:30"),
                new SupportApiClient.CommunicationRow(10, "SALE", 42, "IN/17-11-2026/0001", "EMAIL",
                        "ssdhameliya@gmail.com, ssdhameliya123@gmail.com", "Sales Invoice IN/17-11-2026/0001",
                        "FAILED", "Connection timeout", "Shailesh Dhameliya", "2026-11-17T23:10:00+05:30")
        );

        List<CommunicationCenterController.Row> rows = CommunicationCenterController.buildRows(rawNewestFirst);

        assertEquals(3, rows.size());
        assertEquals("Resend #2 (3/3)", rows.get(0).attempts.get());
        assertEquals("Resend #1 (2/3)", rows.get(1).attempts.get());
        assertEquals("Original (1/3)", rows.get(2).attempts.get());
        assertEquals(List.of("ssdhameliya@gmail.com", "ssdhameliya123@gmail.com"), rows.get(0).recipients);
        assertEquals(3, rows.get(0).totalAttempts);
    }

    @Test
    void buildRowsKeepsSalesInvoiceSeparateFromPaymentReceiptForSameInvoiceId() {
        List<SupportApiClient.CommunicationRow> rawNewestFirst = List.of(
                new SupportApiClient.CommunicationRow(30, "SALE", 42, "IN/23-11-2026/0001", "EMAIL",
                        "ssdhameliya123@gmail.com, ssdhameliya@gmail.com",
                        "Sales Invoice IN/23-11-2026/0001 from Jasvi Creation",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T10:34:00+05:30"),
                new SupportApiClient.CommunicationRow(20, "SALE", 42, "IN/23-11-2026/0001", "EMAIL",
                        "ssdhameliya@gmail.com, ssdhameliya123@gmail.com",
                        "Payment Receipt for IN/23-11-2026/0001 from Jasvi Creation",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:42:00+05:30"),
                new SupportApiClient.CommunicationRow(10, "SALE", 42, "IN/23-11-2026/0001", "EMAIL",
                        "ssdhameliya123@gmail.com",
                        "WhatsApp (Payment Receipt): IN/23-11-2026/0001",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:42:00+05:30")
        );

        // Even with Channel = EMAIL on both Sales Invoice and Payment Receipt, first Sales Invoice must say Original!
        List<SupportApiClient.CommunicationRow> emailOnlyNewestFirst = List.of(rawNewestFirst.get(0), rawNewestFirst.get(1));
        List<CommunicationCenterController.Row> rows = CommunicationCenterController.buildRows(emailOnlyNewestFirst);

        assertEquals(2, rows.size());
        assertEquals("Original", rows.get(0).attempts.get());
        assertEquals("Original", rows.get(1).attempts.get());

        // Separate WhatsApp stream also distinguishes Payment Receipt from regular Invoice WhatsApp
        assertEquals("PAYMENT_RECEIPT", CommunicationCenterController.dispatchCategory("SALE", "WhatsApp (Payment Receipt): IN/23-11-2026/0001"));
        assertEquals("DOCUMENT_DISPATCH", CommunicationCenterController.dispatchCategory("SALE", "WhatsApp: IN/23-11-2026/0001"));
    }

    @Test
    void buildRowsParsesMultiRecipientWhatsappAndSeparatesChannelsAndZeroIdRecipients() {
        List<SupportApiClient.CommunicationRow> rawNewestFirst = List.of(
                new SupportApiClient.CommunicationRow(4, "SALE", 99, "IN/25-11-2026/0001", "WHATSAPP",
                        "919876543210, 919123456789", "WhatsApp: IN/25-11-2026/0001",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:45:00+05:30"),
                new SupportApiClient.CommunicationRow(3, "SALE", 99, "IN/25-11-2026/0001", "EMAIL",
                        "a@jasvi.in; b@jasvi.in", "Payment Receipt for IN/25-11-2026/0001",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:42:00+05:30"),
                new SupportApiClient.CommunicationRow(2, "WHATSAPP_MESSAGE", 0, "Direct", "WHATSAPP",
                        "919876543210", "Direct WhatsApp Message",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:30:00+05:30"),
                new SupportApiClient.CommunicationRow(1, "WHATSAPP_MESSAGE", 0, "Direct", "WHATSAPP",
                        "919123456789", "Direct WhatsApp Message",
                        "SENT", "", "Shailesh Dhameliya", "2026-11-25T09:20:00+05:30")
        );

        List<CommunicationCenterController.Row> rows = CommunicationCenterController.buildRows(rawNewestFirst);

        assertEquals(4, rows.size());
        assertEquals("Original", rows.get(0).attempts.get());
        assertEquals(List.of("919876543210", "919123456789"), rows.get(0).recipients);
        assertEquals("Original", rows.get(1).attempts.get());
        assertEquals(List.of("a@jasvi.in", "b@jasvi.in"), rows.get(1).recipients);
        assertEquals("Original", rows.get(2).attempts.get());
        assertEquals("Original", rows.get(3).attempts.get());
    }
}
