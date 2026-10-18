package com.homelabhub.internal.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/**
 * The terminal channel (plan 5.3). Client -> server: JSON text frames {auth, input, resize, ping}; the first must be
 * {type:auth, ticket} within 5 s. Server -> client: terminal output as BINARY frames (raw bytes, so xterm decodes
 * UTF-8 split across frames correctly) and JSON text frames {ready, warn, exit, pong}. Terminal content is never logged.
 *
 * <p>Backpressure: one thread per session reads the shell and sends. Sends go through a
 * ConcurrentWebSocketSessionDecorator; while the browser is slow the thread blocks in send, so it stops reading the
 * shell and SSH flow control slows the remote side. If the queue still overflows the session ends with "too_slow".
 */
@Component
class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    static final Duration AUTH_WITHIN = Duration.ofSeconds(5);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int READ_CHUNK = 8192;

    /** Per-socket state. */
    private final class Channel implements TerminalSessionManager.Endpoint {
        final WebSocketSession ws;
        volatile boolean authStarted;
        volatile TerminalSessionManager.Session session;
        /** System.nanoTime() when the current output send started, 0 when none is in progress. */
        volatile long sendingSince;

        Channel(WebSocketSession ws) {
            this.ws = ws;
        }

        @Override
        public void warn(int secondsLeft) {
            send(this, Map.of("type", "warn", "secondsLeft", secondsLeft));
        }

        @Override
        public void closed(String reason, String message, Integer exitCode) {
            exit(this, reason, message, exitCode);
        }
    }

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final TerminalFeature feature;
    private final TerminalTickets tickets;
    private final TerminalSessionManager sessions;
    private final TerminalConnector connector;
    private final TerminalAudit audit;
    private final ObjectMapper json;

    TerminalWebSocketHandler(TerminalFeature feature, TerminalTickets tickets, TerminalSessionManager sessions,
                             TerminalConnector connector, TerminalAudit audit, ObjectMapper json) {
        this.feature = feature;
        this.tickets = tickets;
        this.sessions = sessions;
        this.connector = connector;
        this.audit = audit;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        // Larger frames close the socket (1009) before they reach the handler. Set per socket rather than with a container
        // bean, which would need a real servlet container and break the MockMvc tests.
        raw.setTextMessageSizeLimit(feature.props().maxMessageBytes());
        raw.setBinaryMessageSizeLimit(1024);
        if (raw instanceof NativeWebSocketSession n && n.getNativeSession() instanceof jakarta.websocket.Session js) {
            // Tomcat's own limit for one blocking send (default 20 s); a browser that stops reading ends the session sooner.
            js.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", (long) SEND_TIME_LIMIT_MS);
        }
        WebSocketSession ws = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS,
                feature.props().maxQueuedOutputBytes(), ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        Channel ch = new Channel(ws);
        channels.put(raw.getId(), ch);
        sessions.awaitAuth(ch, AUTH_WITHIN, () -> {
            deny(ch, null, "auth_timeout");
            exit(ch, "denied", "No ticket was presented in time. Connect again.", null);
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        Channel ch = channels.get(raw.getId());
        if (ch == null) {
            return;
        }
        JsonNode msg;
        try {
            msg = json.readTree(message.getPayload());
        } catch (IOException e) {
            msg = null;
        }
        if (msg == null || !msg.isObject()) {
            fail(ch, "Malformed message.");
            return;
        }
        String type = msg.path("type").asText("");
        if (!ch.authStarted) {
            if (!type.equals("auth")) {
                fail(ch, "The first message must present the ticket.");
                return;
            }
            ch.authStarted = true;
            sessions.authenticated(ch);
            startSession(ch, msg);
            return;
        }
        TerminalSessionManager.Session s = ch.session;
        switch (type) {
            case "ping" -> send(ch, Map.of("type", "pong"));
            case "input" -> {
                TerminalConnection c = s == null ? null : s.connection();
                if (c == null || s.ended()) {
                    return;
                }
                byte[] data = msg.path("data").asText("").getBytes(StandardCharsets.UTF_8);
                if (data.length == 0) {
                    return;
                }
                try {
                    c.write(data);
                    s.countIn(data.length);
                    sessions.activity(s);
                } catch (IOException e) {
                    sessions.end(s, "error", "The connection to the host was lost.", null);
                }
            }
            case "resize" -> {
                TerminalConnection c = s == null ? null : s.connection();
                if (c == null || s.ended()) {
                    return;
                }
                c.resize(clamp(msg.path("cols").asInt(80), 10, 500), clamp(msg.path("rows").asInt(24), 3, 200));
                sessions.activity(s);
            }
            case "auth" -> fail(ch, "Already authenticated.");
            default -> {
                // unknown types are ignored so an older server tolerates a newer page
            }
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession raw, BinaryMessage message) {
        Channel ch = channels.get(raw.getId());
        if (ch != null) {
            fail(ch, "Binary messages are not accepted.");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        log.debug("terminal websocket error: {}", exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        Channel ch = channels.remove(raw.getId());
        if (ch == null) {
            return;
        }
        sessions.authenticated(ch); // drops a pending handshake, if any
        TerminalSessionManager.Session s = ch.session;
        if (s != null) {
            String reason = "disconnected";
            long since = ch.sendingSince;
            if (status.getCode() == CloseStatus.SESSION_NOT_RELIABLE.getCode()
                    || since != 0 && System.nanoTime() - since > SEND_TIME_LIMIT_MS / 2 * 1_000_000L) {
                reason = "too_slow"; // the socket was closed while output had been stuck for a while
            } else if (status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()) {
                reason = "logout"; // Tomcat closes sockets of an authenticated HTTP session that ended (sign out, expiry)
            }
            sessions.end(s, reason, "The browser disconnected.", null);
        }
    }

    // ---- session start ---------------------------------------------------------------------------------------------

    private void startSession(Channel ch, JsonNode msg) {
        Map<String, Object> attrs = ch.ws.getAttributes();
        String login = (String) attrs.get(TerminalHandshakeInterceptor.LOGIN_SESSION);
        TerminalTickets.Result r = tickets.redeem(msg.path("ticket").asText(null), login);
        if (r.outcome() != TerminalTickets.Outcome.OK) {
            deny(ch, null, "ticket_" + r.outcome().name().toLowerCase());
            exit(ch, "denied", r.outcome() == TerminalTickets.Outcome.EXPIRED
                    ? "The ticket expired before it was used. Connect again." : "The ticket is not valid. Connect again.", null);
            return;
        }
        Optional<TerminalSpec> host = feature.enabled() ? feature.terminal(r.hostId()) : Optional.empty();
        if (host.isEmpty()) {
            deny(ch, r.hostId(), "unknown_host");
            exit(ch, "denied", "That terminal is no longer in dashboard.yaml.", null);
            return;
        }
        TerminalSessionManager.Session s = sessions.reserve(login, r.hostId(), (String) attrs.get(TerminalHandshakeInterceptor.CLIENT_IP),
                (String) attrs.get(TerminalHandshakeInterceptor.USER_AGENT), ch);
        if (s == null) {
            deny(ch, r.hostId(), "limit");
            exit(ch, "denied", TerminalController.limitMessage(feature.props().maxSessions()), null);
            return;
        }
        ch.session = s;
        int cols = clamp(msg.path("cols").asInt(80), 10, 500);
        int rows = clamp(msg.path("rows").asInt(24), 3, 200);
        Thread t = new Thread(() -> run(ch, s, host.get(), cols, rows), "terminal-" + r.hostId());
        t.setDaemon(true);
        t.start();
    }

    /** Connects, then relays shell output until either side ends. The only thread a session owns. */
    private void run(Channel ch, TerminalSessionManager.Session s, TerminalSpec host, int cols, int rows) {
        TerminalConnection c;
        try {
            c = connector.open(host, cols, rows);
        } catch (TerminalException e) {
            audit.record(TerminalAudit.Event.close, host.id(), ip(ch), ua(ch), "reason=connect_failed code=" + e.code());
            sessions.end(s, "error", e.getMessage(), null);
            return;
        } catch (RuntimeException e) {
            log.warn("terminal connect to {} failed: {}", host.id(), e.toString());
            sessions.end(s, "error", "Could not open the terminal.", null);
            return;
        }
        if (!sessions.attach(s, c)) {
            return;
        }
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("host", host.id());
        ready.put("label", host.label());
        ready.put("user", host.user());
        send(ch, ready);

        byte[] buf = new byte[READ_CHUNK];
        InputStream out = c.output();
        while (!s.ended()) {
            int n;
            try {
                n = out.read(buf);
            } catch (IOException | RuntimeException e) {
                sessions.end(s, "error", "The connection to the host was lost.", null);
                return;
            }
            if (n < 0) {
                Integer code = awaitExit(c);
                sessions.end(s, "closed", code == null ? "The shell ended." : "The shell ended (exit code " + code + ").", code);
                return;
            }
            if (n == 0) {
                continue;
            }
            try {
                // Blocks while the browser is slow; meanwhile the shell is not read, so SSH flow control pauses the host.
                ch.sendingSince = System.nanoTime();
                ch.ws.sendMessage(new BinaryMessage(Arrays.copyOf(buf, n)));
                ch.sendingSince = 0;
                s.countOut(n);
            } catch (IOException | SessionLimitExceededException e) {
                if (!s.ended()) {
                    sessions.end(s, "too_slow", "The browser could not keep up with the output, so the terminal was closed.", null);
                }
                return;
            } catch (RuntimeException e) {
                sessions.end(s, "error", "The browser connection failed.", null);
                return;
            }
        }
    }

    /** The exit-status message may arrive a moment after the end of output. */
    private static Integer awaitExit(TerminalConnection c) {
        for (int i = 0; i < 20; i++) {
            Integer code = c.exitStatus();
            if (code != null) {
                return code;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void deny(Channel ch, String hostId, String reason) {
        audit.record(TerminalAudit.Event.denied, hostId, ip(ch), ua(ch), "reason=" + reason);
    }

    private void fail(Channel ch, String message) {
        TerminalSessionManager.Session s = ch.session;
        if (s != null) {
            sessions.end(s, "error", message, null);
        } else {
            exit(ch, "error", message, null);
        }
    }

    private void exit(Channel ch, String reason, String message, Integer exitCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "exit");
        m.put("reason", reason);
        m.put("message", message);
        if (exitCode != null) {
            m.put("exitCode", exitCode);
        }
        send(ch, m);
        try {
            if (ch.ws.isOpen()) {
                ch.ws.close(CloseStatus.NORMAL);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("closing terminal websocket: {}", e.toString());
        }
    }

    private void send(Channel ch, Map<String, ?> m) {
        try {
            if (ch.ws.isOpen()) {
                ch.ws.sendMessage(new TextMessage(json.writeValueAsString(m)));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("terminal send failed: {}", e.toString());
        }
    }

    private static String ip(Channel ch) {
        return (String) ch.ws.getAttributes().get(TerminalHandshakeInterceptor.CLIENT_IP);
    }

    private static String ua(Channel ch) {
        return (String) ch.ws.getAttributes().get(TerminalHandshakeInterceptor.USER_AGENT);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
