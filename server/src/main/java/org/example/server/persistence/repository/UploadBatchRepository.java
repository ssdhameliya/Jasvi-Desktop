package org.example.server.persistence.repository;

import org.example.server.persistence.entity.UploadBatchEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UploadBatchRepository extends JpaRepository<UploadBatchEntity, Long> {
    Optional<UploadBatchEntity> findByBatchReference(String batchReference);
    Page<UploadBatchEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
