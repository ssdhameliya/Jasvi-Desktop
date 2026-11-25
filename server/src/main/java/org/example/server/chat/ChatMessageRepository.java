package org.example.server.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId ORDER BY m.id DESC")
    List<ChatMessageEntity> findLatestByChannel(@Param("channelId") Long channelId, Pageable pageable);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.id < :beforeId ORDER BY m.id DESC")
    List<ChatMessageEntity> findOlderByChannel(@Param("channelId") Long channelId, @Param("beforeId") Long beforeId, Pageable pageable);

    @Query("SELECT COUNT(m) FROM ChatMessageEntity m WHERE m.channelId = :channelId AND m.id > :lastReadId AND m.senderId <> :userId AND m.deleted = 0")
    long countUnread(@Param("channelId") Long channelId, @Param("lastReadId") Long lastReadId, @Param("userId") Integer userId);

    @Query("""
        SELECT m FROM ChatMessageEntity m
        WHERE m.channelId = :channelId AND m.deleted = 0
          AND (LOWER(COALESCE(m.body,'')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(m.linkedDocNo,'')) LIKE LOWER(CONCAT('%', :q, '%')))
        ORDER BY m.id DESC
        """)
    List<ChatMessageEntity> searchChannel(@Param("channelId") Long channelId, @Param("q") String q, Pageable pageable);
}
