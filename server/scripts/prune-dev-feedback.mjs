import { cp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";

const enabled =
  process.env.DEV_FEEDBACK_ENABLED === "true" && process.env.NODE_ENV !== "production";
const runtimeMigrations = path.resolve("runtime-migrations");
await rm(runtimeMigrations, { recursive: true, force: true });
await cp(path.resolve("migrations"), runtimeMigrations, { recursive: true });

if (!enabled) {
  await rm(path.resolve("dist/modules/dev-feedback"), { recursive: true, force: true });
  const appPath = path.resolve("dist/app.js");
  const developmentLoader = `        if (options.config.developmentModuleEnabled && options.config.buildChannel === "dev") {
            const modulePath = "./modules/" + "dev-" + "feedback/routes.js";
            const developmentModule = (await import(modulePath));
            await developmentModule.registerDevelopmentModule(app, options.config, options.database);
        }
`;
  const appSource = await readFile(appPath, "utf8");
  if (appSource.split(developmentLoader).length !== 2) {
    throw new Error("development-feedback runtime loader was not removed exactly once");
  }
  await writeFile(appPath, appSource.replace(developmentLoader, ""), "utf8");
  const developmentMigrationTags = [
    "0004_orange_human_fly",
    "0005_magical_donald_blake",
    "0006_marvelous_randall_flagg",
    "0007_little_zodiak",
    "0008_classify_legacy_feedback_failure",
    "0013_restore_feedback_review_queue",
    "0014_restore_stale_feedback_work",
    "0015_restore_current_feedback_client",
    "0016_restore_feedback_client_function",
    "0017_restore_feedback_after_registration",
    "0018_restore_feedback_isolated_client",
  ];
  for (const tag of developmentMigrationTags) {
    await rm(path.join(runtimeMigrations, `${tag}.sql`), { force: true });
  }
  const metadataDirectory = path.join(runtimeMigrations, "meta");
  for (const name of await readdir(metadataDirectory)) {
    if (name.endsWith("_snapshot.json")) {
      await rm(path.join(metadataDirectory, name), { force: true });
    }
  }
  const journalPath = path.join(metadataDirectory, "_journal.json");
  const journal = JSON.parse(await readFile(journalPath, "utf8"));
  const priorEntries = journal.entries;
  if (!Array.isArray(priorEntries)) throw new Error("migration journal entries are invalid");
  const developmentTags = new Set(developmentMigrationTags);
  journal.entries = priorEntries.filter((entry) => !developmentTags.has(entry?.tag));
  if (journal.entries.length !== priorEntries.length - developmentMigrationTags.length) {
    throw new Error("development-feedback migrations were not removed exactly once");
  }
  await writeFile(journalPath, `${JSON.stringify(journal, null, 2)}\n`, "utf8");
}
