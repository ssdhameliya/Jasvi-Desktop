package org.example.server.persistence.repository;

import org.example.server.persistence.entity.OfflineSyncOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OfflineSyncOutboxRepository extends JpaRepository<OfflineSyncOutboxEntity, Long> {
    Optional<OfflineSyncOutboxEntity> findBySyncUuid(String syncUuid);
    List<OfflineSyncOutboxEntity> findBySyncStatusOrderByCreatedAtAsc(String syncStatus);
    long countBySyncStatus(String syncStatus);
}
