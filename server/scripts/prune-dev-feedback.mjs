import { cp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const enabled =
  process.env.DEV_FEEDBACK_ENABLED === "true" && process.env.NODE_ENV !== "production";
const runtimeMigrations = path.resolve("runtime-migrations");
await rm(runtimeMigrations, { recursive: true, force: true });
await cp(path.resolve("migrations"), runtimeMigrations, { recursive: true });

if (!enabled) {
  await rm(path.resolve("dist/modules/dev-feedback"), { recursive: true, force: true });
  const developmentMigrations = [
    { tag: "0004_orange_human_fly", snapshot: "0004_snapshot.json" },
    { tag: "0005_magical_donald_blake", snapshot: "0005_snapshot.json" },
  ];
  for (const migration of developmentMigrations) {
    await rm(path.join(runtimeMigrations, `${migration.tag}.sql`), { force: true });
    await rm(path.join(runtimeMigrations, "meta", migration.snapshot), { force: true });
  }
  const journalPath = path.join(runtimeMigrations, "meta", "_journal.json");
  const journal = JSON.parse(await readFile(journalPath, "utf8"));
  const priorEntries = journal.entries;
  if (!Array.isArray(priorEntries)) throw new Error("migration journal entries are invalid");
  const developmentTags = new Set(developmentMigrations.map(({ tag }) => tag));
  journal.entries = priorEntries.filter((entry) => !developmentTags.has(entry?.tag));
  if (journal.entries.length !== priorEntries.length - developmentMigrations.length) {
    throw new Error("development-feedback migrations were not removed exactly once");
  }
  await writeFile(journalPath, `${JSON.stringify(journal, null, 2)}\n`, "utf8");
}
