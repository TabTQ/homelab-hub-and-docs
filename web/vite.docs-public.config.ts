import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The public Docs bundle: one shared page, read-only. It must never contain the editor, Mermaid or any admin code, so it
// has its own entry and imports nothing from src/docs-internal (checked by scripts/check-public-bundle.mjs after the build).
export default defineConfig({
  root: "src/docs-public",
  // Relative base: the built folder works at the site root and from a sub-folder (the GitHub Pages copy).
  base: "./",
  plugins: [react()],
  build: { outDir: "../../dist/docs-public", emptyOutDir: true, sourcemap: false, target: "es2022" },
});
