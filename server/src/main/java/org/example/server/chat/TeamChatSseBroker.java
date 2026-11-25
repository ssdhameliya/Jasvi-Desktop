package org.example.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class TeamChatSseBroker {
    private static final Logger log = LoggerFactory.getLogger(TeamChatSseBroker.class);
    private static final int MAX_EMITTERS_PER_USER = 3;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final ExecutorService dispatchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "team-chat-sse-keepalive");
        t.setDaemon(true);
        return t;
    });

    public TeamChatSseBroker() {
        keepaliveScheduler.scheduleAtFixedRate(this::sendKeepalives, 20, 20, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        keepaliveScheduler.shutdownNow();
        dispatchExecutor.shutdownNow();
    }

    public SseEmitter register(int userId) {
        SseEmitter emitter = new SseEmitter(1_800_000L);
        CopyOnWriteArrayList<SseEmitter> list = userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        while (list.size() >= MAX_EMITTERS_PER_USER) {
            SseEmitter oldest = list.removeFirst();
            try { oldest.complete(); } catch (Exception ignored) {}
        }
        list.add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            try { emitter.complete(); } catch (Exception ignored) {}
        });
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
        String serialized = serialize(payload);
        if (serialized == null) return;
        List<Integer> targets = List.copyOf(userIds);
        dispatchExecutor.execute(() -> {
            for (Integer uid : targets) {
                if (uid != null) deliverJson(uid, eventName, serialized);
            }
        });
    }

    public void broadcastAll(TeamChatDtos.ChatStreamEvent payload) {
        if (userEmitters.isEmpty()) return;
        String serialized = serialize(payload);
        if (serialized == null) return;
        List<Integer> targets = List.copyOf(userEmitters.keySet());
        dispatchExecutor.execute(() -> {
            for (Integer uid : targets) {
                deliverJson(uid, payload.eventType(), serialized);
            }
        });
    }

    private void sendKeepalives() {
        for (var entry : userEmitters.entrySet()) {
            int uid = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception ex) {
                    remove(uid, emitter);
                }
            }
        }
    }

    private void deliverJson(int userId, String eventName, String serializedJson) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(serializedJson, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                log.debug("Removing closed SSE chat emitter for user {}: {}", userId, ex.getMessage());
                remove(userId, emitter);
            }
        }
    }

    private String serialize(TeamChatDtos.ChatStreamEvent payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("Failed to serialize SSE chat payload: {}", ex.getMessage());
            return null;
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
