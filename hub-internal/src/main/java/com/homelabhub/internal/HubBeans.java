package com.homelabhub.internal;

import com.homelabhub.internal.legacy.LegacyProperties;
import com.homelabhub.internal.proxmox.HttpProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxClient;
import com.homelabhub.internal.proxmox.ProxmoxProperties;
import com.homelabhub.internal.widgets.HostMetrics;
import com.homelabhub.internal.wol.WakeOnLanSender;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ProxmoxProperties.class, LegacyProperties.class})
class HubBeans {

    private static final Logger log = LoggerFactory.getLogger(HubBeans.class);

    @Bean
    ProxmoxClient proxmoxClient(ProxmoxProperties props) {
        if (!props.configured()) {
            log.warn("Proxmox is not configured (PROXMOX_API_URL / PROXMOX_API_TOKEN): power actions will report NOT_CONFIGURED.");
        } else if (props.tlsMode() == ProxmoxProperties.TlsMode.INSECURE) {
            log.warn("PROXMOX_TLS_MODE=insecure: the Proxmox certificate is NOT verified. Pin it with PROXMOX_TLS_FINGERPRINT.");
        }
        return new HttpProxmoxClient(props);
    }

    @Bean
    WakeOnLanSender wakeOnLanSender(@Value("${hub.wol.target:255.255.255.255}") String target,
                                    @Value("${hub.wol.port:9}") int port,
                                    @Value("${hub.wol.repeat:3}") int repeat,
                                    @Value("${hub.wol.delay-millis:100}") long delayMillis) throws UnknownHostException {
        return new WakeOnLanSender(InetAddress.getByName(target), port, repeat, delayMillis);
    }

    /** In Docker, mount the host's /proc and /sys read-only (e.g. /host/proc) and point these at them. */
    @Bean
    HostMetrics hostMetrics(@Value("${hub.host.proc:/proc}") String proc, @Value("${hub.host.sys:/sys}") String sys,
                            @Value("${hub.host.root:}") String root, java.time.Clock clock) {
        return new HostMetrics(java.nio.file.Path.of(proc), java.nio.file.Path.of(sys),
                root == null || root.isBlank() ? null : java.nio.file.Path.of(root), clock, ms -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }
}
