package com.homelabhub.internal.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only record of power actions (plan 6.2). A failing audit write must never block or fail the action. */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    private static final int MAX_FIELD = 500;

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public enum Source { ui, legacy }

    public enum Result { ok, failed, denied, timeout }

    public void record(Source source, String actionId, String target, Result result, String detail, String clientIp, String userAgent) {
        try {
            jdbc.sql("INSERT INTO audit_log (source, action_id, target, result, detail, client_ip, user_agent) VALUES (?,?,?,?,?,?,?)")
                    .params(source.name(), clip(actionId), clip(target), result.name(), clip(detail), clip(clientIp), clip(userAgent))
                    .update();
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {} {} ({}): {}", source, actionId, target, e.getMessage());
        }
        log.info("audit source={} action={} target={} result={} ip={}", source, actionId, target, result, clientIp);
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > MAX_FIELD ? flat.substring(0, MAX_FIELD) + "..." : flat;
    }
}
