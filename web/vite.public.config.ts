import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The public bundle: read-only portfolio. It must never contain admin, dashboard or action code, so it has its own
// entry and imports nothing from src/internal (checked by scripts/check-public-bundle.mjs after the build).
export default defineConfig({
  root: "src/public",
  plugins: [react()],
  build: { outDir: "../../dist/public", emptyOutDir: true, sourcemap: false, target: "es2022" },
});
