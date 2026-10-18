-- Terminal page (docs/terminal-feature-plan.md 5.3): who opened a shell, when, from where, and why it ended.
-- Metadata only: typed input and terminal output are never stored (decision D5).
CREATE TABLE terminal_audit (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    event      TEXT    NOT NULL CHECK (event IN ('open', 'close', 'denied')),
    host_id    TEXT,
    client_ip  TEXT,
    user_agent TEXT,
    detail     TEXT
);
CREATE INDEX idx_terminal_audit_ts ON terminal_audit (ts);
