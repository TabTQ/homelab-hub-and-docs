-- GitHub Pages as a second place to publish a page. A page may be public on a local address, on GitHub, or both.
-- github_slug is the folder name on the Pages site (NULL = not on GitHub). The unique index ignores the NULLs.
ALTER TABLE doc_pages ADD COLUMN github_slug TEXT;
CREATE UNIQUE INDEX doc_pages_github_slug ON doc_pages (github_slug) WHERE github_slug IS NOT NULL;
ALTER TABLE doc_pages ADD COLUMN github_published_at TEXT;
