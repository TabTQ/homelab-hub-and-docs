import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The public Docs bundle: one shared page, read-only. It must never contain the editor, Mermaid or any admin code, so it
// has its own entry and imports nothing from src/docs-internal (checked by scripts/check-public-bundle.mjs after the build).
export default defineConfig({
  root: "src/docs-public",
  plugins: [react()],
  build: { outDir: "../../dist/docs-public", emptyOutDir: true, sourcemap: false, target: "es2022" },
});
