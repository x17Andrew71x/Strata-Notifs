import { cp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const enabled =
  process.env.DEV_FEEDBACK_ENABLED === "true" && process.env.NODE_ENV !== "production";
const runtimeMigrations = path.resolve("runtime-migrations");
await rm(runtimeMigrations, { recursive: true, force: true });
await cp(path.resolve("migrations"), runtimeMigrations, { recursive: true });

if (!enabled) {
  await rm(path.resolve("dist/modules/dev-feedback"), { recursive: true, force: true });
  await rm(path.join(runtimeMigrations, "0004_orange_human_fly.sql"), { force: true });
  await rm(path.join(runtimeMigrations, "meta", "0004_snapshot.json"), { force: true });
  const journalPath = path.join(runtimeMigrations, "meta", "_journal.json");
  const journal = JSON.parse(await readFile(journalPath, "utf8"));
  const priorEntries = journal.entries;
  if (!Array.isArray(priorEntries)) throw new Error("migration journal entries are invalid");
  journal.entries = priorEntries.filter((entry) => entry?.tag !== "0004_orange_human_fly");
  if (journal.entries.length !== priorEntries.length - 1) {
    throw new Error("development-feedback migration was not removed exactly once");
  }
  await writeFile(journalPath, `${JSON.stringify(journal, null, 2)}\n`, "utf8");
}
