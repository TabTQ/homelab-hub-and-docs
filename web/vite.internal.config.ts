import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  root: "src/internal",
  plugins: [react()],
  build: { outDir: "../../dist/internal", emptyOutDir: true, sourcemap: false, target: "es2022" },
  server: { proxy: { "/api": "http://localhost:3010", "/images": "http://localhost:3010" } },
});
