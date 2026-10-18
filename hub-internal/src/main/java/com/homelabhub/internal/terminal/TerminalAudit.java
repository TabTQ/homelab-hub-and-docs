package com.homelabhub.internal.terminal;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Terminal session metadata (plan R8): open, close and denied attempts. NEVER typed input or output. Keeps the last
 * {@value #KEEP} rows. A failing write is logged and ignored: auditing must never break a session.
 */
@Service
public class TerminalAudit {

    private static final Logger log = LoggerFactory.getLogger(TerminalAudit.class);
    static final int KEEP = 1000;
    private static final int MAX_FIELD = 300;

    public enum Event { open, close, denied }

    public record Row(long id, String ts, String event, String hostId, String clientIp, String userAgent, String detail) {}

    private final JdbcClient jdbc;

    public TerminalAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Event event, String hostId, String clientIp, String userAgent, String detail) {
        try {
            jdbc.sql("INSERT INTO terminal_audit (event, host_id, client_ip, user_agent, detail) VALUES (?,?,?,?,?)")
                    .params(event.name(), clip(hostId), clip(clientIp), clip(userAgent), clip(detail)).update();
            jdbc.sql("DELETE FROM terminal_audit WHERE id <= (SELECT MAX(id) FROM terminal_audit) - ?").param(KEEP).update();
        } catch (RuntimeException e) {
            log.warn("Terminal audit write failed ({} {}): {}", event, hostId, e.getMessage());
        }
        log.info("terminal event={} host={} ip={} {}", event, clip(hostId), clip(clientIp), detail == null ? "" : clip(detail));
    }

    public List<Row> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("SELECT id, ts, event, host_id, client_ip, user_agent, detail FROM terminal_audit ORDER BY id DESC LIMIT ?")
                .param(n)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)))
                .list();
    }

    /** "k=v k=v" for the detail column, values flattened. */
    static String detail(Map<String, ?> values) {
        StringBuilder b = new StringBuilder();
        values.forEach((k, v) -> {
            if (v != null) {
                b.append(b.length() == 0 ? "" : " ").append(k).append('=').append(String.valueOf(v).replaceAll("\\s+", "_"));
            }
        });
        return b.toString();
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("[\\r\\n]+", " ");
        return flat.length() > MAX_FIELD ? flat.substring(0, MAX_FIELD) + "..." : flat;
    }
}
