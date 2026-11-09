package org.example.shared.sync;

import java.io.Serializable;

/**
 * Result of syncing an individual item on the server.
 */
public record SyncItemStatus(
    String syncUuid,
    String status, // "SYNCED", "CONFLICT", "FAILED"
    String assignedIdentifier,
    String message
) implements Serializable {}
