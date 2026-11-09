package org.example.shared.sync;

import java.io.Serializable;
import java.util.List;

/**
 * Batch payload sent by the desktop client to the cloud server during synchronization.
 */
public record SyncBatchRequest(
    String batchId,
    String workstationId,
    String timestamp,
    List<SyncItemRecord> items
) implements Serializable {}
