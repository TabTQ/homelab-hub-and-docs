package com.homelabhub.docs;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** docs.* settings (plan 5.9). */
@ConfigurationProperties(prefix = "docs")
public record DocsProperties(String dataDir, String snapshotDir, String hubUrl, String domains, Integer maxAssetMb,
                             Integer maxBundleMb, Long publishRetryMillis, Integer gcDelaySeconds) {

    public DocsProperties {
        dataDir = blank(dataDir) ? "./data" : dataDir;
        snapshotDir = blank(snapshotDir) ? "./snapshot" : snapshotDir;
        hubUrl = hubUrl == null ? "" : hubUrl.strip();
        domains = domains == null ? "" : domains;
        maxAssetMb = maxAssetMb == null || maxAssetMb <= 0 ? 20 : maxAssetMb;
        maxBundleMb = maxBundleMb == null || maxBundleMb <= 0 ? 25 : maxBundleMb;
        publishRetryMillis = publishRetryMillis == null ? 30000L : publishRetryMillis;
        gcDelaySeconds = gcDelaySeconds == null ? 60 : gcDelaySeconds;
    }

    public List<String> domainList() {
        return Arrays.stream(domains.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** The ← Hub link, only when it is a plain http(s) URL. */
    public String safeHubUrl() {
        String lower = hubUrl.toLowerCase(java.util.Locale.ROOT);
        return (lower.startsWith("https://") || lower.startsWith("http://")) && !hubUrl.contains("@") ? hubUrl : "";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
