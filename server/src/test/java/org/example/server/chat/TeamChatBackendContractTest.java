package org.example.server.chat;

import org.example.server.persistence.entity.UserEntity;
import org.example.server.persistence.repository.UserRepository;
import org.example.server.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamChatBackendContractTest {

    @TempDir
    Path tempWorkspace;

    @Test
    void regularUserCannotDmSuperAdminButAdminCan() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        ChatPresenceRepository presRepo = mock(ChatPresenceRepository.class);
        ChatChannelRepository chRepo = mock(ChatChannelRepository.class);
        ChatChannelMemberRepository memRepo = mock(ChatChannelMemberRepository.class);
        ChatMessageRepository msgRepo = mock(ChatMessageRepository.class);
        ChatAttachmentRepository attRepo = mock(ChatAttachmentRepository.class);
        TeamChatSseBroker broker = new TeamChatSseBroker();

        UserEntity staff = user(2, "sales1", "SALES");
        UserEntity admin = user(1, "admin", "ADMIN");
        UserEntity superAdmin = user(99, "superadmin", "SUPER_ADMIN");

        when(userRepo.findById(2)).thenReturn(Optional.of(staff));
        when(userRepo.findById(1)).thenReturn(Optional.of(admin));
        when(userRepo.findById(99)).thenReturn(Optional.of(superAdmin));
        when(userRepo.findAll()).thenReturn(List.of(admin, staff, superAdmin));

        TeamChatService svc = new TeamChatService(userRepo, presRepo, chRepo, memRepo, msgRepo, attRepo, broker,
                tempWorkspace.toString());

        assertThrows(ResponseStatusException.class,
                () -> svc.openDirectChannel(new AuthenticatedUser(2, "sales1", "SALES"), 99),
                "Regular company user must not DM Super Admin directly");

        ChatChannelEntity savedCh = new ChatChannelEntity();
        Field idF = ChatChannelEntity.class.getDeclaredField("id");
        idF.setAccessible(true);
        idF.set(savedCh, 10L);
        savedCh.setChannelType("DIRECT");
        savedCh.setChannelKey("DM:1:99");
        savedCh.setName("superadmin");
        savedCh.setCreatedUtc("2026-11-25T10:00:00Z");

        when(chRepo.findByChannelKey("DM:1:99")).thenReturn(Optional.of(savedCh));
        when(memRepo.existsByChannelIdAndUserId(10L, 1)).thenReturn(true);
        when(memRepo.existsByChannelIdAndUserId(10L, 99)).thenReturn(true);
        ChatChannelMemberEntity mem1 = new ChatChannelMemberEntity();
        mem1.setChannelId(10L);
        mem1.setUserId(1);
        mem1.setLastReadMessageId(0L);
        when(memRepo.findByChannelIdAndUserId(10L, 1)).thenReturn(Optional.of(mem1));
        when(memRepo.findByChannelId(10L)).thenReturn(List.of(mem1));
        when(msgRepo.findLatestByChannel(eq(10L), any())).thenReturn(List.of());
        when(msgRepo.countUnread(eq(10L), anyLong(), eq(1))).thenReturn(0L);

        TeamChatDtos.ChannelSummaryDto dm = svc.openDirectChannel(new AuthenticatedUser(1, "admin", "ADMIN"), 99);
        assertEquals(10L, dm.id());
        assertEquals("DIRECT", dm.channelType());
    }

    @Test
    void executableAttachmentsAreRejectedForSecurity() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        ChatPresenceRepository presRepo = mock(ChatPresenceRepository.class);
        ChatChannelRepository chRepo = mock(ChatChannelRepository.class);
        ChatChannelMemberRepository memRepo = mock(ChatChannelMemberRepository.class);
        ChatMessageRepository msgRepo = mock(ChatMessageRepository.class);
        ChatAttachmentRepository attRepo = mock(ChatAttachmentRepository.class);

        UserEntity admin = user(1, "admin", "ADMIN");
        when(userRepo.findById(1)).thenReturn(Optional.of(admin));
        ChatChannelEntity ch = new ChatChannelEntity();
        ch.setChannelType("GROUP");
        ch.setName("general");
        when(chRepo.findById(5L)).thenReturn(Optional.of(ch));
        when(memRepo.existsByChannelIdAndUserId(5L, 1)).thenReturn(true);

        TeamChatService svc = new TeamChatService(userRepo, presRepo, chRepo, memRepo, msgRepo, attRepo,
                new TeamChatSseBroker(), tempWorkspace.toString());

        MockMultipartFile exe = new MockMultipartFile("files", "payload.exe", "application/octet-stream", new byte[]{1, 2});
        assertThrows(ResponseStatusException.class,
                () -> svc.sendWithAttachments(new AuthenticatedUser(1, "admin", "ADMIN"), 5L, "hi", null, null, null, List.of(exe)));
    }

    @Test
    void unchangedHeartbeatDoesNotBroadcastPresenceStorm() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        ChatPresenceRepository presRepo = mock(ChatPresenceRepository.class);
        ChatChannelRepository chRepo = mock(ChatChannelRepository.class);
        ChatChannelMemberRepository memRepo = mock(ChatChannelMemberRepository.class);
        ChatMessageRepository msgRepo = mock(ChatMessageRepository.class);
        ChatAttachmentRepository attRepo = mock(ChatAttachmentRepository.class);
        TeamChatSseBroker broker = mock(TeamChatSseBroker.class);

        UserEntity admin = user(1, "admin", "ADMIN");
        when(userRepo.findById(1)).thenReturn(Optional.of(admin));
        ChatPresenceEntity active = new ChatPresenceEntity();
        active.setUserId(1);
        active.setStatus("ONLINE");
        active.setLastHeartbeatUtc(java.time.Instant.now().toString());
        when(presRepo.findById(1)).thenReturn(Optional.of(active));
        when(presRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TeamChatService svc = new TeamChatService(userRepo, presRepo, chRepo, memRepo, msgRepo, attRepo,
                broker, tempWorkspace.toString());
        TeamChatDtos.PresenceDto dto = svc.heartbeat(new AuthenticatedUser(1, "admin", "ADMIN"));
        assertEquals("ONLINE", dto.status());
        verify(broker, never()).broadcastAll(any());
    }

    @Test
    void markReadIsIdempotentAndSseErrorsNeverReturnHashMap() {
        UserRepository userRepo = mock(UserRepository.class);
        ChatPresenceRepository presRepo = mock(ChatPresenceRepository.class);
        ChatChannelRepository chRepo = mock(ChatChannelRepository.class);
        ChatChannelMemberRepository memRepo = mock(ChatChannelMemberRepository.class);
        ChatMessageRepository msgRepo = mock(ChatMessageRepository.class);
        ChatAttachmentRepository attRepo = mock(ChatAttachmentRepository.class);
        TeamChatSseBroker broker = mock(TeamChatSseBroker.class);

        UserEntity admin = new UserEntity();
        admin.setUsername("admin");
        admin.setFullName("Admin");
        admin.setRole("ADMIN");
        admin.setActive(true);
        try {
            var f = UserEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(admin, 1);
        } catch (Exception e) { throw new RuntimeException(e); }

        when(userRepo.findById(1)).thenReturn(Optional.of(admin));
        ChatChannelEntity ch = new ChatChannelEntity();
        ch.setChannelType("DIRECT");
        when(chRepo.findById(5L)).thenReturn(Optional.of(ch));
        when(memRepo.existsByChannelIdAndUserId(5L, 1)).thenReturn(true);

        ChatChannelMemberEntity mem1 = new ChatChannelMemberEntity();
        mem1.setChannelId(5L);
        mem1.setUserId(1);
        mem1.setLastReadMessageId(42L);
        ChatChannelMemberEntity mem2 = new ChatChannelMemberEntity();
        mem2.setChannelId(5L);
        mem2.setUserId(2);
        mem2.setLastReadMessageId(40L);
        when(memRepo.findByChannelIdAndUserId(5L, 1)).thenReturn(Optional.of(mem1));
        when(memRepo.findByChannelId(5L)).thenReturn(List.of(mem1, mem2));

        TeamChatService svc = new TeamChatService(userRepo, presRepo, chRepo, memRepo, msgRepo, attRepo,
                broker, tempWorkspace.toString());

        // Same messageId (42) must NOT emit any READ event
        svc.markRead(new AuthenticatedUser(1, "admin", "ADMIN"), 5L, 42L);
        verify(broker, never()).sendToUsers(any(), any(), any());

        // New messageId (43) emits READ only to peer (user 2), never back to user 1
        svc.markRead(new AuthenticatedUser(1, "admin", "ADMIN"), 5L, 43L);
        verify(broker, times(1)).sendToUsers(eq(List.of(2)), eq("READ"), any());

        var handler = new org.example.server.web.ApiExceptionHandler();
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/chat/stream");
        var resp = new org.springframework.mock.web.MockHttpServletResponse();
        resp.setContentType("text/event-stream");
        var entity = handler.serverError(new RuntimeException("Client closed"), req, resp);
        assertEquals(204, entity.getStatusCode().value());
    }

    @Test
    void manualOfflineAndCustomNotePersistAcrossHeartbeatAndDirectory() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        ChatPresenceRepository presRepo = mock(ChatPresenceRepository.class);
        ChatChannelRepository chRepo = mock(ChatChannelRepository.class);
        ChatChannelMemberRepository memRepo = mock(ChatChannelMemberRepository.class);
        ChatMessageRepository msgRepo = mock(ChatMessageRepository.class);
        ChatAttachmentRepository attRepo = mock(ChatAttachmentRepository.class);
        TeamChatSseBroker broker = mock(TeamChatSseBroker.class);

        UserEntity admin = user(1, "admin", "ADMIN");
        when(userRepo.findById(1)).thenReturn(Optional.of(admin));
        ChatPresenceEntity pres = new ChatPresenceEntity();
        pres.setUserId(1);
        pres.setStatus("ONLINE");
        when(presRepo.findById(1)).thenReturn(Optional.of(pres));
        when(presRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TeamChatService svc = new TeamChatService(userRepo, presRepo, chRepo, memRepo, msgRepo, attRepo,
                broker, tempWorkspace.toString());

        TeamChatDtos.PresenceDto updated = svc.updatePresence(
                new AuthenticatedUser(1, "admin", "ADMIN"),
                new TeamChatDtos.PresenceUpdateRequest("OFFLINE", "Support"));
        assertEquals("OFFLINE", updated.status());
        assertEquals("Support", updated.customNote());

        TeamChatDtos.PresenceDto afterHeartbeat = svc.heartbeat(new AuthenticatedUser(1, "admin", "ADMIN"));
        assertEquals("OFFLINE", afterHeartbeat.status(), "Heartbeat must preserve manual OFFLINE status");
        assertEquals("Support", afterHeartbeat.customNote(), "Heartbeat must preserve custom status note");
    }

    private static UserEntity user(int id, String username, String role) throws Exception {
        UserEntity u = new UserEntity();
        Field f = UserEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(u, id);
        u.setUsername(username);
        u.setFullName(username.toUpperCase());
        u.setRole(role);
        u.setActive(true);
        return u;
    }
}
