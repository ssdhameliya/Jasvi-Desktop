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
