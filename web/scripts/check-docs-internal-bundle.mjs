// The portfolio editor is a lazy chunk of the Docs app: reading a Docs page must never download it.
// Fails when the entry chunk (the one index.html loads first) contains the editor's code.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const dir = join(import.meta.dirname, "..", "dist", "docs-internal");
const html = readFileSync(join(dir, "index.html"), "utf8");
const entry = /src="\/?([^"]*assets\/[^"]+\.js)"/.exec(html)?.[1];
if (!entry) { console.error("docs-internal bundle check: no entry script found in index.html"); process.exit(1); }
const entryJs = readFileSync(join(dir, entry), "utf8");
const markers = ["Manage skills", "/api/admin/portfolio/schema"];
const leaked = markers.filter((m) => entryJs.includes(m));
const chunks = readdirSync(join(dir, "assets")).filter((f) => /^PortfolioAdmin-.*\.js$/.test(f));
if (chunks.length !== 1) { console.error("docs-internal bundle check: expected one PortfolioAdmin chunk, found " + chunks.length); process.exit(1); }
if (leaked.length) { console.error("docs-internal bundle check: the portfolio editor leaked into the entry chunk: " + leaked.join(", ")); process.exit(1); }
console.log("docs-internal bundle is clean (portfolio editor is a lazy chunk)");
