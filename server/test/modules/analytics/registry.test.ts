import { readdir, readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { analyticsEvent } from "../../../src/modules/analytics/registry.js";

const fixturesRoot = new URL("../../../../contracts/fixtures/", import.meta.url);

async function fixtures(kind: "invalid" | "valid"): Promise<readonly [string, unknown][]> {
  const directory = new URL(`${kind}/`, fixturesRoot);
  const names = (await readdir(directory)).filter((name) => name.endsWith(".json")).sort();
  return Promise.all(
    names.map(
      async (name) => [name, JSON.parse(await readFile(new URL(name, directory), "utf8"))] as const,
    ),
  );
}

describe("analytics event registry", () => {
  it("accepts every versioned valid fixture", async () => {
    for (const [name, event] of await fixtures("valid")) {
      expect(analyticsEvent.safeParse(event).success, name).toBe(true);
    }
  });

  it("rejects every versioned invalid fixture", async () => {
    for (const [name, event] of await fixtures("invalid")) {
      expect(analyticsEvent.safeParse(event).success, name).toBe(false);
    }
  });
});
