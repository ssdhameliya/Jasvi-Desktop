package org.example.server.chat;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "sender_id", nullable = false)
    private Integer senderId;

    @Column(name = "sender_username", nullable = false, length = 100)
    private String senderUsername;

    @Column(name = "sender_name", nullable = false, length = 160)
    private String senderName;

    @Column(name = "sender_role", length = 64)
    private String senderRole;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "reply_to_id")
    private Long replyToId;

    @Column(name = "reply_preview", length = 240)
    private String replyPreview;

    @Column(name = "linked_doc_type", length = 48)
    private String linkedDocType;

    @Column(name = "linked_doc_no", length = 80)
    private String linkedDocNo;

    @Column(name = "reactions_json", columnDefinition = "TEXT")
    private String reactionsJson;

    @Column(name = "created_utc", nullable = false, length = 64)
    private String createdUtc;

    @Column(name = "edited_utc", length = 64)
    private String editedUtc;

    @Column(nullable = false)
    private Integer deleted = 0;

    public Long getId() { return id; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Integer getSenderId() { return senderId; }
    public void setSenderId(Integer senderId) { this.senderId = senderId; }
    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Long getReplyToId() { return replyToId; }
    public void setReplyToId(Long replyToId) { this.replyToId = replyToId; }
    public String getReplyPreview() { return replyPreview; }
    public void setReplyPreview(String replyPreview) { this.replyPreview = replyPreview; }
    public String getLinkedDocType() { return linkedDocType; }
    public void setLinkedDocType(String linkedDocType) { this.linkedDocType = linkedDocType; }
    public String getLinkedDocNo() { return linkedDocNo; }
    public void setLinkedDocNo(String linkedDocNo) { this.linkedDocNo = linkedDocNo; }
    public String getReactionsJson() { return reactionsJson; }
    public void setReactionsJson(String reactionsJson) { this.reactionsJson = reactionsJson; }
    public String getCreatedUtc() { return createdUtc; }
    public void setCreatedUtc(String createdUtc) { this.createdUtc = createdUtc; }
    public String getEditedUtc() { return editedUtc; }
    public void setEditedUtc(String editedUtc) { this.editedUtc = editedUtc; }
    public boolean isDeleted() { return Integer.valueOf(1).equals(deleted); }
    public void setDeleted(boolean deleted) { this.deleted = deleted ? 1 : 0; }
}
