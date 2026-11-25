package org.example.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class TeamChatSseBroker {
    private static final Logger log = LoggerFactory.getLogger(TeamChatSseBroker.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    public SseEmitter register(int userId) {
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}", MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            remove(userId, emitter);
        }
        return emitter;
    }

    public void sendToUsers(Collection<Integer> userIds, String eventName, TeamChatDtos.ChatStreamEvent payload) {
        if (userIds == null || userIds.isEmpty()) return;
        for (Integer uid : userIds) {
            if (uid == null) continue;
            sendToUser(uid, eventName, payload);
        }
    }

    public void broadcastAll(TeamChatDtos.ChatStreamEvent payload) {
        for (Integer uid : List.copyOf(userEmitters.keySet())) {
            sendToUser(uid, payload.eventType(), payload);
        }
    }

    private void sendToUser(int userId, String eventName, TeamChatDtos.ChatStreamEvent payload) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) return;
        String serialized;
        try {
            serialized = json.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Failed to serialize SSE chat payload: {}", ex.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(serialized, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                log.debug("Removing closed SSE chat emitter for user {}: {}", userId, ex.getMessage());
                remove(userId, emitter);
            }
        }
    }

    private void remove(int userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.get(userId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                userEmitters.remove(userId, list);
            }
        }
    }
}
