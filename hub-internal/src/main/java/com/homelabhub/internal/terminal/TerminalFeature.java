package com.homelabhub.internal.terminal;

import com.homelabhub.auth.AuthProperties;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig.TerminalSpec;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Whether the Terminal page may be used at all. Fails closed: it needs HUB_TERMINAL_ENABLED=true, a login mode other than
 * none (a shell without a password is never acceptable) and a configured password for the step-up check.
 */
@Component
@EnableConfigurationProperties(TerminalProperties.class)
public class TerminalFeature {

    private static final Logger log = LoggerFactory.getLogger(TerminalFeature.class);

    private final TerminalProperties props;
    private final ConfigStore store;
    private final boolean switchedOn;

    public TerminalFeature(TerminalProperties props, AuthProperties auth, ConfigStore store) {
        this.props = props;
        this.store = store;
        boolean on = props.enabled();
        if (on && auth.auth().mode() == AuthProperties.Mode.NONE) {
            log.error("HUB_TERMINAL_ENABLED=true is ignored because HUB_AUTH_MODE=none: the terminal needs a login. The Terminal page stays off.");
            on = false;
        } else if (on && !auth.auth().passwordConfigured()) {
            log.error("HUB_TERMINAL_ENABLED=true is ignored because no dashboard password is set (HUB_PASSWORD_HASH). The Terminal page stays off.");
            on = false;
        }
        this.switchedOn = on;
        if (on) {
            log.info("Terminal page enabled: idle {} s, max {} s, at most {} sessions", props.idleSeconds(), props.maxSeconds(), props.maxSessions());
        }
    }

    /** Switched on and allowed (independent of the config file). */
    public boolean enabled() {
        return switchedOn;
    }

    /** Enabled AND at least one terminal declared: what decides whether the nav shows the page. */
    public boolean available() {
        return switchedOn && !terminals().isEmpty();
    }

    public List<TerminalSpec> terminals() {
        var cfg = store.state().config();
        return cfg == null ? List.of() : cfg.terminals();
    }

    public Optional<TerminalSpec> terminal(String id) {
        return terminals().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    public TerminalProperties props() {
        return props;
    }
}
