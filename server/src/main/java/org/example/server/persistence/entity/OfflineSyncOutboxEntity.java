package org.example.server.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "offline_sync_outbox")
public class OfflineSyncOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_uuid", nullable = false, unique = true, length = 64)
    private String syncUuid;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "action", nullable = false, length = 20)
    private String action = "CREATE";

    @Column(name = "entity_identifier", length = 100)
    private String entityIdentifier;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus = "PENDING";

    @Column(name = "origin_workstation", length = 100)
    private String originWorkstation;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSyncUuid() { return syncUuid; }
    public void setSyncUuid(String syncUuid) { this.syncUuid = syncUuid; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getEntityIdentifier() { return entityIdentifier; }
    public void setEntityIdentifier(String entityIdentifier) { this.entityIdentifier = entityIdentifier; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String syncStatus) { this.syncStatus = syncStatus; }

    public String getOriginWorkstation() { return originWorkstation; }
    public void setOriginWorkstation(String originWorkstation) { this.originWorkstation = originWorkstation; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
}
