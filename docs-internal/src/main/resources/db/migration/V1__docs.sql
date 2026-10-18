-- Docs app schema (docs plan 5.2). Its own database (docs.db) on the Docs LXC; nothing is shared with the hub.

CREATE TABLE doc_bundles (                                  -- one row per webapp upload
    id            INTEGER PRIMARY KEY,
    page_id       INTEGER NOT NULL REFERENCES doc_pages(id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,                         -- b1, b2 … per page
    sha256        TEXT NOT NULL,                            -- hash of the manifest
    manifest_json TEXT NOT NULL,                            -- [{path, sha256, size, mime}]
    total_size    INTEGER NOT NULL,
    created_at    TEXT NOT NULL,
    UNIQUE (page_id, seq)
);

CREATE TABLE doc_pages (
    id                INTEGER PRIMARY KEY,
    parent_id         INTEGER REFERENCES doc_pages(id),     -- NULL = top level
    position          INTEGER NOT NULL,                     -- order among siblings (gapless, rewritten on move)
    kind              TEXT NOT NULL CHECK (kind IN ('rich', 'webapp')),
    title             TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    draft_json        TEXT NOT NULL,                        -- ProseMirror doc (rich) or webapp settings JSON
    draft_bundle      INTEGER REFERENCES doc_bundles(id),   -- webapp only
    public_host       TEXT UNIQUE,                          -- lowercase FQDN; NULL = internal
    published_version INTEGER,                              -- version that is live; NULL = never published or unpublished
    published_at      TEXT,
    deleted_at        TEXT,                                 -- Trash; purged after 30 days
    trash_root        INTEGER,                              -- the page the delete started from (restore brings the subtree back)
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL,
    version           INTEGER NOT NULL DEFAULT 0            -- optimistic locking
);
CREATE INDEX doc_pages_parent ON doc_pages (parent_id, position);

CREATE TABLE doc_page_versions (                            -- every save / publish / restore / move / rename / host change
    page_id    INTEGER NOT NULL REFERENCES doc_pages(id) ON DELETE CASCADE,
    version    INTEGER NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('created', 'saved', 'published', 'restored', 'moved', 'renamed', 'host', 'bundle', 'unpublished')),
    title      TEXT NOT NULL,
    body_json  TEXT NOT NULL,
    bundle_id  INTEGER REFERENCES doc_bundles(id),
    note       TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (page_id, version)
);

CREATE TABLE doc_assets (                                   -- images, attachments, Mermaid SVGs
    sha256        TEXT PRIMARY KEY,
    mime          TEXT NOT NULL,
    size          INTEGER NOT NULL,
    original_name TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

CREATE TABLE audit_log (                                    -- same shape as the hub's
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    source      TEXT NOT NULL CHECK (source IN ('ui')),
    action_id   TEXT NOT NULL,
    target      TEXT NOT NULL,
    result      TEXT NOT NULL CHECK (result IN ('ok', 'failed', 'denied')),
    detail      TEXT,
    client_ip   TEXT,
    user_agent  TEXT
);
CREATE INDEX idx_audit_log_ts ON audit_log (ts);

CREATE TABLE kv (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);
