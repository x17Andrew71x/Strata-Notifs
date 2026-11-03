import { execFile } from "node:child_process";
import { cp, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { afterEach, describe, expect, it } from "vitest";

const execute = promisify(execFile);
const serverRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const temporaryRoots: string[] = [];
const developmentLoader = `        if (options.config.developmentModuleEnabled && options.config.buildChannel === "dev") {
            const modulePath = "./modules/" + "dev-" + "feedback/routes.js";
            const developmentModule = (await import(modulePath));
            await developmentModule.registerDevelopmentModule(app, options.config, options.database);
        }
`;
const forbiddenMigrationMarker = /dev_feedback|dev-feedback|development_feedback_identity/;

async function productionFixture(): Promise<string> {
  const root = await mkdtemp(path.join(os.tmpdir(), "afterchime-production-artifact-"));
  temporaryRoots.push(root);
  await cp(path.join(serverRoot, "migrations"), path.join(root, "migrations"), { recursive: true });
  await mkdir(path.join(root, "dist", "modules", "dev-feedback"), { recursive: true });
  await writeFile(path.join(root, "dist", "app.js"), developmentLoader, "utf8");
  await writeFile(path.join(root, "dist", "modules", "dev-feedback", "routes.js"), "export {};\n");
  await execute(process.execPath, [path.join(serverRoot, "scripts", "prune-dev-feedback.mjs")], {
    cwd: root,
    env: { ...process.env, DEV_FEEDBACK_ENABLED: "false", NODE_ENV: "production" },
  });
  return root;
}

afterEach(async () => {
  await Promise.all(
    temporaryRoots.splice(0).map((root) => rm(root, { force: true, recursive: true })),
  );
});

describe("production runtime migration artifact", () => {
  it("removes the complete development-feedback migration lineage and snapshot metadata", async () => {
    const root = await productionFixture();
    const runtime = path.join(root, "runtime-migrations");
    const journal = JSON.parse(
      await readFile(path.join(runtime, "meta", "_journal.json"), "utf8"),
    ) as Readonly<{ entries: readonly Readonly<{ tag: string }>[] }>;
    const tags = journal.entries.map(({ tag }) => tag);

    expect(tags).toEqual([
      "0000_fair_harpoon",
      "0001_session_functions",
      "0002_slow_skrulls",
      "0003_pure_vulture",
      "0009_exhibits",
      "0010_dynamic_exhibit_scaling",
      "0011_retire_placeholder_exhibits",
      "0012_first_featured_exhibit",
      "0019_security_hardening",
      "0020_installation_trust_readback",
    ]);
    expect((await readdir(path.join(runtime, "meta"))).sort()).toEqual(["_journal.json"]);

    for (const name of await readdir(runtime)) {
      if (!name.endsWith(".sql")) continue;
      expect(await readFile(path.join(runtime, name), "utf8"), name).not.toMatch(
        forbiddenMigrationMarker,
      );
    }
  });
});
