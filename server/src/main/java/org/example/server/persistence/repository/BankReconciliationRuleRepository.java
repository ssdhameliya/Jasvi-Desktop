package org.example.server.persistence.repository;

import org.example.server.persistence.entity.BankReconciliationRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankReconciliationRuleRepository extends JpaRepository<BankReconciliationRuleEntity, Long> {
    List<BankReconciliationRuleEntity> findAllByOrderByPriorityAscIdAsc();
    List<BankReconciliationRuleEntity> findByActiveTrueOrderByPriorityAscIdAsc();
}
