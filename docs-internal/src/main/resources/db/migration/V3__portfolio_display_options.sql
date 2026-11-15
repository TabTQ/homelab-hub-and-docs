-- Display options for the public portfolio (round 4). All optional: NULL / 0 means "automatic", exactly as before.
-- These columns exist only in docs.db. The old hub schema never had them, so PortfolioSchemaParityTest lists them as the
-- known extras and PortfolioMigrator skips them when the source (a hub backup) lacks them.
ALTER TABLE skills ADD COLUMN icon TEXT;                         -- icon key, NULL = pick from the label
ALTER TABLE skills ADD COLUMN core INTEGER NOT NULL DEFAULT 0;   -- 1 = pin to the character screen
ALTER TABLE skill_groups ADD COLUMN constellation TEXT;          -- constellation key, NULL = assign automatically
ALTER TABLE projects ADD COLUMN icon TEXT;                       -- icon key, NULL = pick automatically
