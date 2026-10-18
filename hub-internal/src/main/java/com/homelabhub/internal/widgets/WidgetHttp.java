package com.homelabhub.internal.widgets;

import com.homelabhub.internal.config.DashboardConfig.WidgetSpec;
import com.homelabhub.internal.proxmox.ProxmoxProperties.TlsMode;
import com.homelabhub.internal.proxmox.TlsTrust;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** HTTP for widgets: TLS mode per widget, short timeouts, no redirects, and a hard cap on how much we read. */
public final class WidgetHttp {

    static final int MAX_BODY = 1024 * 1024;

    public record Response(int status, String body, Map<String, List<String>> headers) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;

    public WidgetHttp(WidgetSpec spec, Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        TlsMode mode = switch (spec.tls().toLowerCase(Locale.ROOT)) {
            case "insecure" -> TlsMode.INSECURE;
            case "pinned" -> TlsMode.PINNED;
            default -> TlsMode.SYSTEM;
        };
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
        if (mode != TlsMode.SYSTEM) {
            b.sslContext(TlsTrust.contextFor(mode, spec.fingerprint()));
        }
        this.client = b.build();
    }

    public Response get(String url, Map<String, String> headers) {
        return send(builder(url, headers).GET().build());
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(builder(url, headers).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public Response postJson(String url, String json, Map<String, String> headers) {
        return send(builder(url, headers).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpRequest.Builder builder(String url, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).header("Accept", "application/json");
        headers.forEach(b::header);
        return b;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<InputStream> r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                byte[] bytes = in.readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    throw new WidgetException(WidgetException.Code.BAD_RESPONSE, "The service sent an unexpectedly large response");
                }
                return new Response(r.statusCode(), new String(bytes, StandardCharsets.UTF_8), r.headers().map());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WidgetException(WidgetException.Code.UNREACHABLE, "Interrupted");
        } catch (HttpTimeoutException e) {
            throw new WidgetException(WidgetException.Code.TIMEOUT, "The service did not answer in time", e);
        } catch (IOException e) {
            if (mismatch(e)) {
                throw new WidgetException(WidgetException.Code.CERT_MISMATCH, "The server certificate does not match the pinned fingerprint", e);
            }
            throw new WidgetException(WidgetException.Code.UNREACHABLE, "Could not reach the service", e);
        }
    }

    private static boolean mismatch(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.security.cert.CertificateException && c.getMessage() != null
                    && c.getMessage().startsWith(TlsTrust.MISMATCH_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /** 401/403 become UNAUTHORIZED, anything else outside 2xx BAD_RESPONSE. The upstream body is never copied into the error. */
    public static void requireOk(Response r, String what) {
        if (r.status() >= 200 && r.status() < 300) {
            return;
        }
        if (r.status() == 401 || r.status() == 403) {
            throw new WidgetException(WidgetException.Code.UNAUTHORIZED, what + ": the credentials were refused (HTTP " + r.status() + ")");
        }
        throw new WidgetException(WidgetException.Code.BAD_RESPONSE, what + " answered HTTP " + r.status());
    }
}
