package org.example.server.persistence.repository;

import org.example.server.persistence.entity.WhatsappSenderChannelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WhatsappSenderChannelRepository extends JpaRepository<WhatsappSenderChannelEntity, Long> {
    Optional<WhatsappSenderChannelEntity> findByChannelCode(String channelCode);
    Optional<WhatsappSenderChannelEntity> findFirstByIsDefaultTrueAndIsActiveTrue();
    Optional<WhatsappSenderChannelEntity> findFirstByPhoneNumberAndIsActiveTrue(String phoneNumber);
    List<WhatsappSenderChannelEntity> findAllByIsActiveTrueOrderByIsDefaultDescDisplayNameAsc();
}
