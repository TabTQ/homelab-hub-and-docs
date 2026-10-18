-- homelab-hub initial schema (plan section 6). SQLite.
-- JSON columns are TEXT guarded by json_valid(). Timestamps are UTC ISO-8601 text.
-- `version` columns support optimistic locking (plan 8.3).

CREATE TABLE profile (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    name        TEXT NOT NULL,
    title       TEXT NOT NULL,
    tagline     TEXT NOT NULL DEFAULT '',
    location    TEXT NOT NULL DEFAULT '',
    email       TEXT NOT NULL DEFAULT '',
    phone       TEXT NOT NULL DEFAULT '',
    brand       TEXT NOT NULL DEFAULT '',
    status      TEXT NOT NULL DEFAULT '',
    summary     TEXT NOT NULL DEFAULT '',
    bio         TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(bio)),
    socials     TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(socials)),
    stats       TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(stats)),
    version     INTEGER NOT NULL DEFAULT 0,
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE experiences (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    slug            TEXT NOT NULL UNIQUE,
    role            TEXT NOT NULL,
    company         TEXT NOT NULL,
    client          TEXT,
    duration_label  TEXT NOT NULL,
    start_date      TEXT,           -- reserved for the deferred timeline (plan D5); unused in v1
    end_date        TEXT,
    location        TEXT,
    summary         TEXT NOT NULL,
    highlights      TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(highlights)),
    sort_order      INTEGER NOT NULL DEFAULT 0,
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE projects (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slug         TEXT NOT NULL UNIQUE,
    kind         TEXT NOT NULL CHECK (kind IN ('flagship', 'small')),
    title        TEXT NOT NULL,
    description  TEXT NOT NULL,
    repo_url     TEXT,
    live_url     TEXT,
    type         TEXT,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE project_case_studies (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    client      TEXT,
    role        TEXT,
    duration    TEXT,
    context     TEXT,
    platform    TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(platform)),
    metrics     TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(metrics)),
    layers      TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(layers)),
    tech_stack  TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(tech_stack)),
    version     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE project_deliverables (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id       INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    deliverable_key  TEXT NOT NULL,
    title            TEXT NOT NULL,
    tag              TEXT NOT NULL,
    body             TEXT NOT NULL,
    sort_order       INTEGER NOT NULL DEFAULT 0,
    version          INTEGER NOT NULL DEFAULT 0,
    UNIQUE (project_id, deliverable_key)
);

CREATE TABLE academic_items (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    type         TEXT NOT NULL,
    title        TEXT NOT NULL,
    institution  TEXT NOT NULL,
    date_label   TEXT NOT NULL,
    description  TEXT,
    url          TEXT,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 0
);

-- Skills (plan 8.2). Normalised: canonical skills plus links to experiences and projects.
CREATE TABLE skill_groups (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    category    TEXT NOT NULL,
    icon        TEXT NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE skills (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id    INTEGER NOT NULL REFERENCES skill_groups (id) ON DELETE CASCADE,
    label       TEXT NOT NULL COLLATE NOCASE UNIQUE,
    sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE skill_aliases (
    alias     TEXT NOT NULL COLLATE NOCASE PRIMARY KEY,
    skill_id  INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE
);

CREATE TABLE experience_skills (
    experience_id  INTEGER NOT NULL REFERENCES experiences (id) ON DELETE CASCADE,
    skill_id       INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    PRIMARY KEY (experience_id, skill_id)
);

CREATE TABLE project_skills (
    project_id  INTEGER NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    skill_id    INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    PRIMARY KEY (project_id, skill_id)
);

-- Free-text tags that did not map to a skill. Polymorphic owner: integrity is enforced by the app, not an FK.
CREATE TABLE unlinked_tags (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_type  TEXT NOT NULL CHECK (owner_type IN ('experience', 'project')),
    owner_id    INTEGER NOT NULL,
    text        TEXT NOT NULL,
    UNIQUE (owner_type, owner_id, text)
);

-- Homelab content shown on the portfolio's homelab pages (plan 8.6). Static content, not live status.
CREATE TABLE homelab_devices (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_key  TEXT NOT NULL UNIQUE,
    category    TEXT NOT NULL,
    name        TEXT NOT NULL,
    summary     TEXT,
    ip_address  TEXT,
    status      TEXT,
    hardware    TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(hardware)),
    extra       TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(extra)),
    sort_order  INTEGER NOT NULL DEFAULT 0,
    version     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE homelab_containers (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    container_key  TEXT NOT NULL UNIQUE,
    device_id      INTEGER NOT NULL REFERENCES homelab_devices (id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    ip_address     TEXT,
    purpose        TEXT,
    resources      TEXT,
    status         TEXT NOT NULL DEFAULT 'Active',
    services       TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(services)),
    sort_order     INTEGER NOT NULL DEFAULT 0,
    version        INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE homelab_services (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL REFERENCES homelab_devices (id) ON DELETE CASCADE,
    service_name    TEXT NOT NULL,
    container_name  TEXT,
    host_port       TEXT,
    function        TEXT,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    version         INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE homelab_network_entities (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id    INTEGER NOT NULL REFERENCES homelab_devices (id) ON DELETE CASCADE,
    entity_type  TEXT NOT NULL,
    name         TEXT NOT NULL,
    spec         TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(spec)),
    sort_order   INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 0
);

-- Operational tables (plan 6.2).
CREATE TABLE audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    source      TEXT NOT NULL CHECK (source IN ('ui', 'legacy')),
    action_id   TEXT NOT NULL,
    target      TEXT NOT NULL,
    result      TEXT NOT NULL CHECK (result IN ('ok', 'failed', 'denied', 'timeout')),
    detail      TEXT,
    client_ip   TEXT,
    user_agent  TEXT
);
CREATE INDEX idx_audit_log_ts ON audit_log (ts);

CREATE TABLE config_versions (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    ts       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    sha256   TEXT NOT NULL,
    content  TEXT NOT NULL,
    source   TEXT NOT NULL CHECK (source IN ('ui', 'file', 'import', 'restore')),
    note     TEXT
);
CREATE INDEX idx_config_versions_ts ON config_versions (ts);

CREATE TABLE kv (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
