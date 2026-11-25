package org.example.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatChannelRepository extends JpaRepository<ChatChannelEntity, Long> {
    Optional<ChatChannelEntity> findByChannelKey(String channelKey);
}
