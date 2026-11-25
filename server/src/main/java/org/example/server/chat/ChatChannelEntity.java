package org.example.server.chat;

import jakarta.persistence.*;

@Entity
@Table(name = "chat_channel")
public class ChatChannelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_type", nullable = false, length = 24)
    private String channelType; // DIRECT, GROUP, ADMIN_SUPPORT

    @Column(name = "channel_key", unique = true, length = 160)
    private String channelKey;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "created_by")
    private Integer createdBy;

    @Column(name = "created_utc", nullable = false, length = 64)
    private String createdUtc;

    public Long getId() { return id; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }
    public String getCreatedUtc() { return createdUtc; }
    public void setCreatedUtc(String createdUtc) { this.createdUtc = createdUtc; }
}
