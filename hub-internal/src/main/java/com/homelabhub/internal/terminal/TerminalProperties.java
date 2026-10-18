package com.homelabhub.internal.terminal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** hub.terminal.* (application.yml). Every value is overridable through HUB_TERMINAL_* environment variables. */
@ConfigurationProperties(prefix = "hub.terminal")
public record TerminalProperties(boolean enabled, int idleSeconds, int warnSeconds, int maxSeconds, int maxSessions,
                                 int connectTimeoutSeconds, int maxMessageBytes, int maxQueuedOutputBytes) {

    public TerminalProperties {
        idleSeconds = idleSeconds <= 0 ? 900 : idleSeconds;
        warnSeconds = warnSeconds <= 0 || warnSeconds >= idleSeconds ? Math.min(60, idleSeconds / 2) : warnSeconds;
        maxSeconds = maxSeconds <= 0 ? 7200 : maxSeconds;
        maxSessions = maxSessions <= 0 ? 2 : Math.min(maxSessions, 20);
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 10 : connectTimeoutSeconds;
        maxMessageBytes = maxMessageBytes <= 0 ? 65536 : maxMessageBytes;
        maxQueuedOutputBytes = maxQueuedOutputBytes <= 0 ? 524288 : maxQueuedOutputBytes;
    }
}
