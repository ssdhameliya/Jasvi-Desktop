package org.example.server.persistence.repository;

import org.example.server.persistence.entity.CompanyTemplateMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyTemplateMappingRepository extends JpaRepository<CompanyTemplateMappingEntity, Long> {
    List<CompanyTemplateMappingEntity> findByCompanyId(Long companyId);
    Optional<CompanyTemplateMappingEntity> findByCompanyIdAndOperationType(Long companyId, String operationType);
}
