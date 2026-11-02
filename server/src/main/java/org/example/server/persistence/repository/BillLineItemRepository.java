package org.example.server.persistence.repository;

import org.example.server.persistence.entity.BillLineItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BillLineItemRepository extends JpaRepository<BillLineItemEntity, Long> {
    List<BillLineItemEntity> findByBillRecordIdOrderByLineNumberAsc(Long billRecordId);
    void deleteByBillRecordId(Long billRecordId);
}
