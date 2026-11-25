package org.example.server.persistence.repository;

import org.example.server.persistence.entity.SalesChargeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface SalesChargeRepository extends JpaRepository<SalesChargeEntity,Integer> {
    List<SalesChargeEntity> findBySalesIdOrderBySequenceNoAscIdAsc(Integer salesId);
    List<SalesChargeEntity> findBySalesIdInOrderBySalesIdAscSequenceNoAscIdAsc(Collection<Integer> salesIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM SalesChargeEntity sc WHERE sc.salesId = :salesId")
    void deleteBySalesId(@Param("salesId") Integer salesId);
}
