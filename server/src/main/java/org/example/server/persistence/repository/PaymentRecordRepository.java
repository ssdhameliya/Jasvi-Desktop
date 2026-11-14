package org.example.server.persistence.repository;
import org.example.server.persistence.entity.PaymentRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecordEntity,Integer>{
    List<PaymentRecordEntity> findByDocumentTypeAndDocumentIdOrderByPaymentDateDescIdDesc(String documentType, Integer documentId);
}
