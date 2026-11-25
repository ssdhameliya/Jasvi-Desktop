package org.example.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatPresenceRepository extends JpaRepository<ChatPresenceEntity, Integer> {
    List<ChatPresenceEntity> findByUserIdIn(List<Integer> userIds);
}
