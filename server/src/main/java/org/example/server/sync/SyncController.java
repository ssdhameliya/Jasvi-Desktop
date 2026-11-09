package org.example.server.sync;

import org.example.shared.sync.SyncBatchRequest;
import org.example.shared.sync.SyncBatchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sync/v1")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * Lightweight health probe for network reachability checks.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "sync-engine"));
    }

    /**
     * Ingests a batch of offline transactions pushed by a workstation.
     */
    @PostMapping("/push")
    public ResponseEntity<SyncBatchResult> pushBatch(@RequestBody SyncBatchRequest request) {
        SyncBatchResult result = syncService.processIncomingBatch(request);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns synchronization queue statistics.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(syncService.getSyncStatusSummary());
    }
}
