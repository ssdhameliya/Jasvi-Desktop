package org.example.server.persistence.repository;

import org.example.server.persistence.entity.ClientExtractionTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientExtractionTemplateRepository extends JpaRepository<ClientExtractionTemplateEntity, Long> {
    List<ClientExtractionTemplateEntity> findByClientProfileIdOrderByCreatedAtDesc(Long clientProfileId);

    List<ClientExtractionTemplateEntity> findByVendorGstinAndIsActiveTrue(String vendorGstin);

    Optional<ClientExtractionTemplateEntity> findByClientProfileIdAndVersionCode(Long clientProfileId, String versionCode);

    long countByClientProfileId(Long clientProfileId);
}
