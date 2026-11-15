// Packs the built public bundles into docs-internal, so the Java app can write the GitHub Pages copy without Node.
//   node scripts/pack-static-export.mjs [distDir] [resourcesDir]
// distDir:      where the builds are (default web/dist). Needs docs-public/ and public/ inside.
// resourcesDir: docs-internal's resources folder (default ../docs-internal/src/main/resources).
// Result: <resourcesDir>/static-export/viewer/ and /portfolio/, each with files.txt (one relative path per line, sorted).
// The private portrait is never copied: only avatar.webp, which the build already chose.
import { cpSync, existsSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const dist = resolve(process.argv[2] ?? join(here, "../dist"));
const res = resolve(process.argv[3] ?? join(here, "../../docs-internal/src/main/resources"));

const PARTS = [["docs-public", "viewer"], ["public", "portfolio"]];

function walk(d) {
  return readdirSync(d).flatMap((f) => { const p = join(d, f); return statSync(p).isDirectory() ? walk(p) : [p]; });
}

const out = join(res, "static-export");
rmSync(out, { recursive: true, force: true });
for (const [from, to] of PARTS) {
  const src = join(dist, from);
  if (!existsSync(join(src, "index.html"))) { console.error(`${src} has no index.html: build it first`); process.exit(2); }
  const dest = join(out, to);
  cpSync(src, dest, { recursive: true, filter: (p) => !/avatar\.private\./.test(p) });
  const files = walk(dest).map((p) => relative(dest, p).split("\\").join("/")).filter((p) => p !== "files.txt").sort();
  writeFileSync(join(dest, "files.txt"), files.join("\n") + "\n");
  console.log(`${to}: ${files.length} files`);
}
