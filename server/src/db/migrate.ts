import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { createSqlClient, type DatabaseClient } from "./client.js";

const execFileAsync = promisify(execFile);

export type DatabaseReadiness = Readonly<{
  database: string;
  migrationHead: string | null;
  postgresVersion: string;
}>;

export async function readDatabaseReadiness(sql: DatabaseClient): Promise<DatabaseReadiness> {
  const [server] = await sql<
    Readonly<{
      database: string;
      postgres_version: string;
    }>[]
  >`SELECT current_database() AS database, current_setting('server_version') AS postgres_version`;

  if (!server) {
    throw new Error("database readiness query returned no server record");
  }

  const [journal] = await sql<
    Readonly<{ exists: boolean }>[]
  >`SELECT to_regclass('drizzle.__drizzle_migrations') IS NOT NULL AS exists`;
  let migrationHead: string | null = null;

  if (journal?.exists) {
    const [migration] = await sql<
      Readonly<{ hash: string }>[]
    >`SELECT hash FROM drizzle.__drizzle_migrations ORDER BY created_at DESC LIMIT 1`;
    migrationHead = migration?.hash ?? null;
  }

  return {
    database: server.database,
    migrationHead,
    postgresVersion: server.postgres_version,
  };
}

export async function migrateDatabase(databaseUrl: string): Promise<DatabaseReadiness> {
  const serverRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
  const migrationsFolder = path.join(serverRoot, "migrations");

  if (existsSync(migrationsFolder)) {
    const drizzleKit = path.join(serverRoot, "node_modules/.bin/drizzle-kit");
    await execFileAsync(drizzleKit, ["migrate"], {
      cwd: serverRoot,
      env: {
        ...process.env,
        DATABASE_MIGRATOR_URL: databaseUrl,
        DATABASE_URL: databaseUrl,
      },
      maxBuffer: 1024 * 1024,
    });
  }

  const sql = createSqlClient(databaseUrl);
  try {
    return await readDatabaseReadiness(sql);
  } finally {
    await sql.end({ timeout: 5 });
  }
}

async function main(): Promise<void> {
  // biome-ignore lint/complexity/useLiteralKeys: Node's environment index signature requires bracket access.
  const databaseUrl = process.env["DATABASE_MIGRATOR_URL"];
  if (!databaseUrl) {
    throw new Error("DATABASE_MIGRATOR_URL is required for migrations");
  }

  const readiness = await migrateDatabase(databaseUrl);
  process.stdout.write(`${JSON.stringify(readiness)}\n`);
}

const invokedFile = process.argv[1];
if (invokedFile && import.meta.url === pathToFileURL(path.resolve(invokedFile)).href) {
  await main();
}
