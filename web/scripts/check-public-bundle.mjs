// The public bundles are served to the internet. They must not contain any of the internal apps' code or API paths.
// Run after the public builds; exits 1 with the offending strings if anything leaks.
//   node scripts/check-public-bundle.mjs              the portfolio bundle (dist/public)
//   node scripts/check-public-bundle.mjs docs-public  the shared Docs page bundle (dist/docs-public)
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const HUB_FORBIDDEN = [
  "/api/admin", "/api/dashboard", "/api/actions", "/api/status", "/api/widgets", "/api/auth", "/api/portfolio",
  "dashboard.yaml", "hub:config-save", "codemirror", "PROXMOX", "secret:", "X-XSRF-TOKEN", "XSRF-TOKEN",
  "typed_mismatch", "proxmox-node", "proxmox-guest", "Wake-on-LAN", "wake-packet",
];

const TARGETS = {
  public: { dir: "../dist/public/", forbidden: HUB_FORBIDDEN, required: ["/api/public/portfolio"] },
  // Docs plan 5.7: no editor (TipTap/ProseMirror), no Mermaid, no admin, preview or login code in the shared-page bundle.
  "docs-public": {
    dir: "../dist/docs-public/",
    forbidden: [...HUB_FORBIDDEN, "tiptap", "prosemirror", "ProseMirror", "mermaid.initialize", "docs-preview", "preview-token",
      "/api/public/portfolio", "publish-check"],
    required: ["/api/public/page"],
  },
};

const name = process.argv[2] ?? "public";
const target = TARGETS[name];
if (!target) { console.error(`unknown bundle ${name}`); process.exit(2); }
const dir = new URL(target.dir, import.meta.url).pathname;
if (!existsSync(dir)) { console.error(`${dir} does not exist: build it first`); process.exit(2); }

function walk(d) {
  return readdirSync(d).flatMap((f) => { const p = join(d, f); return statSync(p).isDirectory() ? walk(p) : [p]; });
}

let bad = 0;
const files = walk(dir).filter((f) => /\.(js|css|html|map)$/.test(f));
let all = "";
for (const f of files) {
  const text = readFileSync(f, "utf8");
  all += text;
  for (const s of target.forbidden) {
    if (text.includes(s)) { console.error(`LEAK: ${f.replace(dir, "")} contains "${s}"`); bad++; }
  }
}
for (const s of target.required) if (!all.includes(s)) { console.error(`MISSING: the ${name} bundle no longer references ${s}`); bad++; }
console.log(`${name}: checked ${files.length} files, ${(all.length / 1024).toFixed(0)} KiB of text`);
if (bad) { console.error(`${bad} problem(s). The ${name} bundle must stay read-only.`); process.exit(1); }
console.log(`${name} bundle is clean`);
