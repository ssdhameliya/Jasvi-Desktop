package org.example.server.persistence.repository;

import org.example.server.persistence.entity.ClientProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClientProfileRepository extends JpaRepository<ClientProfileEntity, Long> {
    Optional<ClientProfileEntity> findByClientName(String clientName);
    List<ClientProfileEntity> findAllByOrderByClientNameAsc();
}
