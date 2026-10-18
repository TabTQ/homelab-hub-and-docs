package com.homelabhub.internal.status;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Fan-out of status changes to the browsers that have the dashboard open (one-way, so SSE, not WebSocket). */
@Component
public class StatusStream {

    static final int MAX_SUBSCRIBERS = 20;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** @return null when too many browsers are already connected */
    public SseEmitter subscribe(Object initialSnapshot) {
        if (emitters.size() >= MAX_SUBSCRIBERS) {
            return null;
        }
        SseEmitter e = new SseEmitter(30 * 60 * 1000L);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(t -> emitters.remove(e));
        emitters.add(e);
        try {
            e.send(SseEmitter.event().name("snapshot").data(initialSnapshot, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(e);
        }
        return e;
    }

    public void broadcast(String event, Object data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    void heartbeat() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    public int subscribers() {
        return emitters.size();
    }
}
