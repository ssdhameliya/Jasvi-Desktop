package org.example.server.persistence.repository;

import org.example.server.persistence.entity.PurchaseChargeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface PurchaseChargeRepository extends JpaRepository<PurchaseChargeEntity,Integer> {
    List<PurchaseChargeEntity> findByPurchaseIdOrderBySequenceNoAscIdAsc(Integer purchaseId);
    List<PurchaseChargeEntity> findByPurchaseIdInOrderByPurchaseIdAscSequenceNoAscIdAsc(Collection<Integer> purchaseIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM PurchaseChargeEntity pc WHERE pc.purchaseId = :purchaseId")
    void deleteByPurchaseId(@Param("purchaseId") Integer purchaseId);
}
