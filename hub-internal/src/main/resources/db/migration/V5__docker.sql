-- Docker page (docs/docker-feature-plan.md): who did what to which container or stack, and the result.
-- Metadata only: container logs and stack file contents are never written here.
CREATE TABLE docker_audit (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    host_id    TEXT    NOT NULL,
    target     TEXT,
    action     TEXT    NOT NULL,
    result     TEXT    NOT NULL CHECK (result IN ('ok', 'failed', 'denied', 'started')),
    detail     TEXT,
    client_ip  TEXT,
    user_agent TEXT
);
CREATE INDEX idx_docker_audit_ts ON docker_audit (ts);

-- Earlier versions of each stack's compose.yaml, for diff and restore. The .env (passwords) is never kept here.
CREATE TABLE docker_stack_version (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    host_id  TEXT    NOT NULL,
    stack    TEXT    NOT NULL,
    ts       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    content  TEXT    NOT NULL
);
CREATE INDEX idx_docker_stack_version ON docker_stack_version (host_id, stack, id);
