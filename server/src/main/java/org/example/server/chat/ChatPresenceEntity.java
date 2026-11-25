package org.example.server.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_presence")
public class ChatPresenceEntity {
    @Id
    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false, length = 24)
    private String status = "ONLINE";

    @Column(name = "custom_note", length = 160)
    private String customNote;

    @Column(name = "last_heartbeat_utc", length = 64)
    private String lastHeartbeatUtc;

    @Column(name = "updated_utc", length = 64)
    private String updatedUtc;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCustomNote() { return customNote; }
    public void setCustomNote(String customNote) { this.customNote = customNote; }
    public String getLastHeartbeatUtc() { return lastHeartbeatUtc; }
    public void setLastHeartbeatUtc(String lastHeartbeatUtc) { this.lastHeartbeatUtc = lastHeartbeatUtc; }
    public String getUpdatedUtc() { return updatedUtc; }
    public void setUpdatedUtc(String updatedUtc) { this.updatedUtc = updatedUtc; }
}
