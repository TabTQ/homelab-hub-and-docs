-- Homelab items can be linked to skills (mockup: picking "Docker / Compose" also finds the Pi services that use it).
CREATE TABLE device_skills (
    device_id  INTEGER NOT NULL REFERENCES homelab_devices (id) ON DELETE CASCADE,
    skill_id   INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    PRIMARY KEY (device_id, skill_id)
);

CREATE TABLE container_skills (
    container_id  INTEGER NOT NULL REFERENCES homelab_containers (id) ON DELETE CASCADE,
    skill_id      INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    PRIMARY KEY (container_id, skill_id)
);

CREATE TABLE service_skills (
    service_id  INTEGER NOT NULL REFERENCES homelab_services (id) ON DELETE CASCADE,
    skill_id    INTEGER NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    PRIMARY KEY (service_id, skill_id)
);

-- Optimistic locking for the editable skill tables (the others already have `version`).
ALTER TABLE skill_groups ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE skills ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_unlinked_tags_owner ON unlinked_tags (owner_type, owner_id);
