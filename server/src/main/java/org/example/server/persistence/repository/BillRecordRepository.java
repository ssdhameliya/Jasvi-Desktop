package org.example.server.persistence.repository;

import org.example.server.persistence.entity.BillRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BillRecordRepository extends JpaRepository<BillRecordEntity, Long> {
    List<BillRecordEntity> findByBatchIdOrderByIdAsc(Long batchId);
    Page<BillRecordEntity> findByBatchIdOrderByIdAsc(Long batchId, Pageable pageable);

    @Query("SELECT b FROM BillRecordEntity b WHERE (:batchId IS NULL OR b.batchId = :batchId) " +
           "AND (:clientProfileId IS NULL OR b.clientProfileId = :clientProfileId) " +
           "AND (:status IS NULL OR b.status = :status) ORDER BY b.id DESC")
    Page<BillRecordEntity> searchBills(@Param("batchId") Long batchId,
                                      @Param("clientProfileId") Long clientProfileId,
                                      @Param("status") String status,
                                      Pageable pageable);

    long countByBatchIdAndStatus(Long batchId, String status);
}
