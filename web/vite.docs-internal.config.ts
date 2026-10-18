import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The Docs editor app (docs-internal on the Docs LXC). The editor (TipTap) and Mermaid are lazy chunks.
export default defineConfig({
  root: "src/docs-internal",
  plugins: [react()],
  build: { outDir: "../../dist/docs-internal", emptyOutDir: true, sourcemap: false, target: "es2022", chunkSizeWarningLimit: 1500 },
  server: { proxy: { "/api": "http://localhost:3020", "/docs-preview": "http://localhost:3020" } },
});
