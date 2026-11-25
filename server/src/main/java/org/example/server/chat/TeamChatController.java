package org.example.server.chat;

import org.example.server.security.AuthenticatedUser;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class TeamChatController {
    private final TeamChatService service;
    private final TeamChatSseBroker broker;

    public TeamChatController(TeamChatService service, TeamChatSseBroker broker) {
        this.service = service;
        this.broker = broker;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser current) {
        if (current == null) {
            throw new SecurityException("Authentication required");
        }
        return broker.register(current.id());
    }

    @GetMapping("/directory")
    public TeamChatDtos.DirectoryResponse directory(@AuthenticationPrincipal AuthenticatedUser current) {
        return service.directory(current);
    }

    @PutMapping("/presence")
    public TeamChatDtos.PresenceDto updatePresence(
            @AuthenticationPrincipal AuthenticatedUser current,
            @RequestBody TeamChatDtos.PresenceUpdateRequest request
    ) {
        return service.updatePresence(current, request);
    }

    @PostMapping("/presence/heartbeat")
    public TeamChatDtos.PresenceDto heartbeat(@AuthenticationPrincipal AuthenticatedUser current) {
        return service.heartbeat(current);
    }

    @PostMapping("/channels/direct")
    public TeamChatDtos.ChannelSummaryDto openDirect(
            @AuthenticationPrincipal AuthenticatedUser current,
            @RequestBody TeamChatDtos.OpenDirectRequest request
    ) {
        return service.openDirectChannel(current, request.peerUserId());
    }

    @PostMapping("/channels/group")
    public TeamChatDtos.ChannelSummaryDto createGroup(
            @AuthenticationPrincipal AuthenticatedUser current,
            @RequestBody TeamChatDtos.CreateGroupRequest request
    ) {
        return service.createGroupChannel(current, request);
    }

    @GetMapping("/channels/{channelId}/messages")
    public List<TeamChatDtos.MessageDto> listMessages(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long channelId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "60") int limit
    ) {
        return service.listMessages(current, channelId, beforeId, q, limit);
    }

    @PostMapping("/channels/{channelId}/messages")
    public TeamChatDtos.MessageDto sendTextMessage(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long channelId,
            @RequestBody TeamChatDtos.SendMessageRequest request
    ) {
        return service.sendTextMessage(current, channelId, request);
    }

    @PostMapping(path = "/channels/{channelId}/messages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TeamChatDtos.MessageDto sendWithAttachments(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long channelId,
            @RequestParam(required = false) String body,
            @RequestParam(required = false) Long replyToId,
            @RequestParam(required = false) String linkedDocType,
            @RequestParam(required = false) String linkedDocNo,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        return service.sendWithAttachments(current, channelId, body, replyToId, linkedDocType, linkedDocNo, files);
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long attachmentId
    ) {
        TeamChatService.AttachmentDownload dl = service.loadAttachment(current, attachmentId);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        try {
            if (dl.contentType() != null && !dl.contentType().isBlank()) {
                mediaType = MediaType.parseMediaType(dl.contentType());
            }
        } catch (Exception ignored) {}

        ContentDisposition disposition = (dl.inlineImage() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(dl.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(dl.resource());
    }

    @PutMapping("/messages/{messageId}")
    public TeamChatDtos.MessageDto editMessage(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long messageId,
            @RequestBody TeamChatDtos.EditMessageRequest request
    ) {
        return service.editMessage(current, messageId, request);
    }

    @DeleteMapping("/messages/{messageId}")
    public TeamChatDtos.MessageDto deleteMessage(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long messageId
    ) {
        return service.deleteMessage(current, messageId);
    }

    @PostMapping("/messages/{messageId}/react")
    public TeamChatDtos.MessageDto react(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long messageId,
            @RequestBody TeamChatDtos.ReactRequest request
    ) {
        return service.toggleReaction(current, messageId, request.emoji());
    }

    @PostMapping("/channels/{channelId}/read")
    public Map<String, Boolean> markRead(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long channelId,
            @RequestBody TeamChatDtos.MarkReadRequest request
    ) {
        service.markRead(current, channelId, request.lastReadMessageId());
        return Map.of("ok", Boolean.TRUE);
    }

    @PostMapping("/channels/{channelId}/typing")
    public Map<String, Boolean> typing(
            @AuthenticationPrincipal AuthenticatedUser current,
            @PathVariable long channelId,
            @RequestBody TeamChatDtos.TypingRequest request
    ) {
        service.notifyTyping(current, channelId, request != null && request.typing());
        return Map.of("ok", Boolean.TRUE);
    }
}
