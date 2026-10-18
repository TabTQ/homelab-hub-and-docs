package com.homelabhub.docs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only record of logins, publishes, unpublishes, deletes and hostname changes. A failing write never fails the action. */
@Service
public class DocsAudit {

    private static final Logger log = LoggerFactory.getLogger(DocsAudit.class);
    private static final int MAX_FIELD = 500;

    private final JdbcClient jdbc;

    public DocsAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String target, String result, String detail, String clientIp, String userAgent) {
        try {
            jdbc.sql("INSERT INTO audit_log (source, action_id, target, result, detail, client_ip, user_agent) VALUES ('ui',?,?,?,?,?,?)")
                    .params(clip(action), clip(target), result, clip(detail), clip(clientIp), clip(userAgent)).update();
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} {}: {}", action, target, e.getMessage());
        }
        log.info("audit action={} target={} result={} ip={}", action, target, result, clientIp);
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > MAX_FIELD ? flat.substring(0, MAX_FIELD) + "..." : flat;
    }
}
