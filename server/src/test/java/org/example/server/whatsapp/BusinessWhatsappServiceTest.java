package org.example.server.whatsapp;

import org.example.server.audit.AuditService;
import org.example.server.documents.CanonicalDocumentService;
import org.example.server.persistence.JpaNativeRepository;
import org.example.server.persistence.entity.WhatsappSenderChannelEntity;
import org.example.server.persistence.repository.WhatsappSenderChannelRepository;
import org.example.server.support.SupportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessWhatsappServiceTest {

    private WahaApiClient wahaClient;
    private WhatsappSenderChannelRepository channelRepo;
    private JpaNativeRepository jdbc;
    private CanonicalDocumentService canonicalDocs;
    private BusinessWhatsappService service;

    @BeforeEach
    void setUp() {
        wahaClient = mock(WahaApiClient.class);
        channelRepo = mock(WhatsappSenderChannelRepository.class);
        jdbc = mock(JpaNativeRepository.class);
        canonicalDocs = mock(CanonicalDocumentService.class);
        SupportService supportService = mock(SupportService.class);
        AuditService auditService = new AuditService(null);
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };

        service = new BusinessWhatsappService(wahaClient, channelRepo, jdbc, canonicalDocs, supportService, auditService, txManager);

        WhatsappSenderChannelEntity channel = new WhatsappSenderChannelEntity();
        channel.setId(1L);
        channel.setChannelCode("PRIMARY");
        channel.setDisplayName("Primary WhatsApp");
        channel.setSessionName("default");
        channel.setPhoneNumber("919999999999");
        channel.setIsDefault(true);
        channel.setIsActive(true);

        when(channelRepo.findFirstByIsDefaultTrueAndIsActiveTrue()).thenReturn(Optional.of(channel));
        when(channelRepo.findAllByIsActiveTrueOrderByIsDefaultDescDisplayNameAsc()).thenReturn(List.of(channel));
        when(wahaClient.getSessions(anyString(), anyString()))
                .thenReturn(List.of(new WahaDtos.SessionStatus("default", "WORKING")));
        when(jdbc.queryForList(contains("application_setting"), any())).thenReturn(List.of());
    }

    @Test
    void sendDocumentToMultipleRecipientsDispatchesAllAndWritesOneConsolidatedAuditRow() throws Exception {
        when(canonicalDocs.render("SALES_INVOICE", "IN/25-11-2026/0001", "pdf"))
                .thenReturn(new CanonicalDocumentService.Rendered("IN-0001.pdf", "application/pdf", new byte[]{1, 2, 3}));
        when(wahaClient.sendFile(anyString(), anyString(), eq("default"), eq("919876543210@c.us"),
                eq("IN-0001.pdf"), eq("application/pdf"), any(byte[].class), anyString()))
                .thenReturn(new WahaDtos.SendResponse(true, "Sent", "msg-1", "PRIMARY", "919876543210@c.us"));
        when(wahaClient.sendFile(anyString(), anyString(), eq("default"), eq("919123456789@c.us"),
                eq("IN-0001.pdf"), eq("application/pdf"), any(byte[].class), anyString()))
                .thenReturn(new WahaDtos.SendResponse(true, "Sent", "msg-2", "PRIMARY", "919123456789@c.us"));

        WahaDtos.SendResponse resp = service.sendDocument(new WahaDtos.DocumentSendRequest(
                "SALE", "IN/25-11-2026/0001", 42, null, "9876543210; 9123456789, 9876543210", "Hello", 1L
        ));

        assertTrue(resp.success());
        assertEquals("9876543210, 9123456789", resp.recipientPhone());
        verify(canonicalDocs, times(1)).render("SALES_INVOICE", "IN/25-11-2026/0001", "pdf");
        verify(wahaClient, times(2)).sendFile(anyString(), anyString(), eq("default"), anyString(),
                eq("IN-0001.pdf"), eq("application/pdf"), any(byte[].class), eq("Hello"));
        verify(jdbc, times(1)).update(
                contains("INSERT INTO communication_log"),
                eq("SALE"), eq(42), eq("9876543210, 9123456789"),
                eq("WhatsApp: IN/25-11-2026/0001"), eq("SENT"), isNull(), anyString(), anyString()
        );
        verify(jdbc, times(1)).update(eq("UPDATE sales_header SET whatsapp_sent = 1 WHERE id = ?"), eq(42));
    }

    @Test
    void sendDocumentPartialFailureStillUpdatesDocumentSentFlagWhenOneSucceedsAndLogsDiagnosticOnce() throws Exception {
        when(canonicalDocs.render("QUOTATION", "QTN/0009", "pdf"))
                .thenReturn(new CanonicalDocumentService.Rendered("QTN-0009.pdf", "application/pdf", new byte[]{1, 2}));
        when(wahaClient.sendFile(anyString(), anyString(), eq("default"), eq("919876543210@c.us"),
                anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(new WahaDtos.SendResponse(true, "Sent", "msg-1", "PRIMARY", "919876543210@c.us"));
        when(wahaClient.sendFile(anyString(), anyString(), eq("default"), eq("919000000000@c.us"),
                anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(new WahaDtos.SendResponse(false, "No LID for user", "", "PRIMARY", "919000000000@c.us"));

        WahaDtos.SendResponse resp = service.sendDocument(new WahaDtos.DocumentSendRequest(
                "QUOTATION", "QTN/0009", 77, null, "9876543210, 9000000000", "Quote attached", 1L
        ));

        assertFalse(resp.success());
        assertTrue(resp.message().contains("Delivered to 1/2 (9876543210)"));
        assertTrue(resp.message().contains("9000000000: No LID for user"));
        verify(jdbc, times(1)).update(
                contains("INSERT INTO communication_log"),
                eq("QUOTATION"), eq(77), eq("9876543210, 9000000000"),
                eq("WhatsApp: QTN/0009"), eq("FAILED"), contains("No LID for user"), anyString(), anyString()
        );
        verify(jdbc, times(1)).update(eq("UPDATE quotation_header SET whatsapp_sent = 1 WHERE id = ?"), eq(77));
    }

    @Test
    void sendDocumentWithPaymentReceiptDispatchLabelTagsSubjectClearlyInAuditLog() throws Exception {
        when(canonicalDocs.render("SALES_INVOICE", "IN/23-11-2026/0001", "pdf"))
                .thenReturn(new CanonicalDocumentService.Rendered("IN-0001.pdf", "application/pdf", new byte[]{1, 2}));
        when(wahaClient.sendFile(anyString(), anyString(), eq("default"), eq("919876543210@c.us"),
                anyString(), anyString(), any(byte[].class), anyString()))
                .thenReturn(new WahaDtos.SendResponse(true, "Sent", "msg-10", "PRIMARY", "919876543210@c.us"));

        WahaDtos.SendResponse resp = service.sendDocument(new WahaDtos.DocumentSendRequest(
                "SALE", "IN/23-11-2026/0001", 42, null, "9876543210",
                "Payment confirmation", 1L, "Payment Receipt"
        ));

        assertTrue(resp.success());
        verify(jdbc, times(1)).update(
                contains("INSERT INTO communication_log"),
                eq("SALE"), eq(42), eq("9876543210"),
                eq("WhatsApp (Payment Receipt): IN/23-11-2026/0001"), eq("SENT"), isNull(), anyString(), anyString()
        );
    }

    @Test
    void sendTextSupportsMultiplePhonesInSingleConsolidatedLog() {
        when(wahaClient.sendText(anyString(), anyString(), eq("default"), anyString(), eq("Reminder")))
                .thenReturn(new WahaDtos.SendResponse(true, "Sent", "m-1", "PRIMARY", "chat"));

        WahaDtos.SendResponse resp = service.sendText(new WahaDtos.TextSendRequest("9876543210; 9123456789", "Reminder", null));

        assertTrue(resp.success());
        verify(wahaClient, times(2)).sendText(anyString(), anyString(), eq("default"), anyString(), eq("Reminder"));
        verify(jdbc, times(1)).update(
                contains("INSERT INTO communication_log"),
                eq("9876543210, 9123456789"), eq("SENT"), isNull(), anyString(), anyString()
        );
    }
}
