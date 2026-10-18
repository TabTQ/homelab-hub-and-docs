package com.homelabhub.internal.legacy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * hub.legacy.*  Controls the wol-server compatibility routes (plan 9.5, decision D15).
 * Defaults keep everything on, as chosen.
 */
@ConfigurationProperties(prefix = "hub.legacy")
public record LegacyProperties(Boolean enabled, Boolean blockCrossSite) {
    public LegacyProperties {
        enabled = enabled == null || enabled;
        blockCrossSite = blockCrossSite == null || blockCrossSite;
    }
}
