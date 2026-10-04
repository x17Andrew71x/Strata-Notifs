import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    manifest: true,
    assetsDir: "assets",
  },
  server: { host: "0.0.0.0" },
  preview: { host: "0.0.0.0" },
});
