import { existsSync } from "node:fs";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { drizzle } from "drizzle-orm/postgres-js";
import { migrate } from "drizzle-orm/postgres-js/migrator";
import { createSqlClient, type DatabaseClient } from "./client.js";

export type DatabaseReadiness = Readonly<{
  database: string;
  migrationHead: string | null;
  postgresVersion: string;
}>;

export type MigrationOptions = Readonly<{
  migrationsFolder?: string;
}>;

type MigrationJournal = Readonly<{
  dialect: string;
  entries: readonly Readonly<{
    breakpoints: boolean;
    idx: number;
    tag: string;
    version: string;
    when: number;
  }>[];
  version: string;
}>;

const enumCompatibilityBoundary = "0007_little_zodiak";

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

export async function migrateDatabase(
  databaseUrl: string,
  options: MigrationOptions = {},
): Promise<DatabaseReadiness> {
  const serverRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
  const migrationsFolder = options.migrationsFolder ?? path.join(serverRoot, "migrations");

  if (!existsSync(migrationsFolder)) {
    throw new Error(`migration directory is missing: ${migrationsFolder}`);
  }

  const sql = createSqlClient(databaseUrl);
  try {
    await migrateWithEnumCompatibility(sql, migrationsFolder);
    return await readDatabaseReadiness(sql);
  } finally {
    await sql.end({ timeout: 5 });
  }
}

async function migrateWithEnumCompatibility(
  sql: DatabaseClient,
  migrationsFolder: string,
): Promise<void> {
  const journalPath = path.join(migrationsFolder, "meta", "_journal.json");
  const journal = JSON.parse(await readFile(journalPath, "utf8")) as MigrationJournal;
  const boundary = journal.entries.find((entry) => entry.tag === enumCompatibilityBoundary);
  if (!boundary) {
    await migrate(drizzle(sql), { migrationsFolder });
    return;
  }

  const [journalState] = await sql<Readonly<{ exists: boolean }>[]>`
    SELECT to_regclass('drizzle.__drizzle_migrations') IS NOT NULL AS exists
  `;
  let head: number | null = null;
  if (journalState?.exists) {
    const [state] = await sql<Readonly<{ head: number | null }>[]>`
      SELECT max(created_at)::double precision AS head FROM drizzle.__drizzle_migrations
    `;
    head = state?.head ?? null;
  }
  if ((head ?? -1) < boundary.when) {
    const temporaryRoot = await mkdtemp(
      path.join(process.env["TMPDIR"] ?? os.tmpdir(), "afterchime-migrations-"),
    );
    try {
      await mkdir(path.join(temporaryRoot, "meta"), { recursive: true });
      const entries = journal.entries.filter((entry) => entry.idx <= boundary.idx);
      await writeFile(
        path.join(temporaryRoot, "meta", "_journal.json"),
        `${JSON.stringify({ ...journal, entries }, null, 2)}\n`,
        "utf8",
      );
      await Promise.all(
        entries.map((entry) =>
          copyFile(
            path.join(migrationsFolder, `${entry.tag}.sql`),
            path.join(temporaryRoot, `${entry.tag}.sql`),
          ),
        ),
      );
      await migrate(drizzle(sql), { migrationsFolder: temporaryRoot });
    } finally {
      await rm(temporaryRoot, { force: true, recursive: true });
    }
  }
  await migrate(drizzle(sql), { migrationsFolder });
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
