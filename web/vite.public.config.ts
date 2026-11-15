import react from "@vitejs/plugin-react";
import { copyFileSync, existsSync, readFileSync, rmSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig, type Plugin } from "vite";

// The owner's real portrait is git-ignored. If it exists, it is served (dev) or emitted (build) as /avatar.webp
// in place of the committed placeholder, and the private file itself never lands in dist.
function privateAvatar(): Plugin {
  const priv = resolve(__dirname, "src/public/public/avatar.private.webp");
  let outDir = "";
  return {
    name: "private-avatar",
    configResolved(c) { outDir = resolve(c.root, c.build.outDir); },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split("?")[0] === "/avatar.webp" && existsSync(priv)) {
          res.setHeader("Content-Type", "image/webp");
          res.end(readFileSync(priv));
        } else next();
      });
    },
    closeBundle() {
      if (!existsSync(priv) || !outDir) return;
      copyFileSync(priv, resolve(outDir, "avatar.webp"));
      rmSync(resolve(outDir, "avatar.private.webp"), { force: true });
    },
  };
}

// The public bundle: read-only portfolio. It must never contain admin, dashboard or action code, so it has its own
// entry and imports nothing from src/internal (checked by scripts/check-public-bundle.mjs after the build).
export default defineConfig({
  root: "src/public",
  // Relative base: the built folder works at the site root and from a sub-folder (the GitHub Pages copy).
  base: "./",
  plugins: [react(), privateAvatar()],
  build: { outDir: "../../dist/public", emptyOutDir: true, sourcemap: false, target: "es2022" },
});
