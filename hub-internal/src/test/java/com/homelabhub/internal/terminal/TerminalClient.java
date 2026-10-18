package com.homelabhub.internal.terminal;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A "browser" for the terminal integration tests: its own cookie jar, CSRF handling and a WebSocket. */
public final class TerminalClient {

    final int port;
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    public final HttpClient http;

    public TerminalClient(int port) {
        this.port = port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    public HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        String xsrf = cookie("XSRF-TOKEN");
        if (xsrf != null) {
            b.header("X-XSRF-TOKEN", xsrf);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    public TerminalClient login(String password) throws Exception {
        call("GET", "/api/auth/status", null);
        HttpResponse<String> r = call("POST", "/api/auth/login", "{\"password\":\"" + password + "\"}");
        if (r.statusCode() != 200) {
            throw new AssertionError("login failed: " + r.statusCode() + " " + r.body());
        }
        call("GET", "/api/auth/status", null); // fresh CSRF cookie after the session id change
        return this;
    }

    HttpResponse<String> stepUp(String hostId, String password) throws Exception {
        return call("POST", "/api/admin/terminal/sessions", "{\"hostId\":\"" + hostId + "\",\"password\":\"" + password + "\"}");
    }

    String ticket(String password) throws Exception {
        HttpResponse<String> r = stepUp("pi", password);
        if (r.statusCode() != 200) {
            throw new AssertionError("step-up failed: " + r.statusCode() + " " + r.body());
        }
        return r.body().replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");
    }

    Ws connect() throws Exception {
        return connect("http://localhost:" + port);
    }

    Ws connect(String origin) throws Exception {
        Ws ws = new Ws();
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (origin != null) {
            b.header("Origin", origin);
        }
        String session = cookie("HUBSESSION");
        if (session != null) {
            b.header("Cookie", "HUBSESSION=" + session);
        }
        ws.socket = b.buildAsync(URI.create("ws://localhost:" + port + "/api/admin/terminal/ws"), ws).get(5, TimeUnit.SECONDS);
        return ws;
    }

    /** Sends a hand-written upgrade request and returns the HTTP status line, to test refused handshakes. */
    String rawUpgrade(String host, String origin, boolean withCookie) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET /api/admin/terminal/ws HTTP/1.1\r\nHost: " + host + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n");
            if (origin != null) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            if (withCookie && cookie("HUBSESSION") != null) {
                req.append("Cookie: HUBSESSION=").append(cookie("HUBSESSION")).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    static final class Ws implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        volatile boolean paused;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                texts.add(partial.toString());
                seen.add(partial.toString());
                partial.setLength(0);
            }
            if (!paused) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            synchronized (output) {
                output.write(b, 0, b.length);
            }
            if (!paused) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        void auth(String ticket) {
            send("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\",\"cols\":100,\"rows\":30}");
        }

        void input(String data) {
            send("{\"type\":\"input\",\"data\":\"" + data + "\"}");
        }

        /** The next text frame whose type is {@code type} (skipping others). */
        String await(String type) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                String t = texts.poll(200, TimeUnit.MILLISECONDS);
                if (t != null && t.contains("\"type\":\"" + type + "\"")) {
                    return t;
                }
            }
            throw new AssertionError("no '" + type + "' frame; frames: " + seen + " closed: " + closed.getNow(null) + " output: " + out());
        }

        String out() {
            synchronized (output) {
                return output.toString(StandardCharsets.UTF_8);
            }
        }

        void awaitOutput(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                if (out().contains(expected)) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("did not see '" + expected + "' in: " + out());
        }

        int awaitClose() throws Exception {
            return closed.get(40, TimeUnit.SECONDS);
        }
    }
}
