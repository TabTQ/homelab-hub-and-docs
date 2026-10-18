package com.homelabhub.internal.docker;

import com.homelabhub.auth.AuthProperties;
import com.homelabhub.internal.config.ConfigStore;
import com.homelabhub.internal.config.DashboardConfig.DockerHostSpec;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Whether the Docker page may be used at all. Fails closed like the Terminal: it needs HUB_DOCKER_ENABLED=true and a login
 * mode other than none, because the docker group on a host is as good as root there.
 */
@Component
@EnableConfigurationProperties(DockerProperties.class)
public class DockerFeature {

    private static final Logger log = LoggerFactory.getLogger(DockerFeature.class);

    private final DockerProperties props;
    private final ConfigStore store;
    private final boolean switchedOn;

    public DockerFeature(DockerProperties props, AuthProperties auth, ConfigStore store) {
        this.props = props;
        this.store = store;
        boolean on = props.enabled();
        if (on && auth.auth().mode() == AuthProperties.Mode.NONE) {
            log.error("HUB_DOCKER_ENABLED=true is ignored because HUB_AUTH_MODE=none: managing containers needs a login. The Docker page stays off.");
            on = false;
        }
        this.switchedOn = on;
        if (on) {
            log.info("Docker page enabled");
        }
    }

    public boolean enabled() {
        return switchedOn;
    }

    /** Enabled AND at least one host declared: what decides whether the nav shows the page. */
    public boolean available() {
        return switchedOn && !hosts().isEmpty();
    }

    public List<DockerHostSpec> hosts() {
        var cfg = store.state().config();
        return cfg == null ? List.of() : cfg.dockerHosts();
    }

    public Optional<DockerHostSpec> host(String id) {
        return hosts().stream().filter(h -> h.id().equals(id)).findFirst();
    }

    public DockerProperties props() {
        return props;
    }
}
