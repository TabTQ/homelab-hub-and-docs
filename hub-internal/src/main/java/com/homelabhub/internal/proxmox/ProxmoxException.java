package com.homelabhub.internal.proxmox;

/**
 * Any failure talking to Proxmox. The message text deliberately matches the legacy wol-server messages,
 * because legacy callers see them (plan 9.5). {@link #kind()} lets the new UI react precisely.
 */
public class ProxmoxException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED, UNREACHABLE, CERT_MISMATCH, UNAUTHORIZED, HTTP_ERROR, INVALID_STATE, BAD_RESPONSE
    }

    private final Kind kind;

    public ProxmoxException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProxmoxException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
