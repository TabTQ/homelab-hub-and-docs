package com.homelabhub.internal.status;

import com.homelabhub.internal.config.DashboardConfig.StatusCheck;
import com.homelabhub.internal.proxmox.ProxmoxProperties.TlsMode;
import com.homelabhub.internal.proxmox.TlsTrust;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import javax.net.ssl.SSLException;

/**
 * GET the URL and look only at the status code. No body is read and nothing secret is ever sent, which is why
 * self-signed certificates are accepted by default (a status light does not need certificate validation).
 */
public class HttpChecker implements Checker {

    private final HttpClient insecure;
    private final HttpClient system;
    private final Duration requestTimeout;

    public HttpChecker(Duration connectTimeout, Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        this.insecure = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(TlsTrust.contextFor(TlsMode.INSECURE, null)).build();
        this.system = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public CheckResult run(StatusCheck check) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(check.target())).timeout(requestTimeout)
                    .header("User-Agent", "homelab-hub-status/1").GET().build();
            HttpResponse<Void> r = (check.insecureTls() ? insecure : system).send(req, HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int code = r.statusCode();
            if (code >= check.expectFrom() && code <= check.expectTo()) {
                return CheckResult.up(ms);
            }
            return CheckResult.degraded(ms, "HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.unknown("interrupted");
        } catch (HttpTimeoutException e) {
            return CheckResult.down("timed out");
        } catch (SSLException e) {
            return CheckResult.down("TLS error");
        } catch (IOException e) {
            return CheckResult.down(e instanceof java.net.ConnectException ? "connection refused or unreachable" : e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return CheckResult.unknown("bad URL");
        }
    }
}
