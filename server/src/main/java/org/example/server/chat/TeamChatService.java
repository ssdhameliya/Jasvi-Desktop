package org.example.server.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.server.persistence.entity.UserEntity;
import org.example.server.persistence.repository.UserRepository;
import org.example.server.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class TeamChatService {
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(75);
    private static final Set<String> VALID_STATUSES = Set.of("ONLINE", "AWAY", "BUSY", "OFFLINE");
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".ps1", ".vbs", ".vbe", ".msi", ".scr", ".com", ".pif", ".jse", ".hta"
    );

    private final UserRepository userRepo;
    private final ChatPresenceRepository presenceRepo;
    private final ChatChannelRepository channelRepo;
    private final ChatChannelMemberRepository memberRepo;
    private final ChatMessageRepository messageRepo;
    private final ChatAttachmentRepository attachmentRepo;
    private final TeamChatSseBroker broker;
    private final ObjectMapper json = new ObjectMapper();
    private final Path chatStorageRoot;

    public TeamChatService(
            UserRepository userRepo,
            ChatPresenceRepository presenceRepo,
            ChatChannelRepository channelRepo,
            ChatChannelMemberRepository memberRepo,
            ChatMessageRepository messageRepo,
            ChatAttachmentRepository attachmentRepo,
            TeamChatSseBroker broker,
            @Value("${dse.workspace.path:}") String workspace
    ) {
        this.userRepo = userRepo;
        this.presenceRepo = presenceRepo;
        this.channelRepo = channelRepo;
        this.memberRepo = memberRepo;
        this.messageRepo = messageRepo;
        this.attachmentRepo = attachmentRepo;
        this.broker = broker;
        Path root = (workspace == null || workspace.isBlank() ? Path.of(System.getProperty("user.dir")) : Path.of(workspace))
                .toAbsolutePath().normalize();
        this.chatStorageRoot = root.resolve("Attachments").resolve("chat").normalize();
    }

    @Transactional
    public TeamChatDtos.DirectoryResponse directory(AuthenticatedUser current) {
        UserEntity me = requireActiveUser(current);
        ensureDefaultChannelsSynced(me);
        touchHeartbeat(me.getId(), null, null, false);

        List<UserEntity> visibleUsers = visibleDirectoryUsers(me);
        Map<Integer, ChatPresenceEntity> presenceMap = loadPresenceMap(visibleUsers);
        Map<Integer, UserEntity> userById = new LinkedHashMap<>();
        for (UserEntity u : userRepo.findAll()) {
            if (u.getId() != null) userById.put(u.getId(), u);
        }

        List<TeamChatDtos.PresenceDto> userDtos = visibleUsers.stream()
                .map(u -> toPresenceDto(u, presenceMap.get(u.getId())))
                .sorted(Comparator.comparingInt((TeamChatDtos.PresenceDto p) -> statusOrder(p.status()))
                        .thenComparing(TeamChatDtos.PresenceDto::fullName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        TeamChatDtos.PresenceDto selfDto = toPresenceDto(me, presenceRepo.findById(me.getId()).orElse(null));

        List<ChatChannelMemberEntity> memberships = memberRepo.findByUserId(me.getId());
        List<TeamChatDtos.ChannelSummaryDto> channelDtos = new ArrayList<>();
        long totalUnread = 0;

        for (ChatChannelMemberEntity mem : memberships) {
            ChatChannelEntity ch = channelRepo.findById(mem.getChannelId()).orElse(null);
            if (ch == null) continue;
            if ("ADMIN_SUPPORT".equals(ch.getChannelType()) && !isAdminOrSuperAdmin(me.getRoleName())) {
                continue;
            }
            TeamChatDtos.ChannelSummaryDto summary = buildChannelSummary(ch, mem, me.getId(), userById, presenceMap);
            channelDtos.add(summary);
            totalUnread += summary.unreadCount();
        }

        channelDtos.sort(Comparator
                .comparing((TeamChatDtos.ChannelSummaryDto c) -> "ADMIN_SUPPORT".equals(c.channelType()) ? 0 : ("GROUP".equals(c.channelType()) ? 1 : 2))
                .thenComparing(c -> safe(c.lastMessageUtc()), Comparator.reverseOrder())
                .thenComparing(TeamChatDtos.ChannelSummaryDto::name, String.CASE_INSENSITIVE_ORDER));

        return new TeamChatDtos.DirectoryResponse(selfDto, userDtos, channelDtos, totalUnread);
    }

    public TeamChatDtos.PresenceDto updatePresence(AuthenticatedUser current, TeamChatDtos.PresenceUpdateRequest req) {
        UserEntity me = requireActiveUser(current);
        String nextStatus = req == null || req.status() == null ? "ONLINE" : req.status().trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(nextStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported presence status: " + nextStatus);
        }
        String note = req != null && req.customNote() != null ? truncate(req.customNote().trim(), 150) : null;
        ChatPresenceEntity saved = touchHeartbeat(me.getId(), nextStatus, note, true);
        TeamChatDtos.PresenceDto dto = toPresenceDto(me, saved);
        broker.broadcastAll(new TeamChatDtos.ChatStreamEvent("PRESENCE", null, null, dto, me.getId(), displayName(me), null, null));
        return dto;
    }

    public TeamChatDtos.PresenceDto heartbeat(AuthenticatedUser current) {
        UserEntity me = requireActiveUser(current);
        ChatPresenceEntity saved = touchHeartbeat(me.getId(), null, null, false);
        TeamChatDtos.PresenceDto dto = toPresenceDto(me, saved);
        broker.broadcastAll(new TeamChatDtos.ChatStreamEvent("PRESENCE", null, null, dto, me.getId(), displayName(me), null, null));
        return dto;
    }

    @Transactional
    public TeamChatDtos.ChannelSummaryDto openDirectChannel(AuthenticatedUser current, int peerUserId) {
        UserEntity me = requireActiveUser(current);
        if (peerUserId == me.getId()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select another team member to chat with.");
        }
        UserEntity peer = userRepo.findById(peerUserId)
                .filter(UserEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found or inactive."));

        if (isSuperAdmin(peer.getRoleName()) && !isAdminOrSuperAdmin(me.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only Company Admin can chat directly with Super Admin.");
        }
        if (isSuperAdmin(me.getRoleName()) && !isAdminOrSuperAdmin(peer.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super Admin can escalate directly with Company Admin.");
        }

        int a = Math.min(me.getId(), peer.getId());
        int b = Math.max(me.getId(), peer.getId());
        String key = "DM:" + a + ":" + b;
        ChatChannelEntity channel = channelRepo.findByChannelKey(key).orElseGet(() -> {
            ChatChannelEntity ch = new ChatChannelEntity();
            ch.setChannelType("DIRECT");
            ch.setChannelKey(key);
            ch.setName(displayName(peer));
            ch.setCreatedBy(me.getId());
            ch.setCreatedUtc(Instant.now().toString());
            return channelRepo.save(ch);
        });
        ensureMember(channel.getId(), me.getId(), "MEMBER");
        ensureMember(channel.getId(), peer.getId(), "MEMBER");

        Map<Integer, UserEntity> users = Map.of(me.getId(), me, peer.getId(), peer);
        Map<Integer, ChatPresenceEntity> pres = loadPresenceMap(List.of(me, peer));
        ChatChannelMemberEntity myMem = memberRepo.findByChannelIdAndUserId(channel.getId(), me.getId()).orElseThrow();
        return buildChannelSummary(channel, myMem, me.getId(), users, pres);
    }

    @Transactional
    public TeamChatDtos.ChannelSummaryDto createGroupChannel(AuthenticatedUser current, TeamChatDtos.CreateGroupRequest req) {
        UserEntity me = requireActiveUser(current);
        String name = req == null || req.name() == null ? "" : req.name().trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel name is required.");
        }
        if (name.startsWith("#")) name = name.substring(1).trim();
        name = truncate(name, 100);

        ChatChannelEntity ch = new ChatChannelEntity();
        ch.setChannelType("GROUP");
        ch.setChannelKey("GROUP:" + UUID.randomUUID());
        ch.setName(name);
        ch.setCreatedBy(me.getId());
        ch.setCreatedUtc(Instant.now().toString());
        final ChatChannelEntity savedCh = channelRepo.save(ch);

        ensureMember(savedCh.getId(), me.getId(), "OWNER");
        if (req != null && req.memberIds() != null) {
            for (Integer uid : req.memberIds()) {
                if (uid == null || uid.equals(me.getId())) continue;
                userRepo.findById(uid).filter(UserEntity::isActive).ifPresent(u -> {
                    if (!isSuperAdmin(u.getRoleName()) || isAdminOrSuperAdmin(me.getRoleName())) {
                        ensureMember(savedCh.getId(), u.getId(), "MEMBER");
                    }
                });
            }
        }
        Map<Integer, UserEntity> userById = new HashMap<>();
        userRepo.findAll().forEach(u -> { if (u.getId() != null) userById.put(u.getId(), u); });
        Map<Integer, ChatPresenceEntity> pres = loadPresenceMap(new ArrayList<>(userById.values()));
        ChatChannelMemberEntity myMem = memberRepo.findByChannelIdAndUserId(savedCh.getId(), me.getId()).orElseThrow();
        return buildChannelSummary(savedCh, myMem, me.getId(), userById, pres);
    }

    public List<TeamChatDtos.MessageDto> listMessages(AuthenticatedUser current, long channelId, Long beforeId, String query, int limit) {
        UserEntity me = requireActiveUser(current);
        ChatChannelEntity channel = requireChannelAccess(me, channelId);
        int capped = Math.max(10, Math.min(limit <= 0 ? 60 : limit, 150));

        List<ChatMessageEntity> rows;
        if (query != null && !query.isBlank()) {
            rows = messageRepo.searchChannel(channel.getId(), query.trim(), PageRequest.of(0, capped));
        } else if (beforeId != null && beforeId > 0) {
            rows = messageRepo.findOlderByChannel(channel.getId(), beforeId, PageRequest.of(0, capped));
        } else {
            rows = messageRepo.findLatestByChannel(channel.getId(), PageRequest.of(0, capped));
        }
        List<ChatMessageEntity> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);

        List<Long> msgIds = ordered.stream().map(ChatMessageEntity::getId).toList();
        Map<Long, List<TeamChatDtos.AttachmentDto>> attMap = loadAttachmentMap(msgIds);
        List<ChatChannelMemberEntity> members = memberRepo.findByChannelId(channel.getId());

        return ordered.stream()
                .map(m -> toMessageDto(m, attMap.getOrDefault(m.getId(), List.of()), members, me.getId()))
                .toList();
    }

    public TeamChatDtos.MessageDto sendTextMessage(AuthenticatedUser current, long channelId, TeamChatDtos.SendMessageRequest req) {
        UserEntity me = requireActiveUser(current);
        ChatChannelEntity channel = requireChannelAccess(me, channelId);
        String body = req == null || req.body() == null ? "" : req.body().trim();
        String docNo = req == null || req.linkedDocNo() == null ? null : truncate(req.linkedDocNo().trim(), 80);
        String docType = req == null || req.linkedDocType() == null ? null : truncate(req.linkedDocType().trim(), 48);
        if (body.isBlank() && (docNo == null || docNo.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message text or ERP document link is required.");
        }
        Long replyToId = req == null ? null : req.replyToId();
        ChatMessageEntity saved = persistMessage(me, channel, body, replyToId, docType, docNo);
        markReadInternal(me.getId(), channel.getId(), saved.getId());
        TeamChatDtos.MessageDto dto = toMessageDto(saved, List.of(), memberRepo.findByChannelId(channel.getId()), me.getId());
        broadcastChannelEvent(channel.getId(), "MESSAGE", dto, me, null, null);
        return dto;
    }

    public TeamChatDtos.MessageDto sendWithAttachments(
            AuthenticatedUser current,
            long channelId,
            String body,
            Long replyToId,
            String linkedDocType,
            String linkedDocNo,
            List<MultipartFile> files
    ) {
        UserEntity me = requireActiveUser(current);
        ChatChannelEntity channel = requireChannelAccess(me, channelId);
        List<MultipartFile> nonEmpty = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        String trimmedBody = body == null ? "" : body.trim();
        if (trimmedBody.isBlank() && nonEmpty.isEmpty() && (linkedDocNo == null || linkedDocNo.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a message or at least one file attachment.");
        }
        for (MultipartFile file : nonEmpty) {
            validateAttachmentSafety(file.getOriginalFilename());
        }

        ChatMessageEntity saved = persistMessage(me, channel, trimmedBody, replyToId,
                linkedDocType == null ? null : truncate(linkedDocType.trim(), 48),
                linkedDocNo == null ? null : truncate(linkedDocNo.trim(), 80));

        List<TeamChatDtos.AttachmentDto> savedAttachments = new ArrayList<>();
        for (MultipartFile file : nonEmpty) {
            savedAttachments.add(storeAttachmentFile(me, channel.getId(), saved.getId(), file));
        }

        markReadInternal(me.getId(), channel.getId(), saved.getId());
        TeamChatDtos.MessageDto dto = toMessageDto(saved, savedAttachments, memberRepo.findByChannelId(channel.getId()), me.getId());
        broadcastChannelEvent(channel.getId(), "MESSAGE", dto, me, null, null);
        return dto;
    }

    public AttachmentDownload loadAttachment(AuthenticatedUser current, long attachmentId) {
        UserEntity me = requireActiveUser(current);
        ChatAttachmentEntity att = attachmentRepo.findById(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found."));
        requireChannelAccess(me, att.getChannelId());

        Path target = chatStorageRoot.resolve(att.getStoragePath()).normalize();
        if (!target.startsWith(chatStorageRoot) || !Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment file is no longer available on disk.");
        }
        return new AttachmentDownload(att.getFileName(), att.getContentType(), isImageType(att.getContentType(), att.getFileName()), new FileSystemResource(target));
    }

    public TeamChatDtos.MessageDto editMessage(AuthenticatedUser current, long messageId, TeamChatDtos.EditMessageRequest req) {
        UserEntity me = requireActiveUser(current);
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));
        requireChannelAccess(me, msg.getChannelId());
        if (!Objects.equals(msg.getSenderId(), me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit your own messages.");
        }
        String updated = req == null || req.body() == null ? "" : req.body().trim();
        if (updated.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Edited message cannot be empty.");
        }
        msg.setBody(updated);
        msg.setEditedUtc(Instant.now().toString());
        msg = messageRepo.save(msg);

        TeamChatDtos.MessageDto dto = toMessageDto(msg, loadAttachmentMap(List.of(msg.getId())).getOrDefault(msg.getId(), List.of()),
                memberRepo.findByChannelId(msg.getChannelId()), me.getId());
        broadcastChannelEvent(msg.getChannelId(), "MESSAGE_UPDATED", dto, me, null, null);
        return dto;
    }

    public TeamChatDtos.MessageDto deleteMessage(AuthenticatedUser current, long messageId) {
        UserEntity me = requireActiveUser(current);
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));
        requireChannelAccess(me, msg.getChannelId());
        if (!Objects.equals(msg.getSenderId(), me.getId()) && !isAdminOrSuperAdmin(me.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own messages.");
        }
        msg.setDeleted(true);
        msg.setBody("[Message removed]");
        msg.setEditedUtc(Instant.now().toString());
        msg = messageRepo.save(msg);

        TeamChatDtos.MessageDto dto = toMessageDto(msg, List.of(), memberRepo.findByChannelId(msg.getChannelId()), me.getId());
        broadcastChannelEvent(msg.getChannelId(), "MESSAGE_UPDATED", dto, me, null, null);
        return dto;
    }

    public TeamChatDtos.MessageDto toggleReaction(AuthenticatedUser current, long messageId, String emojiRaw) {
        UserEntity me = requireActiveUser(current);
        ChatMessageEntity msg = messageRepo.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found."));
        requireChannelAccess(me, msg.getChannelId());
        String emoji = emojiRaw == null ? "" : emojiRaw.trim();
        if (emoji.isBlank() || emoji.length() > 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reaction emoji.");
        }
        Map<String, List<Integer>> reactions = parseReactions(msg.getReactionsJson());
        List<Integer> voters = new ArrayList<>(reactions.getOrDefault(emoji, List.of()));
        if (voters.contains(me.getId())) {
            voters.remove(me.getId());
        } else {
            voters.add(me.getId());
        }
        if (voters.isEmpty()) reactions.remove(emoji);
        else reactions.put(emoji, voters);
        msg.setReactionsJson(serializeReactions(reactions));
        msg = messageRepo.save(msg);

        TeamChatDtos.MessageDto dto = toMessageDto(msg, loadAttachmentMap(List.of(msg.getId())).getOrDefault(msg.getId(), List.of()),
                memberRepo.findByChannelId(msg.getChannelId()), me.getId());
        broadcastChannelEvent(msg.getChannelId(), "MESSAGE_UPDATED", dto, me, null, null);
        return dto;
    }

    public void markRead(AuthenticatedUser current, long channelId, long lastReadMessageId) {
        UserEntity me = requireActiveUser(current);
        requireChannelAccess(me, channelId);
        markReadInternal(me.getId(), channelId, lastReadMessageId);
        broadcastChannelEvent(channelId, "READ", null, me, null, lastReadMessageId);
    }

    public void notifyTyping(AuthenticatedUser current, long channelId, boolean typing) {
        UserEntity me = requireActiveUser(current);
        requireChannelAccess(me, channelId);
        broadcastChannelEvent(channelId, "TYPING", null, me, typing, null);
    }

    // ----------------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------------

    private ChatMessageEntity persistMessage(
            UserEntity sender,
            ChatChannelEntity channel,
            String body,
            Long replyToId,
            String linkedDocType,
            String linkedDocNo
    ) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setChannelId(channel.getId());
        msg.setSenderId(sender.getId());
        msg.setSenderUsername(sender.getUsername());
        msg.setSenderName(displayName(sender));
        msg.setSenderRole(sender.getRoleName());
        msg.setBody(body);
        if (replyToId != null && replyToId > 0) {
            messageRepo.findById(replyToId)
                    .filter(orig -> Objects.equals(orig.getChannelId(), channel.getId()))
                    .ifPresent(orig -> {
                        msg.setReplyToId(orig.getId());
                        String snippet = orig.isDeleted() ? "[Removed]" : safe(orig.getBody());
                        msg.setReplyPreview(truncate(orig.getSenderName() + ": " + snippet, 220));
                    });
        }
        if (linkedDocType != null && !linkedDocType.isBlank()) msg.setLinkedDocType(linkedDocType);
        if (linkedDocNo != null && !linkedDocNo.isBlank()) msg.setLinkedDocNo(linkedDocNo);
        msg.setCreatedUtc(Instant.now().toString());
        return messageRepo.save(msg);
    }

    private TeamChatDtos.AttachmentDto storeAttachmentFile(UserEntity uploader, long channelId, long messageId, MultipartFile file) {
        String rawName = file.getOriginalFilename() == null ? "attachment" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String safeName = rawName.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        if (safeName.isBlank()) safeName = "file";
        safeName = truncate(safeName, 140);

        Path channelDir = chatStorageRoot.resolve(String.valueOf(channelId)).normalize();
        if (!channelDir.startsWith(chatStorageRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment directory.");
        }
        String storedFileName = UUID.randomUUID() + "_" + safeName;
        Path dest = channelDir.resolve(storedFileName).normalize();
        if (!dest.startsWith(channelDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attachment path.");
        }

        String sha256;
        try {
            Files.createDirectories(channelDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            sha256 = sha256Hex(dest);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save attachment: " + ex.getMessage());
        }

        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setMessageId(messageId);
        entity.setChannelId(channelId);
        entity.setFileName(safeName);
        entity.setContentType(file.getContentType());
        entity.setFileSize(file.getSize());
        entity.setStoragePath(channelId + "/" + storedFileName);
        entity.setSha256(sha256);
        entity.setUploadedBy(uploader.getId());
        entity.setUploadedUtc(Instant.now().toString());
        entity = attachmentRepo.save(entity);
        return toAttachmentDto(entity);
    }

    private void ensureDefaultChannelsSynced(UserEntity current) {
        String now = Instant.now().toString();
        // 1. Company-wide #general channel for internal company team (excluding Super Admin unless needed)
        ChatChannelEntity general = channelRepo.findByChannelKey("GROUP:GENERAL").orElseGet(() -> {
            ChatChannelEntity ch = new ChatChannelEntity();
            ch.setChannelType("GROUP");
            ch.setChannelKey("GROUP:GENERAL");
            ch.setName("general");
            ch.setCreatedBy(current.getId());
            ch.setCreatedUtc(now);
            return channelRepo.save(ch);
        });
        List<UserEntity> allUsers = userRepo.findAll();
        for (UserEntity u : allUsers) {
            if (!u.isActive() || u.getId() == null) continue;
            if (!isSuperAdmin(u.getRoleName())) {
                ensureMember(general.getId(), u.getId(), "ADMIN".equalsIgnoreCase(u.getRoleName()) ? "OWNER" : "MEMBER");
            }
        }

        // 2. Admin <-> Super Admin Escalation Channel
        if (isAdminOrSuperAdmin(current.getRoleName())) {
            ChatChannelEntity support = channelRepo.findByChannelKey("ADMIN_SUPPORT:GLOBAL").orElseGet(() -> {
                ChatChannelEntity ch = new ChatChannelEntity();
                ch.setChannelType("ADMIN_SUPPORT");
                ch.setChannelKey("ADMIN_SUPPORT:GLOBAL");
                ch.setName("Super Admin Support");
                ch.setCreatedBy(current.getId());
                ch.setCreatedUtc(now);
                return channelRepo.save(ch);
            });
            for (UserEntity u : allUsers) {
                if (u.isActive() && u.getId() != null && isAdminOrSuperAdmin(u.getRoleName())) {
                    ensureMember(support.getId(), u.getId(), isSuperAdmin(u.getRoleName()) ? "OWNER" : "MEMBER");
                }
            }
        }
    }

    private void ensureMember(long channelId, int userId, String role) {
        if (memberRepo.existsByChannelIdAndUserId(channelId, userId)) return;
        ChatChannelMemberEntity mem = new ChatChannelMemberEntity();
        mem.setChannelId(channelId);
        mem.setUserId(userId);
        mem.setMemberRole(role);
        mem.setLastReadMessageId(0L);
        mem.setJoinedUtc(Instant.now().toString());
        memberRepo.save(mem);
    }

    private void markReadInternal(int userId, long channelId, long lastReadId) {
        if (lastReadId <= 0) return;
        memberRepo.findByChannelIdAndUserId(channelId, userId).ifPresent(m -> {
            if (lastReadId > m.getLastReadMessageId()) {
                m.setLastReadMessageId(lastReadId);
                memberRepo.save(m);
            }
        });
    }

    private ChatPresenceEntity touchHeartbeat(int userId, String explicitStatus, String note, boolean updateNote) {
        String now = Instant.now().toString();
        ChatPresenceEntity p = presenceRepo.findById(userId).orElseGet(() -> {
            ChatPresenceEntity np = new ChatPresenceEntity();
            np.setUserId(userId);
            np.setStatus("ONLINE");
            return np;
        });
        if (explicitStatus != null) {
            p.setStatus(explicitStatus);
        } else if ("OFFLINE".equalsIgnoreCase(p.getStatus())) {
            // Restore automatic ONLINE when user logs back in / actively heartbeats without manual AWAY/BUSY
            p.setStatus("ONLINE");
        }
        if (updateNote) {
            p.setCustomNote(note == null || note.isBlank() ? null : note);
        }
        p.setLastHeartbeatUtc(now);
        p.setUpdatedUtc(now);
        return presenceRepo.save(p);
    }

    private void broadcastChannelEvent(long channelId, String type, TeamChatDtos.MessageDto msg, UserEntity actor, Boolean typing, Long readId) {
        List<Integer> memberIds = memberRepo.findByChannelId(channelId).stream()
                .map(ChatChannelMemberEntity::getUserId)
                .toList();
        broker.sendToUsers(memberIds, type,
                new TeamChatDtos.ChatStreamEvent(type, channelId, msg, null, actor.getId(), displayName(actor), typing, readId));
    }

    private List<UserEntity> visibleDirectoryUsers(UserEntity me) {
        boolean iAmAdmin = "ADMIN".equalsIgnoreCase(me.getRoleName());
        boolean iAmSuperAdmin = isSuperAdmin(me.getRoleName());
        return userRepo.findAll().stream()
                .filter(u -> u.isActive() && u.getId() != null && !Objects.equals(u.getId(), me.getId()))
                .filter(u -> {
                    if (iAmSuperAdmin) return isAdminOrSuperAdmin(u.getRoleName());
                    if (isSuperAdmin(u.getRoleName())) return iAmAdmin;
                    return true;
                })
                .toList();
    }

    private UserEntity requireActiveUser(AuthenticatedUser current) {
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }
        return userRepo.findById(current.id())
                .filter(UserEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account is inactive."));
    }

    private ChatChannelEntity requireChannelAccess(UserEntity me, long channelId) {
        ChatChannelEntity channel = channelRepo.findById(channelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat channel not found."));
        if ("ADMIN_SUPPORT".equals(channel.getChannelType()) && !isAdminOrSuperAdmin(me.getRoleName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super Admin Support is restricted to Admins.");
        }
        if (!memberRepo.existsByChannelIdAndUserId(channelId, me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this channel.");
        }
        return channel;
    }

    private TeamChatDtos.ChannelSummaryDto buildChannelSummary(
            ChatChannelEntity ch,
            ChatChannelMemberEntity myMembership,
            int selfUserId,
            Map<Integer, UserEntity> userById,
            Map<Integer, ChatPresenceEntity> presenceMap
    ) {
        List<ChatChannelMemberEntity> members = memberRepo.findByChannelId(ch.getId());
        List<TeamChatDtos.MemberDto> memberDtos = new ArrayList<>();
        Integer peerUserId = null;
        String peerStatus = null;
        String peerNote = null;
        String effectiveName = ch.getName();

        for (ChatChannelMemberEntity m : members) {
            UserEntity u = userById.get(m.getUserId());
            if (u == null) continue;
            ChatPresenceEntity p = presenceMap.get(u.getId());
            String st = computeEffectiveStatus(p);
            memberDtos.add(new TeamChatDtos.MemberDto(u.getId(), u.getUsername(), displayName(u), u.getRoleName(),
                    m.getMemberRole(), st, m.getLastReadMessageId()));
            if ("DIRECT".equals(ch.getChannelType()) && !Objects.equals(u.getId(), selfUserId)) {
                peerUserId = u.getId();
                peerStatus = st;
                peerNote = p != null ? p.getCustomNote() : null;
                effectiveName = displayName(u);
            }
        }

        List<ChatMessageEntity> latest = messageRepo.findLatestByChannel(ch.getId(), PageRequest.of(0, 1));
        String preview = "";
        String lastUtc = ch.getCreatedUtc();
        if (!latest.isEmpty()) {
            ChatMessageEntity last = latest.getFirst();
            preview = last.isDeleted() ? "[Removed]" : (safe(last.getBody()).isBlank() ? "[Attachment]" : safe(last.getBody()));
            preview = truncate(last.getSenderName() + ": " + preview, 90);
            lastUtc = last.getCreatedUtc();
        }
        long unread = messageRepo.countUnread(ch.getId(), myMembership.getLastReadMessageId(), selfUserId);
        return new TeamChatDtos.ChannelSummaryDto(ch.getId(), ch.getChannelType(), ch.getChannelKey(), effectiveName,
                unread, preview, lastUtc, peerUserId, peerStatus, peerNote, memberDtos);
    }

    private TeamChatDtos.MessageDto toMessageDto(
            ChatMessageEntity m,
            List<TeamChatDtos.AttachmentDto> attachments,
            List<ChatChannelMemberEntity> members,
            int viewerUserId
    ) {
        boolean readByPeer = false;
        if (Objects.equals(m.getSenderId(), viewerUserId)) {
            for (ChatChannelMemberEntity mem : members) {
                if (!Objects.equals(mem.getUserId(), viewerUserId) && mem.getLastReadMessageId() >= m.getId()) {
                    readByPeer = true;
                    break;
                }
            }
        }
        return new TeamChatDtos.MessageDto(
                m.getId(), m.getChannelId(), m.getSenderId(), m.getSenderUsername(), m.getSenderName(), m.getSenderRole(),
                m.getBody(), m.getReplyToId(), m.getReplyPreview(), m.getLinkedDocType(), m.getLinkedDocNo(),
                parseReactions(m.getReactionsJson()), attachments, m.getCreatedUtc(), m.getEditedUtc(), m.isDeleted(), readByPeer
        );
    }

    private TeamChatDtos.AttachmentDto toAttachmentDto(ChatAttachmentEntity a) {
        return new TeamChatDtos.AttachmentDto(a.getId(), a.getMessageId(), a.getChannelId(), a.getFileName(),
                a.getContentType(), a.getFileSize(), isImageType(a.getContentType(), a.getFileName()), a.getUploadedUtc());
    }

    private Map<Long, List<TeamChatDtos.AttachmentDto>> loadAttachmentMap(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        Map<Long, List<TeamChatDtos.AttachmentDto>> map = new HashMap<>();
        for (ChatAttachmentEntity a : attachmentRepo.findByMessageIdIn(messageIds)) {
            map.computeIfAbsent(a.getMessageId(), k -> new ArrayList<>()).add(toAttachmentDto(a));
        }
        return map;
    }

    private Map<Integer, ChatPresenceEntity> loadPresenceMap(List<UserEntity> users) {
        List<Integer> ids = users.stream().map(UserEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Integer, ChatPresenceEntity> map = new HashMap<>();
        for (ChatPresenceEntity p : presenceRepo.findByUserIdIn(ids)) {
            map.put(p.getUserId(), p);
        }
        return map;
    }

    private TeamChatDtos.PresenceDto toPresenceDto(UserEntity u, ChatPresenceEntity p) {
        return new TeamChatDtos.PresenceDto(
                u.getId(), u.getUsername(), displayName(u), u.getRoleName(),
                safe(u.getDepartment()), safe(u.getBranch()),
                computeEffectiveStatus(p), p != null ? p.getCustomNote() : null,
                p != null ? p.getLastHeartbeatUtc() : null, p != null ? p.getUpdatedUtc() : null
        );
    }

    private static String computeEffectiveStatus(ChatPresenceEntity p) {
        if (p == null || "OFFLINE".equalsIgnoreCase(p.getStatus())) return "OFFLINE";
        String hb = p.getLastHeartbeatUtc();
        if (hb == null || hb.isBlank()) return "OFFLINE";
        try {
            Instant last = Instant.parse(hb);
            if (Duration.between(last, Instant.now()).compareTo(HEARTBEAT_TIMEOUT) > 0) {
                return "OFFLINE";
            }
        } catch (Exception ignored) {
            return "OFFLINE";
        }
        return p.getStatus().toUpperCase(Locale.ROOT);
    }

    private Map<String, List<Integer>> parseReactions(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(raw, new TypeReference<LinkedHashMap<String, List<Integer>>>() {});
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String serializeReactions(Map<String, List<Integer>> map) {
        try {
            return json.writeValueAsString(map);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static void validateAttachmentSafety(String fileName) {
        String lower = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_EXTENSIONS) {
            if (lower.endsWith(blocked)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Executable file type '" + blocked + "' cannot be uploaded in chat for security.");
            }
        }
    }

    private static boolean isImageType(String contentType, String fileName) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("image/")) return true;
        String fn = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".gif") || fn.endsWith(".webp");
    }

    private static boolean isSuperAdmin(String role) {
        return "SUPER_ADMIN".equalsIgnoreCase(role) || "SUPERADMIN".equalsIgnoreCase(role);
    }

    private static boolean isAdminOrSuperAdmin(String role) {
        return "ADMIN".equalsIgnoreCase(role) || isSuperAdmin(role);
    }

    private static String displayName(UserEntity u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private static int statusOrder(String status) {
        return switch (status == null ? "OFFLINE" : status.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> 0;
            case "BUSY" -> 1;
            case "AWAY" -> 2;
            default -> 3;
        };
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int max) { return s == null ? "" : (s.length() <= max ? s : s.substring(0, max)); }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            throw new IOException("Checksum calculation failed", ex);
        }
    }

    public record AttachmentDownload(String fileName, String contentType, boolean inlineImage, Resource resource) {}
}
