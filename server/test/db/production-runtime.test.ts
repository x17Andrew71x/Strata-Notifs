import { execFile } from "node:child_process";
import { cp, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";

const execute = promisify(execFile);
const config = loadPostgresTestConfig();
const describePostgres = config ? describe : describe.skip;
const serverRoot = path.resolve(fileURLToPath(new URL("../..", import.meta.url)));
const developmentLoader = `        if (options.config.developmentModuleEnabled && options.config.buildChannel === "dev") {
            const modulePath = "./modules/" + "dev-" + "feedback/routes.js";
            const developmentModule = (await import(modulePath));
            await developmentModule.registerDevelopmentModule(app, options.config, options.database);
        }
`;

describePostgres("production runtime migrations", () => {
  let admin: DatabaseClient;
  let harness: PostgresTestHarness;
  let temporaryRoot: string;
  let runtimeMigrations: string;

  beforeAll(async () => {
    if (!config) throw new Error("PostgreSQL test configuration is required for this suite");
    harness = await PostgresTestHarness.create(config);
    temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "afterchime-production-runtime-"));
    await cp(path.join(serverRoot, "migrations"), path.join(temporaryRoot, "migrations"), {
      recursive: true,
    });
    await mkdir(path.join(temporaryRoot, "dist", "modules", "dev-feedback"), {
      recursive: true,
    });
    await writeFile(path.join(temporaryRoot, "dist", "app.js"), developmentLoader, "utf8");
    await writeFile(
      path.join(temporaryRoot, "dist", "modules", "dev-feedback", "routes.js"),
      "export {};\n",
      "utf8",
    );
    await execute(process.execPath, [path.join(serverRoot, "scripts", "prune-dev-feedback.mjs")], {
      cwd: temporaryRoot,
      env: { ...process.env, DEV_FEEDBACK_ENABLED: "false", NODE_ENV: "production" },
    });
    runtimeMigrations = path.join(temporaryRoot, "runtime-migrations");
    await migrateDatabase(harness.migrationDatabaseUrl, { migrationsFolder: runtimeMigrations });
    const adminUrl = new URL(harness.adminDatabaseUrl);
    adminUrl.pathname = "/afterchime_test";
    admin = createSqlClient(adminUrl.toString());
  }, 30_000);

  afterAll(async () => {
    await admin?.end({ timeout: 5 });
    await harness?.close();
    if (temporaryRoot) await rm(temporaryRoot, { force: true, recursive: true });
  }, 30_000);

  it("initialises a fresh product schema without development-feedback objects and reapplies as a no-op", async () => {
    const [before] = await admin<
      Readonly<{
        development_table: string | null;
        development_type: string | null;
        migration_count: string;
        trust_table: string | null;
      }>[]
    >`
      SELECT
        to_regclass('public.installation_trust_grants')::text AS trust_table,
        to_regclass('public.dev_feedback_items')::text AS development_table,
        to_regtype('public.dev_feedback_status')::text AS development_type,
        (SELECT count(*)::text FROM drizzle.__drizzle_migrations) AS migration_count
    `;
    expect(before).toEqual({
      development_table: null,
      development_type: null,
      migration_count: "10",
      trust_table: "installation_trust_grants",
    });

    await migrateDatabase(harness.migrationDatabaseUrl, { migrationsFolder: runtimeMigrations });
    const [after] = await admin<Readonly<{ migration_count: string }>[]>`
      SELECT count(*)::text AS migration_count
      FROM drizzle.__drizzle_migrations
    `;
    expect(after?.migration_count).toBe("10");
  });
});
