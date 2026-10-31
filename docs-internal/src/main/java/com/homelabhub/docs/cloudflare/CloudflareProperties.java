package com.homelabhub.docs.cloudflare;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * docs.cloudflare.* settings. Off by default: with {@code enabled=false} nothing here talks to Cloudflare. The API token needs only
 * Account > Cloudflare Tunnel: Edit and Zone > DNS: Edit, restricted to the one account and zone.
 */
@ConfigurationProperties(prefix = "docs.cloudflare")
public record CloudflareProperties(Boolean enabled, Boolean dryRun, String apiToken, String baseUrl, String accountId, String zoneId,
                                   String tunnelId, String zoneName, String service, String reservedLabels) {

    public static final String DEFAULT_BASE_URL = "https://api.cloudflare.com/client/v4";
    public static final String DEFAULT_SERVICE = "http://docs-public:3011";
    /** Written to the comment of every DNS record the app creates; only records carrying it are ever deleted. */
    public static final String MARKER = "managed-by=homelab-docs";

    public CloudflareProperties {
        enabled = enabled != null && enabled;
        dryRun = dryRun != null && dryRun;
        apiToken = blank(apiToken) ? "" : apiToken.strip();
        baseUrl = blank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.strip().replaceAll("/+$", "");
        accountId = blank(accountId) ? "" : accountId.strip();
        zoneId = blank(zoneId) ? "" : zoneId.strip();
        tunnelId = blank(tunnelId) ? "" : tunnelId.strip();
        zoneName = blank(zoneName) ? "" : zoneName.strip().toLowerCase(Locale.ROOT);
        service = blank(service) ? DEFAULT_SERVICE : service.strip();
        reservedLabels = reservedLabels == null ? "" : reservedLabels;
    }

    /** @return what is missing for the automation to run, or null when it is fully configured */
    public String configError() {
        if (apiToken.isEmpty()) return "DOCS_CF_API_TOKEN is not set";
        if (accountId.isEmpty() || zoneId.isEmpty() || tunnelId.isEmpty()) return "DOCS_CF_ACCOUNT_ID, DOCS_CF_ZONE_ID and DOCS_CF_TUNNEL_ID must be set";
        if (zoneName.isEmpty()) return "DOCS_CF_ZONE_NAME must be set";
        return null;
    }

    public List<String> extraReserved() {
        return Arrays.stream(reservedLabels.split(",")).map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    public String cnameTarget() {
        return tunnelId + ".cfargotunnel.com";
    }

    /** Never prints the token. */
    @Override
    public String toString() {
        return "CloudflareProperties[enabled=" + enabled + ", dryRun=" + dryRun + ", apiToken=" + (apiToken.isEmpty() ? "<unset>" : "<masked>")
                + ", zoneName=" + zoneName + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
