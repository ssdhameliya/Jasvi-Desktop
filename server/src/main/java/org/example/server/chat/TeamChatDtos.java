package org.example.server.chat;

import java.util.List;
import java.util.Map;

public final class TeamChatDtos {
    private TeamChatDtos() {}

    public record PresenceDto(
            int userId,
            String username,
            String fullName,
            String role,
            String department,
            String branch,
            String status,
            String customNote,
            String lastHeartbeatUtc,
            String updatedUtc
    ) {}

    public record DirectoryResponse(
            PresenceDto selfPresence,
            List<PresenceDto> users,
            List<ChannelSummaryDto> channels,
            long totalUnread
    ) {}

    public record ChannelSummaryDto(
            long id,
            String channelType,
            String channelKey,
            String name,
            long unreadCount,
            String lastMessagePreview,
            String lastMessageUtc,
            Integer peerUserId,
            String peerStatus,
            String peerCustomNote,
            List<MemberDto> members
    ) {}

    public record MemberDto(
            int userId,
            String username,
            String fullName,
            String role,
            String memberRole,
            String status,
            long lastReadMessageId
    ) {}

    public record AttachmentDto(
            long id,
            long messageId,
            long channelId,
            String fileName,
            String contentType,
            long fileSize,
            boolean image,
            String uploadedUtc
    ) {}

    public record MessageDto(
            long id,
            long channelId,
            int senderId,
            String senderUsername,
            String senderName,
            String senderRole,
            String body,
            Long replyToId,
            String replyPreview,
            String linkedDocType,
            String linkedDocNo,
            Map<String, List<Integer>> reactions,
            List<AttachmentDto> attachments,
            String createdUtc,
            String editedUtc,
            boolean deleted,
            boolean readByPeer
    ) {}

    public record PresenceUpdateRequest(String status, String customNote) {}
    public record OpenDirectRequest(int peerUserId) {}
    public record CreateGroupRequest(String name, List<Integer> memberIds) {}
    public record SendMessageRequest(String body, Long replyToId, String linkedDocType, String linkedDocNo) {}
    public record EditMessageRequest(String body) {}
    public record ReactRequest(String emoji) {}
    public record MarkReadRequest(long lastReadMessageId) {}
    public record TypingRequest(boolean typing) {}

    public record ChatStreamEvent(
            String eventType,
            Long channelId,
            MessageDto message,
            PresenceDto presence,
            Integer actorUserId,
            String actorName,
            Boolean typing,
            Long readMessageId
    ) {}
}
