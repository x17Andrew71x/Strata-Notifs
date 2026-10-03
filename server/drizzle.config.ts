import { defineConfig } from "drizzle-kit";

// biome-ignore lint/complexity/useLiteralKeys: Node's environment index signature requires bracket access.
const databaseUrl = process.env["DATABASE_MIGRATOR_URL"];
if (!databaseUrl) {
  throw new Error("DATABASE_MIGRATOR_URL is required for Drizzle commands");
}

export default defineConfig({
  dialect: "postgresql",
  schema: "./src/db/schema/**/*.ts",
  out: "./migrations",
  dbCredentials: {
    url: databaseUrl,
  },
  strict: true,
  verbose: true,
});
