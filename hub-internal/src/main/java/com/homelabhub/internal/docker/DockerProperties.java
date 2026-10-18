package com.homelabhub.internal.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** hub.docker.* (application.yml). Every value is overridable through HUB_DOCKER_* environment variables. */
@ConfigurationProperties(prefix = "hub.docker")
public record DockerProperties(boolean enabled, int commandTimeoutSeconds, int jobTimeoutSeconds, int logFollowMaxSeconds,
                               int maxLogLines, int idleSessionSeconds) {

    public DockerProperties {
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 20 : commandTimeoutSeconds;
        jobTimeoutSeconds = jobTimeoutSeconds <= 0 ? 900 : jobTimeoutSeconds;
        logFollowMaxSeconds = logFollowMaxSeconds <= 0 ? 1800 : logFollowMaxSeconds;
        maxLogLines = maxLogLines <= 0 ? 5000 : Math.min(maxLogLines, 20000);
        idleSessionSeconds = idleSessionSeconds <= 0 ? 60 : idleSessionSeconds;
    }
}
