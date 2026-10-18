package com.homelabhub.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A browser stand-in for the Spring tests: real HTTP, a cookie jar, and the CSRF header taken from the XSRF-TOKEN cookie. */
final class DocsClient {

    static final ObjectMapper M = new ObjectMapper();

    final int port;
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient http;
    boolean csrf = true;

    DocsClient(int port) {
        this.port = port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    static Path tempDir() {
        try {
            return Files.createTempDirectory("docs-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void useTemp(org.springframework.test.context.DynamicPropertyRegistry r, Path dir) {
        r.add("docs.data-dir", () -> dir.resolve("data").toString());
        r.add("docs.snapshot-dir", () -> dir.resolve("snapshot").toString());
        r.add("docs.portfolio.snapshot-dir", () -> dir.resolve("portfolio-snapshot").toString());
        r.add("spring.datasource.url", () -> {
            try {
                Files.createDirectories(dir.resolve("data"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return "jdbc:sqlite:" + dir.resolve("data/docs.db") + "?foreign_keys=on&journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL";
        });
    }

    String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name)).map(java.net.HttpCookie::getValue).findFirst().orElse(null);
    }

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        addCsrf(b);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : M.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> getBytes(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private void addCsrf(HttpRequest.Builder b) {
        String t = cookie("XSRF-TOKEN");
        if (csrf && t != null) {
            b.header("X-XSRF-TOKEN", t);
        }
    }

    JsonNode json(String method, String path, Object body, int expect) throws Exception {
        HttpResponse<String> r = call(method, path, body);
        if (r.statusCode() != expect) {
            throw new AssertionError(method + " " + path + " → " + r.statusCode() + " (expected " + expect + "): " + r.body());
        }
        return r.body().isEmpty() ? M.nullNode() : M.readTree(r.body());
    }

    DocsClient login(String password) throws Exception {
        call("GET", "/api/auth/status", null);
        HttpResponse<String> r = call("POST", "/api/auth/login", "{\"password\":" + M.writeValueAsString(password) + "}");
        if (r.statusCode() != 200) {
            throw new AssertionError("login failed: " + r.statusCode() + " " + r.body());
        }
        return this;
    }

    HttpResponse<String> upload(String path, String fileName, byte[] content, String... fields) throws Exception {
        String boundary = "----docs" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fields[i] + "\"\r\n\r\n" + fields[i + 1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
        addCsrf(b);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** java.net.http refuses to set Host, so a foreign Host goes over a plain socket. */
    String raw(String request) throws Exception {
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
            s.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String para(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}]}]}";
    }

    static String quote(String s) {
        try {
            return M.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
}
