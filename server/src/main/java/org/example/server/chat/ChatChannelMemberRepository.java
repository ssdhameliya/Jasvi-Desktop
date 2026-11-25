package org.example.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatChannelMemberRepository extends JpaRepository<ChatChannelMemberEntity, Long> {
    List<ChatChannelMemberEntity> findByUserId(Integer userId);
    List<ChatChannelMemberEntity> findByChannelId(Long channelId);
    Optional<ChatChannelMemberEntity> findByChannelIdAndUserId(Long channelId, Integer userId);
    boolean existsByChannelIdAndUserId(Long channelId, Integer userId);
}
