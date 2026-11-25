package org.example.server.chat;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_channel_member")
public class ChatChannelMemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "member_role", nullable = false, length = 24)
    private String memberRole = "MEMBER";

    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId = 0L;

    @Column(name = "joined_utc", nullable = false, length = 64)
    private String joinedUtc;

    public Long getId() { return id; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getMemberRole() { return memberRole; }
    public void setMemberRole(String memberRole) { this.memberRole = memberRole; }
    public Long getLastReadMessageId() { return lastReadMessageId == null ? 0L : lastReadMessageId; }
    public void setLastReadMessageId(Long lastReadMessageId) { this.lastReadMessageId = lastReadMessageId == null ? 0L : lastReadMessageId; }
    public String getJoinedUtc() { return joinedUtc; }
    public void setJoinedUtc(String joinedUtc) { this.joinedUtc = joinedUtc; }
}
