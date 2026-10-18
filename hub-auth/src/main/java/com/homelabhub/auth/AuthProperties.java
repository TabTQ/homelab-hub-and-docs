package com.homelabhub.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** hub.auth.* and hub.security.* settings. The password hash comes from the environment and is never logged. */
@ConfigurationProperties(prefix = "hub")
public record AuthProperties(Auth auth, Security security) {

    public enum Mode { ALL, EDIT, NONE }

    public record Auth(Mode mode, String passwordHash) {
        public Auth {
            mode = mode == null ? Mode.ALL : mode;
            passwordHash = passwordHash == null ? "" : passwordHash.strip();
        }

        public boolean passwordConfigured() {
            return !passwordHash.isEmpty();
        }
    }

    public record Security(List<String> allowedHosts) {
        public Security {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        }
    }

    public AuthProperties {
        auth = auth == null ? new Auth(null, null) : auth;
        security = security == null ? new Security(null) : security;
    }
}
