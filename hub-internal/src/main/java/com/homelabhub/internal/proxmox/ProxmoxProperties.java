package com.homelabhub.internal.proxmox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hub.proxmox.*  (env: PROXMOX_API_URL, PROXMOX_API_TOKEN, PROXMOX_TLS_MODE, PROXMOX_TLS_FINGERPRINT).
 * The token comes from the environment or a secret file only, and is never logged.
 */
@ConfigurationProperties(prefix = "hub.proxmox")
public record ProxmoxProperties(
        String url,
        String token,
        TlsMode tlsMode,
        String tlsFingerprint,
        Duration connectTimeout,
        Duration requestTimeout) {

    public enum TlsMode {
        /** Accept only the server certificate whose SHA-256 fingerprint is configured. Default. */
        PINNED,
        /** Normal JVM trust store validation. */
        SYSTEM,
        /** Trust anything. Explicit opt-in, logged loudly. This is what wol-server does today. */
        INSECURE
    }

    public ProxmoxProperties {
        url = url == null ? "" : url.strip().replaceAll("/+$", "");
        token = token == null ? "" : token.strip();
        tlsMode = tlsMode == null ? TlsMode.PINNED : tlsMode;
        tlsFingerprint = tlsFingerprint == null ? "" : tlsFingerprint.strip();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }

    public boolean configured() {
        return !url.isEmpty() && !token.isEmpty();
    }
}
