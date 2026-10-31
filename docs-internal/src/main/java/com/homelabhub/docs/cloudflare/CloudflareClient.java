package com.homelabhub.docs.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The few Cloudflare API calls the route automation needs (tunnel configuration and DNS records), over plain
 * {@code java.net.http}. Errors carry Cloudflare's own message and status, never the token or the request headers.
 */
public class CloudflareClient {

    public record DnsRecord(String id, String name, String type, String content, boolean proxied, String comment) {}

    public static class ApiException extends RuntimeException {
        private final int status;
        private final int code;

        ApiException(int status, int code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        /** Cloudflare answers 409, or 400 with code 81053/81057/81058, when the record is already there. */
        public boolean alreadyExists() {
            return status == 409 || code == 81053 || code == 81057 || code == 81058;
        }

        public boolean notFound() {
            return status == 404;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PAGES = 20;

    private final CloudflareProperties p;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public CloudflareClient(CloudflareProperties props) {
        this.p = props;
    }

    // ---------------------------------------------------------------- tunnel configuration

    /** @return the tunnel's {@code config} object (ingress rules and settings) */
    public JsonNode getTunnelConfig() {
        JsonNode res = call("GET", tunnelPath(), null).path("result");
        JsonNode config = res.path("config");
        if (!config.isObject()) {
            throw new ApiException(200, 0, "Cloudflare returned no tunnel configuration (is the tunnel managed remotely?).");
        }
        return config;
    }

    /** Replaces the whole configuration: callers must send back everything they read. */
    public void putTunnelConfig(JsonNode config) {
        ObjectNode body = JSON.createObjectNode();
        body.set("config", config);
        call("PUT", tunnelPath(), body);
    }

    private String tunnelPath() {
        return "/accounts/" + p.accountId() + "/cfd_tunnel/" + p.tunnelId() + "/configurations";
    }

    // ---------------------------------------------------------------- DNS

    /** Every record of the zone, or only those with this exact name. */
    public List<DnsRecord> listDns(String name) {
        List<DnsRecord> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String q = "?per_page=100&page=" + page + (name == null ? "" : "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            JsonNode root = call("GET", "/zones/" + p.zoneId() + "/dns_records" + q, null);
            for (JsonNode r : root.path("result")) {
                out.add(new DnsRecord(r.path("id").asText(), r.path("name").asText().toLowerCase(java.util.Locale.ROOT), r.path("type").asText(),
                        r.path("content").asText(), r.path("proxied").asBoolean(false), r.path("comment").asText("")));
            }
            if (page >= root.path("result_info").path("total_pages").asInt(1)) {
                break;
            }
        }
        return out;
    }

    /** Creates the proxied CNAME {@code host -> <tunnel>.cfargotunnel.com}, marked as managed by this app. */
    public DnsRecord createCname(String host) {
        ObjectNode body = JSON.createObjectNode().put("type", "CNAME").put("name", host).put("content", p.cnameTarget())
                .put("proxied", true).put("ttl", 1).put("comment", CloudflareProperties.MARKER);
        JsonNode r = call("POST", "/zones/" + p.zoneId() + "/dns_records", body).path("result");
        return new DnsRecord(r.path("id").asText(), host, "CNAME", p.cnameTarget(), true, CloudflareProperties.MARKER);
    }

    /** @return false when the record was already gone */
    public boolean deleteDns(String id) {
        try {
            call("DELETE", "/zones/" + p.zoneId() + "/dns_records/" + URLEncoder.encode(id, StandardCharsets.UTF_8), null);
            return true;
        } catch (ApiException e) {
            if (e.notFound()) {
                return false;
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- transport

    private JsonNode call(String method, String path, JsonNode body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(p.baseUrl() + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + p.apiToken()).header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, 0, "Cloudflare request interrupted.");
        } catch (IOException e) {
            throw new ApiException(0, 0, "Cloudflare API not reachable (" + e.getClass().getSimpleName() + ").");
        }
        JsonNode root;
        try {
            root = JSON.readTree(res.body());
        } catch (IOException | RuntimeException e) {
            root = null;
        }
        boolean ok = res.statusCode() / 100 == 2 && root != null && root.path("success").asBoolean(false);
        if (!ok) {
            JsonNode err = root == null ? null : root.path("errors").path(0);
            int code = err == null ? 0 : err.path("code").asInt(0);
            String msg = err == null || err.path("message").isMissingNode() ? "no details" : clip(err.path("message").asText());
            throw new ApiException(res.statusCode(), code, "Cloudflare API " + res.statusCode() + ": " + msg);
        }
        return root;
    }

    private static String clip(String s) {
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
    }
}
