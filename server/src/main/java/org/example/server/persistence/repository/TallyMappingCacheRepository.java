package org.example.server.persistence.repository;

import org.example.server.persistence.entity.TallyMappingCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TallyMappingCacheRepository extends JpaRepository<TallyMappingCacheEntity, Long> {
    Optional<TallyMappingCacheEntity> findByClientProfileIdAndMappingTypeAndSourceIdentifier(
        Long clientProfileId, String mappingType, String sourceIdentifier);

    List<TallyMappingCacheEntity> findByClientProfileId(Long clientProfileId);
}
