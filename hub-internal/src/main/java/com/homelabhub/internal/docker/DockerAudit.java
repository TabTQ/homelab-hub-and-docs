package com.homelabhub.internal.docker;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Docker page actions (never logs or file contents). Keeps the last {@value #KEEP} rows; a failing write never fails the action. */
@Service
class DockerAudit {

    private static final Logger log = LoggerFactory.getLogger(DockerAudit.class);
    static final int KEEP = 1000;
    private static final int MAX_FIELD = 300;

    enum Result { ok, failed, denied, started }

    record Row(long id, String ts, String hostId, String target, String action, String result, String detail, String clientIp) {}

    private final JdbcClient jdbc;

    DockerAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(String hostId, String target, String action, Result result, String detail, String ip, String userAgent) {
        try {
            jdbc.sql("INSERT INTO docker_audit (host_id, target, action, result, detail, client_ip, user_agent) VALUES (?,?,?,?,?,?,?)")
                    .params(clip(hostId), clip(target), clip(action), result.name(), clip(detail), clip(ip), clip(userAgent)).update();
            jdbc.sql("DELETE FROM docker_audit WHERE id <= (SELECT MAX(id) FROM docker_audit) - ?").param(KEEP).update();
        } catch (RuntimeException e) {
            log.warn("Docker audit write failed ({} {} {}): {}", hostId, target, action, e.getMessage());
        }
        log.info("docker host={} target={} action={} result={} ip={}", clip(hostId), clip(target), action, result, clip(ip));
    }

    List<Row> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("SELECT id, ts, host_id, target, action, result, detail, client_ip FROM docker_audit ORDER BY id DESC LIMIT ?")
                .param(n)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)))
                .list();
    }

    static String clip(String s) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > MAX_FIELD ? flat.substring(0, MAX_FIELD) + "..." : flat;
    }
}
