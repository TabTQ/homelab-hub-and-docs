package com.homelabhub.docs.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** An in-memory Cloudflare API (tunnel configuration + one DNS zone) on a local port. No real API call is ever made in tests. */
final class FakeCloudflare implements AutoCloseable {

    static final String ACCOUNT = "acct1", ZONE = "zone1", TUNNEL = "tun1", TOKEN = "test-token-123", ZONE_NAME = "example.test";
    static final ObjectMapper M = new ObjectMapper();

    private final HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final AtomicInteger ids = new AtomicInteger(100);
    volatile ObjectNode config;
    final List<ObjectNode> dns = new CopyOnWriteArrayList<>();
    /** When set, requests whose "METHOD path" starts with this prefix fail with status/body. */
    volatile String failPrefix;
    volatile int failStatus = 500;
    volatile String failBody = "{\"success\":false,\"errors\":[{\"code\":1000,\"message\":\"boom\"}]}";
    volatile long delayMillis = 0;

    FakeCloudflare() throws IOException {
        config = (ObjectNode) parse("""
                {"ingress":[
                  {"hostname":"portfolio.example.test","service":"http://hub-public:3011"},
                  {"hostname":"*.example.test","service":"http://docs-public:3011"},
                  {"service":"http_status:404"}],
                 "warp-routing":{"enabled":false}}""");
        dns.add(record("1", "CNAME", "portfolio.example.test", TUNNEL + ".cfargotunnel.com", ""));
        dns.add(record("2", "CNAME", "*.example.test", TUNNEL + ".cfargotunnel.com", ""));
        dns.add(record("3", "A", "example.test", "192.0.2.1", ""));
        dns.add(record("4", "CNAME", "www.example.test", "example.test", ""));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client/v4", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4";
    }

    CloudflareProperties props(boolean dryRun) {
        return new CloudflareProperties(true, dryRun, TOKEN, baseUrl(), ACCOUNT, ZONE, TUNNEL, ZONE_NAME, null, "portfolio,other-app");
    }

    static ObjectNode record(String id, String type, String name, String content, String comment) {
        return (ObjectNode) M.createObjectNode().put("id", id).put("type", type).put("name", name).put("content", content)
                .put("proxied", true).put("comment", comment);
    }

    static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> mutations() {
        return requests.stream().filter(r -> !r.startsWith("GET ")).toList();
    }

    List<String> hostnames() {
        List<String> out = new ArrayList<>();
        config.path("ingress").forEach(r -> out.add(r.path("hostname").asText("")));
        return out;
    }

    ObjectNode dnsNamed(String name) {
        return dns.stream().filter(r -> r.path("name").asText().equals(name)).findFirst().orElse(null);
    }

    private void handle(HttpExchange ex) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getRawPath().substring("/client/v4".length());
            String query = ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(method + " " + path + (body.isEmpty() ? "" : " " + body));
            if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                send(ex, 403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");
                return;
            }
            String fp = failPrefix;
            if (fp != null && (method + " " + path).startsWith(fp)) {
                send(ex, failStatus, failBody);
                return;
            }
            route(ex, method, path, query, body);
        } catch (Exception e) {
            send(ex, 500, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"fake server error\"}]}");
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void route(HttpExchange ex, String method, String path, String query, String body) throws IOException {
        String cfg = "/accounts/" + ACCOUNT + "/cfd_tunnel/" + TUNNEL + "/configurations";
        String recs = "/zones/" + ZONE + "/dns_records";
        if (path.equals(cfg) && method.equals("GET")) {
            ObjectNode res = M.createObjectNode().put("tunnel_id", TUNNEL);
            res.set("config", config.deepCopy());
            send(ex, 200, M.createObjectNode().put("success", true).set("result", res).toString());
        } else if (path.equals(cfg) && method.equals("PUT")) {
            config = (ObjectNode) parse(body).path("config").deepCopy();
            send(ex, 200, "{\"success\":true,\"result\":{}}");
        } else if (path.equals(recs) && method.equals("GET")) {
            String name = null;
            if (query != null && query.contains("name=")) {
                name = java.net.URLDecoder.decode(query.replaceAll(".*name=([^&]*).*", "$1"), StandardCharsets.UTF_8);
            }
            ArrayNode result = M.createArrayNode();
            for (ObjectNode r : dns) {
                if (name == null || r.path("name").asText().equals(name)) result.add(r);
            }
            ObjectNode root = M.createObjectNode().put("success", true);
            root.set("result", result);
            root.putObject("result_info").put("total_pages", 1);
            send(ex, 200, root.toString());
        } else if (path.equals(recs) && method.equals("POST")) {
            JsonNode in = parse(body);
            if (dnsNamed(in.path("name").asText()) != null) {
                send(ex, 400, "{\"success\":false,\"errors\":[{\"code\":81058,\"message\":\"An identical record already exists.\"}]}");
                return;
            }
            ObjectNode r = record(String.valueOf(ids.incrementAndGet()), in.path("type").asText(), in.path("name").asText(), in.path("content").asText(),
                    in.path("comment").asText());
            r.put("proxied", in.path("proxied").asBoolean());
            dns.add(r);
            send(ex, 200, M.createObjectNode().put("success", true).set("result", r).toString());
        } else if (path.startsWith(recs + "/") && method.equals("DELETE")) {
            String id = path.substring(recs.length() + 1);
            boolean removed = dns.removeIf(r -> r.path("id").asText().equals(id));
            send(ex, removed ? 200 : 404, removed ? "{\"success\":true,\"result\":{\"id\":\"" + id + "\"}}"
                    : "{\"success\":false,\"errors\":[{\"code\":81044,\"message\":\"Record does not exist.\"}]}");
        } else {
            send(ex, 404, "{\"success\":false,\"errors\":[{\"code\":7003,\"message\":\"No route\"}]}");
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
