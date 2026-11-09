package org.example.shared.sync;

import java.io.Serializable;

/**
 * Represents an individual offline transaction record queued for synchronization.
 */
public record SyncItemRecord(
    String syncUuid,
    String entityType,
    String action,
    String entityIdentifier,
    String payload,
    String originWorkstation,
    String createdAt
) implements Serializable {}
