import { defineConfig } from "vitest/config";

export default defineConfig({
  define: { "import.meta.env.VITE_DEV_FEEDBACK_ENABLED": '"true"' },
  test: { environment: "jsdom", setupFiles: ["./src/test-setup.ts"] },
});
