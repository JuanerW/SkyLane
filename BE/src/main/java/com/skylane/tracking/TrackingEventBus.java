package com.skylane.tracking;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Open SSE connections per order. One backend instance only (D24), so in-memory is enough. */
@Component
public class TrackingEventBus {
    public static final Set<String> TERMINAL = Set.of("completed", "canceled", "failed");

    public record StatusEvent(String status, Instant at) {
    }

    public record PositionEvent(double lat, double lng, double progress, long remainingS) {
    }

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void register(UUID orderId, SseEmitter emitter) {
        emitters.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> unregister(orderId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
    }

    public Set<UUID> watchedOrders() {
        return emitters.keySet();
    }

    public void publishStatus(UUID orderId, long eventId, String status, Instant at) {
        for (SseEmitter emitter : list(orderId)) {
            if (sendStatus(emitter, eventId, status, at) && TERMINAL.contains(status)) {
                emitter.complete();
            }
        }
        if (TERMINAL.contains(status)) {
            emitters.remove(orderId);
        }
    }

    public void publishPosition(UUID orderId, PositionEvent position) {
        for (SseEmitter emitter : list(orderId)) {
            sendPosition(emitter, orderId, position);
        }
    }

    public void heartbeat() {
        emitters.forEach((orderId, list) -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                unregister(orderId, emitter);
            }
        }));
    }

    /** false when the client is gone. */
    public static boolean sendStatus(SseEmitter emitter, long eventId, String status, Instant at) {
        try {
            emitter.send(SseEmitter.event().name("status").id(Long.toString(eventId))
                    .data(new StatusEvent(status, at), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private void sendPosition(SseEmitter emitter, UUID orderId, PositionEvent position) {
        try {
            emitter.send(SseEmitter.event().name("position").data(position, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            unregister(orderId, emitter);
        }
    }

    private List<SseEmitter> list(UUID orderId) {
        return emitters.getOrDefault(orderId, List.of());
    }

    private void unregister(UUID orderId, SseEmitter emitter) {
        emitters.computeIfPresent(orderId, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
