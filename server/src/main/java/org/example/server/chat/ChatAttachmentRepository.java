package org.example.server.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, Long> {
    List<ChatAttachmentEntity> findByMessageIdIn(List<Long> messageIds);
    List<ChatAttachmentEntity> findByMessageId(Long messageId);
}
