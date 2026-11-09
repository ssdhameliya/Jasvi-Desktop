package org.example.server.sync;

import org.example.server.persistence.entity.OfflineSyncAuditEntity;
import org.example.server.persistence.entity.OfflineSyncOutboxEntity;
import org.example.server.persistence.repository.OfflineSyncAuditRepository;
import org.example.server.persistence.repository.OfflineSyncOutboxRepository;
import org.example.shared.sync.SyncBatchRequest;
import org.example.shared.sync.SyncBatchResult;
import org.example.shared.sync.SyncItemRecord;
import org.example.shared.sync.SyncItemStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final OfflineSyncOutboxRepository outboxRepository;
    private final OfflineSyncAuditRepository auditRepository;

    public SyncService(OfflineSyncOutboxRepository outboxRepository,
                       OfflineSyncAuditRepository auditRepository) {
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
    }

    /**
     * Queues an offline transaction into the local outbox.
     */
    @Transactional
    public OfflineSyncOutboxEntity queueLocalTransaction(String entityType, String action,
                                                        String entityIdentifier, String payload,
                                                        String workstationId) {
        OfflineSyncOutboxEntity entity = new OfflineSyncOutboxEntity();
        entity.setSyncUuid(UUID.randomUUID().toString());
        entity.setEntityType(entityType);
        entity.setAction(action == null ? "CREATE" : action);
        entity.setEntityIdentifier(entityIdentifier);
        entity.setPayload(payload);
        entity.setSyncStatus("PENDING");
        entity.setOriginWorkstation(workstationId);
        entity.setCreatedAt(LocalDateTime.now());
        return outboxRepository.save(entity);
    }

    /**
     * Retrieves pending items waiting to be synced to the cloud.
     */
    @Transactional(readOnly = true)
    public List<OfflineSyncOutboxEntity> getPendingOutbox() {
        return outboxRepository.findBySyncStatusOrderByCreatedAtAsc("PENDING");
    }

    /**
     * Processes an incoming batch push from a client workstation.
     */
    @Transactional
    public SyncBatchResult processIncomingBatch(SyncBatchRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return new SyncBatchResult(
                request == null ? UUID.randomUUID().toString() : request.batchId(),
                0, 0, 0, List.of(), "COMPLETED"
            );
        }

        List<SyncItemStatus> statuses = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (SyncItemRecord item : request.items()) {
            try {
                var existing = outboxRepository.findBySyncUuid(item.syncUuid());
                if (existing.isPresent() && "SYNCED".equalsIgnoreCase(existing.get().getSyncStatus())) {
                    statuses.add(new SyncItemStatus(item.syncUuid(), "SYNCED", existing.get().getEntityIdentifier(), "Already synchronized"));
                    successCount++;
                    continue;
                }

                OfflineSyncOutboxEntity record = existing.orElseGet(OfflineSyncOutboxEntity::new);
                record.setSyncUuid(item.syncUuid());
                record.setEntityType(item.entityType());
                record.setAction(item.action());
                record.setEntityIdentifier(item.entityIdentifier());
                record.setPayload(item.payload());
                record.setOriginWorkstation(item.originWorkstation());
                record.setSyncStatus("SYNCED");
                record.setSyncedAt(LocalDateTime.now());
                outboxRepository.save(record);

                statuses.add(new SyncItemStatus(item.syncUuid(), "SYNCED", item.entityIdentifier(), "Committed successfully"));
                successCount++;
            } catch (Exception e) {
                log.error("Failed to process sync item {}: {}", item.syncUuid(), e.getMessage(), e);
                statuses.add(new SyncItemStatus(item.syncUuid(), "FAILED", item.entityIdentifier(), e.getMessage()));
                failureCount++;
            }
        }

        // Audit the batch execution
        OfflineSyncAuditEntity audit = new OfflineSyncAuditEntity();
        audit.setSyncBatchId(request.batchId() == null ? UUID.randomUUID().toString() : request.batchId());
        audit.setDirection("PUSH");
        audit.setRecordsCount(request.items().size());
        audit.setSuccessCount(successCount);
        audit.setFailureCount(failureCount);
        audit.setStatus(failureCount == 0 ? "COMPLETED" : (successCount > 0 ? "PARTIAL" : "FAILED"));
        audit.setOriginWorkstation(request.workstationId());
        audit.setExecutedAt(LocalDateTime.now());
        auditRepository.save(audit);

        return new SyncBatchResult(
            request.batchId(),
            request.items().size(),
            successCount,
            failureCount,
            statuses,
            audit.getStatus()
        );
    }

    /**
     * Marks a local outbox record as synchronized after cloud confirmation.
     */
    @Transactional
    public void markSynced(String syncUuid, String assignedIdentifier) {
        outboxRepository.findBySyncUuid(syncUuid).ifPresent(record -> {
            record.setSyncStatus("SYNCED");
            record.setSyncedAt(LocalDateTime.now());
            if (assignedIdentifier != null && !assignedIdentifier.isBlank()) {
                record.setEntityIdentifier(assignedIdentifier);
            }
            outboxRepository.save(record);
        });
    }

    /**
     * Returns sync health and counts for dashboards.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSyncStatusSummary() {
        long pending = outboxRepository.countBySyncStatus("PENDING");
        long synced = outboxRepository.countBySyncStatus("SYNCED");
        return Map.of(
            "pendingCount", pending,
            "syncedCount", synced,
            "isHealthy", true,
            "checkedAt", LocalDateTime.now().toString()
        );
    }
}
