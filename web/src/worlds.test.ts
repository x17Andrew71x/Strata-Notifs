import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { artifactFor, WORLD_ASSET_PATHS, WORLD_DEFINITIONS, worldDefinition } from "./worlds";

describe("authored world catalogue", () => {
  it("exposes only the four approved worlds with Relic Vault as the baseline", () => {
    expect(WORLD_DEFINITIONS.map(({ id, name, baseline }) => ({ id, name, baseline }))).toEqual([
      { id: "PRIMEVAL_STRATA", name: "Relic Vault", baseline: true },
      { id: "BOTANICAL_ARCHIVE", name: "Living Field Station", baseline: false },
      { id: "DEEP_SPACE", name: "Expedition Control", baseline: false },
      { id: "THE_ABYSS", name: "Charcoal Atlas", baseline: false },
    ]);
  });

  it("keeps exactly three unique authored artifacts in every world", () => {
    for (const world of WORLD_DEFINITIONS) {
      expect(world.artifacts).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.name))).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.museumImage))).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.id))).toHaveLength(3);
    }
    expect(WORLD_ASSET_PATHS).toHaveLength(15);
    expect(new Set(WORLD_ASSET_PATHS)).toHaveLength(15);
    const relic = worldDefinition("PRIMEVAL_STRATA");
    expect(
      relic.artifacts.every((artifact) => artifact.museumImage !== artifact.excavationImage),
    ).toBe(true);
  });

  it("assigns a stable artifact and never escapes the selected world", () => {
    for (const world of WORLD_DEFINITIONS) {
      const first = artifactFor(world.id, "specimen_42");
      expect(artifactFor(world.id, "specimen_42")).toEqual(first);
      expect(world.artifacts).toContain(first);
    }
  });

  it("maps compatibility world ids to authored public identities", () => {
    expect(worldDefinition("PRIMEVAL_STRATA").name).toBe("Relic Vault");
    expect(worldDefinition("BOTANICAL_ARCHIVE").name).toBe("Living Field Station");
    expect(worldDefinition("DEEP_SPACE").name).toBe("Expedition Control");
    expect(worldDefinition("THE_ABYSS").name).toBe("Charcoal Atlas");
    expect(WORLD_DEFINITIONS.map((world) => world.name)).not.toEqual(
      expect.arrayContaining(["Primeval Strata", "Deep Space", "Botanical Archive", "The Abyss"]),
    );
  });

  it("keeps the exact artwork inventory present and offline-authorized", async () => {
    const publicRoot = path.resolve(process.cwd(), "public");
    const files = (await readdir(path.join(publicRoot, "worlds"))).sort();
    expect(files).toEqual(WORLD_ASSET_PATHS.map((asset) => path.basename(asset)).sort());

    const worker = await readFile(path.join(publicRoot, "service-worker.js"), "utf8");
    for (const asset of WORLD_ASSET_PATHS) {
      expect(worker).toContain(`"${asset}"`);
      expect((await readFile(path.join(publicRoot, asset))).byteLength).toBeGreaterThan(100_000);
    }
  });
});
