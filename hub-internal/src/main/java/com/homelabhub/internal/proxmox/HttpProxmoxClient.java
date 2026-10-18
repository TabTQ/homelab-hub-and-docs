package com.homelabhub.internal.proxmox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Proxmox API client on the JDK HttpClient. Error message wording intentionally matches the legacy wol-server
 * ("Failed to get status for LXC100 on node pve. HTTP 500: ..."), because the legacy routes expose it.
 */
public class HttpProxmoxClient implements ProxmoxClient {

    /** Proxmox node names are host names. Anything else would let a caller steer the request path. */
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,62}");

    private final ProxmoxProperties props;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public HttpProxmoxClient(ProxmoxProperties props) {
        this.props = props;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (props.configured()) {
            // Fails fast at startup on a malformed or missing fingerprint, instead of at the first power action.
            builder.sslContext(TlsTrust.contextFor(props.tlsMode(), props.tlsFingerprint()));
        }
        this.http = builder.build();
    }

    @Override
    public GuestStatus guestStatus(String node, GuestType type, int id) {
        String label = label(type, id);
        String url = base(node) + "/" + type.apiName() + "/" + id + "/status/current";
        HttpResponse<String> r = send(get(url),
                e -> "Error communicating with Proxmox API for " + label + " on node " + node + ": " + e.getMessage());
        if (r.statusCode() != 200) {
            throw httpError(r, "Failed to get status for " + label + " on node " + node + ". HTTP " + r.statusCode() + ": " + r.body());
        }
        try {
            return GuestStatus.fromString(json.readTree(r.body()).path("data").path("status").asText(null));
        } catch (IOException e) {
            throw new ProxmoxException(ProxmoxException.Kind.BAD_RESPONSE, "Unreadable status response for " + label + " on node " + node, e);
        }
    }

    @Override
    public String guestAction(String node, GuestType type, int id, String action) {
        if (!action.equals("start") && !action.equals("stop") && !action.equals("shutdown")) {
            throw new IllegalArgumentException("Unsupported guest action: " + action);
        }
        String label = label(type, id);
        String url = base(node) + "/" + type.apiName() + "/" + id + "/status/" + action;
        HttpResponse<String> r = send(post(url, null),
                e -> "Error " + action + " " + label + " on node " + node + ": " + e.getMessage());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw httpError(r, "Failed to " + action + " " + label + " on node " + node + ". HTTP " + r.statusCode() + ": " + r.body());
        }
        return upid(r.body());
    }

    @Override
    public void nodeCommand(String node, String command) {
        if (!command.equals("shutdown") && !command.equals("reboot")) {
            throw new IllegalArgumentException("Unsupported node command: " + command);
        }
        String url = base(node) + "/status";
        HttpResponse<String> r = send(post(url, "command=" + URLEncoder.encode(command, StandardCharsets.UTF_8)),
                e -> "Error sending " + command + " command to node " + node + ": " + e.getMessage());
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw httpError(r, "Failed to " + command + " node " + node + ". HTTP " + r.statusCode() + ": " + r.body());
        }
    }

    // ---- internals ----

    private static String label(GuestType type, int id) {
        return type.apiName().toUpperCase(Locale.ROOT) + id;
    }

    private String base(String node) {
        if (!props.configured()) {
            throw new ProxmoxException(ProxmoxException.Kind.NOT_CONFIGURED,
                    "Proxmox is not configured. Set PROXMOX_API_URL and PROXMOX_API_TOKEN.");
        }
        if (node == null || !NODE.matcher(node).matches()) {
            throw new IllegalArgumentException("Invalid node name: " + node);
        }
        return props.url() + "/nodes/" + node;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("Authorization", "PVEAPIToken=" + props.token());
    }

    private HttpRequest get(String url) {
        return request(url).header("Accept", "application/json").GET().build();
    }

    private HttpRequest post(String url, String formBody) {
        HttpRequest.Builder b = request(url);
        if (formBody == null) {
            return b.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
    }

    private HttpResponse<String> send(HttpRequest request, Function<Exception, String> ioMessage) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxmoxException(ProxmoxException.Kind.UNREACHABLE, ioMessage.apply(e), e);
        } catch (IOException e) {
            ProxmoxException.Kind kind = mismatch(e) ? ProxmoxException.Kind.CERT_MISMATCH : ProxmoxException.Kind.UNREACHABLE;
            String msg = kind == ProxmoxException.Kind.CERT_MISMATCH ? ioMessage.apply(rootCause(e)) : ioMessage.apply(e);
            throw new ProxmoxException(kind, msg, e);
        }
    }

    private static ProxmoxException httpError(HttpResponse<String> r, String message) {
        boolean auth = r.statusCode() == 401 || r.statusCode() == 403;
        return new ProxmoxException(auth ? ProxmoxException.Kind.UNAUTHORIZED : ProxmoxException.Kind.HTTP_ERROR, message);
    }

    private String upid(String body) {
        try {
            JsonNode data = json.readTree(body).path("data");
            return data.isTextual() ? data.asText() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean mismatch(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.security.cert.CertificateException && c.getMessage() != null
                    && c.getMessage().startsWith(TlsTrust.MISMATCH_PREFIX)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static Exception rootCause(Exception e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            if (c instanceof java.security.cert.CertificateException) {
                break;
            }
            c = c.getCause();
        }
        return c instanceof Exception ex ? ex : e;
    }

    // ---- read-only additions used by the UI actions ----

    private static final Pattern UPID = Pattern.compile("UPID:[A-Za-z0-9.-]{1,63}:[0-9A-Fa-f]{1,16}:[0-9A-Fa-f]{1,16}:[0-9A-Fa-f]{1,16}:[a-z0-9_-]{1,32}:[A-Za-z0-9._-]*:[A-Za-z0-9@!._=-]{1,120}:");

    @Override
    public java.util.List<GuestInfo> listGuests(String node) {
        java.util.List<GuestInfo> out = new java.util.ArrayList<>();
        for (GuestType type : GuestType.values()) {
            String url = base(node) + "/" + type.apiName();
            HttpResponse<String> r = send(get(url), e -> "Error communicating with Proxmox API for the " + type.apiName() + " list on node " + node + ": " + e.getMessage());
            if (r.statusCode() != 200) {
                throw httpError(r, "Failed to list " + type.apiName() + " guests on node " + node + ". HTTP " + r.statusCode());
            }
            try {
                for (JsonNode g : json.readTree(r.body()).path("data")) {
                    out.add(new GuestInfo(g.path("vmid").asInt(), g.path("name").asText(""), type, GuestStatus.fromString(g.path("status").asText(null))));
                }
            } catch (IOException e) {
                throw new ProxmoxException(ProxmoxException.Kind.BAD_RESPONSE, "Unreadable guest list on node " + node, e);
            }
        }
        out.sort(java.util.Comparator.comparingInt(GuestInfo::vmid));
        return out;
    }

    @Override
    public TaskStatus taskStatus(String node, String upid) {
        if (upid == null || !UPID.matcher(upid).matches()) {
            throw new IllegalArgumentException("Invalid task id");
        }
        String url = base(node) + "/tasks/" + URLEncoder.encode(upid, StandardCharsets.UTF_8).replace("+", "%20") + "/status";
        HttpResponse<String> r = send(get(url), e -> "Error reading task status on node " + node + ": " + e.getMessage());
        if (r.statusCode() != 200) {
            throw httpError(r, "Failed to read task status on node " + node + ". HTTP " + r.statusCode());
        }
        try {
            JsonNode d = json.readTree(r.body()).path("data");
            return new TaskStatus("running".equals(d.path("status").asText()), d.path("exitstatus").asText(null));
        } catch (IOException e) {
            throw new ProxmoxException(ProxmoxException.Kind.BAD_RESPONSE, "Unreadable task status on node " + node, e);
        }
    }
}
