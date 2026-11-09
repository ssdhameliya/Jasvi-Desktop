package org.example.server.persistence.repository;

import org.example.server.persistence.entity.OfflineSyncAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OfflineSyncAuditRepository extends JpaRepository<OfflineSyncAuditEntity, Long> {
    List<OfflineSyncAuditEntity> findTop20ByOrderByExecutedAtDesc();
}
