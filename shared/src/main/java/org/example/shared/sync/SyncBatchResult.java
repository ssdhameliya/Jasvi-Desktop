package org.example.shared.sync;

import java.io.Serializable;
import java.util.List;

/**
 * Server response to a batch synchronization request.
 */
public record SyncBatchResult(
    String batchId,
    int totalCount,
    int successCount,
    int failureCount,
    List<SyncItemStatus> itemStatuses,
    String status // "COMPLETED", "PARTIAL", "FAILED"
) implements Serializable {}
