package org.example.server.sync;

import org.example.server.persistence.entity.OfflineSyncOutboxEntity;
import org.example.server.persistence.repository.OfflineSyncAuditRepository;
import org.example.server.persistence.repository.OfflineSyncOutboxRepository;
import org.example.shared.sync.SyncBatchRequest;
import org.example.shared.sync.SyncBatchResult;
import org.example.shared.sync.SyncItemRecord;
import org.example.shared.sync.SyncItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SyncServiceTest {

    private OfflineSyncOutboxRepository outboxRepository;
    private OfflineSyncAuditRepository auditRepository;
    private SyncService syncService;

    @BeforeEach
    void setUp() {
        outboxRepository = Mockito.mock(OfflineSyncOutboxRepository.class);
        auditRepository = Mockito.mock(OfflineSyncAuditRepository.class);
        syncService = new SyncService(outboxRepository, auditRepository);
    }

    @Test
    void testQueueLocalTransaction() {
        OfflineSyncOutboxEntity mockSaved = new OfflineSyncOutboxEntity();
        mockSaved.setId(1L);
        mockSaved.setSyncUuid("test-uuid");
        mockSaved.setEntityType("SALES_INVOICE");
        mockSaved.setSyncStatus("PENDING");

        when(outboxRepository.save(any(OfflineSyncOutboxEntity.class))).thenReturn(mockSaved);

        OfflineSyncOutboxEntity result = syncService.queueLocalTransaction(
            "SALES_INVOICE", "CREATE", "INV-101", "{\"total\":2500}", "WS-01"
        );

        assertNotNull(result);
        assertEquals("PENDING", result.getSyncStatus());
    }

    @Test
    void testProcessIncomingBatch() {
        when(outboxRepository.findBySyncUuid("uuid-1")).thenReturn(Optional.empty());
        when(outboxRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SyncItemRecord item = new SyncItemRecord(
            "uuid-1", "SALES_INVOICE", "CREATE", "INV-101", "{}", "WS-01", "2026-11-09T23:00:00Z"
        );
        SyncBatchRequest request = new SyncBatchRequest("batch-1", "WS-01", "2026-11-09T23:00:00Z", List.of(item));

        SyncBatchResult result = syncService.processIncomingBatch(request);

        assertNotNull(result);
        assertEquals(1, result.totalCount());
        assertEquals(1, result.successCount());
        assertEquals(0, result.failureCount());
        assertEquals("COMPLETED", result.status());
    }

    @Test
    void testSyncContractsRecordImmutability() {
        SyncItemRecord item = new SyncItemRecord(
            "uuid-1234", "SALES_INVOICE", "CREATE", "INV-2026-001",
            "{\"total\":1500}", "WORKSTATION-1", "2026-11-09T23:00:00Z"
        );
        assertEquals("uuid-1234", item.syncUuid());
        assertEquals("SALES_INVOICE", item.entityType());

        SyncItemStatus status = new SyncItemStatus("uuid-1234", "SYNCED", "INV-2026-001", "Committed");
        assertEquals("SYNCED", status.status());
    }
}
