import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  artifactById,
  artifactFor,
  LEGACY_RELIC_ARTIFACT_ALIASES,
  RELIC_VAULT_ARTIFACTS,
  RELIC_VAULT_CATALOG_REVISION,
  relicVaultCatalogUpdate,
  WORLD_ASSET_PATHS,
  WORLD_DEFINITIONS,
  worldDefinition,
} from "./worlds";

describe("authored world catalogue", () => {
  it("exposes only the four approved worlds with Relic Vault as the baseline", () => {
    expect(WORLD_DEFINITIONS.map(({ id, name, baseline }) => ({ id, name, baseline }))).toEqual([
      { id: "PRIMEVAL_STRATA", name: "Relic Vault", baseline: true },
      { id: "BOTANICAL_ARCHIVE", name: "Living Field Station", baseline: false },
      { id: "DEEP_SPACE", name: "Expedition Control", baseline: false },
      { id: "THE_ABYSS", name: "Charcoal Atlas", baseline: false },
    ]);
  });

  it("publishes the approved 10 common, 5 uncommon and 2 rare Relic Vault fossils", () => {
    expect(RELIC_VAULT_ARTIFACTS).toHaveLength(17);
    expect(RELIC_VAULT_ARTIFACTS.filter(({ tier }) => tier === "COMMON")).toHaveLength(10);
    expect(RELIC_VAULT_ARTIFACTS.filter(({ tier }) => tier === "UNCOMMON")).toHaveLength(5);
    expect(RELIC_VAULT_ARTIFACTS.filter(({ tier }) => tier === "RARE")).toHaveLength(2);
    expect(new Set(RELIC_VAULT_ARTIFACTS.map(({ id }) => id))).toHaveLength(17);
    expect(new Set(RELIC_VAULT_ARTIFACTS.map(({ name }) => name))).toHaveLength(17);
    expect(
      RELIC_VAULT_ARTIFACTS.every(
        ({ museumImage, excavationImage }) => String(museumImage) !== String(excavationImage),
      ),
    ).toBe(true);
  });

  it("publishes the complete native draw as a versioned hosted-shell update", () => {
    const payload = relicVaultCatalogUpdate() as {
      schemaVersion: number;
      revision: number;
      items: Array<{ id: string; tier: string; selectionWeight: number; family: string }>;
    };
    expect(payload.schemaVersion).toBe(1);
    expect(payload.revision).toBe(RELIC_VAULT_CATALOG_REVISION);
    expect(payload.items.map(({ id }) => id)).toEqual(RELIC_VAULT_ARTIFACTS.map(({ id }) => id));
    expect(payload.items.reduce((total, item) => total + item.selectionWeight, 0)).toBe(1_000);
    expect(payload.items.every(({ family }) => /^[A-Z_]{1,40}$/.test(family))).toBe(true);
  });

  it("keeps three unique authored artifacts in each cosmetic world", () => {
    for (const world of WORLD_DEFINITIONS.slice(1)) {
      expect(world.artifacts).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.name))).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.museumImage))).toHaveLength(3);
      expect(new Set(world.artifacts.map((artifact) => artifact.id))).toHaveLength(3);
    }
    expect(WORLD_ASSET_PATHS).toHaveLength(43);
    expect(new Set(WORLD_ASSET_PATHS)).toHaveLength(43);
  });

  it("maps retired fictional fossil ids onto canonical research-backed identities", () => {
    for (const [legacyId, canonicalId] of Object.entries(LEGACY_RELIC_ARTIFACT_ALIASES)) {
      expect(artifactById(legacyId)).toBe(artifactById(canonicalId));
      expect(artifactById(legacyId)?.id).toBe(canonicalId);
    }
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

  it("keeps the exact quality-bounded content-addressed artwork inventory offline-authorized", async () => {
    const publicRoot = path.resolve(process.cwd(), "public");
    const files = (await readdir(path.join(publicRoot, "worlds"))).sort();
    expect(files).toEqual(WORLD_ASSET_PATHS.map((asset) => path.basename(asset)).sort());

    const manifest = JSON.parse(
      await readFile(
        path.resolve(process.cwd(), "../docs/art-direction/FOSSIL_CATALOG_ASSETS.json"),
        "utf8",
      ),
    ) as {
      output: {
        format: string;
        width: number;
        height: number;
        quality: number;
        method: number;
        totalBytes: number;
      };
      artifacts: Array<{
        museum: { path: string; bytes: number };
        excavation: { path: string; bytes: number };
      }>;
    };
    expect(manifest.output).toMatchObject({
      format: "WEBP",
      width: 960,
      height: 960,
      quality: 82,
      method: 6,
    });
    expect(manifest.output.totalBytes).toBeLessThanOrEqual(7 * 1024 * 1024);
    expect(
      manifest.artifacts.flatMap(({ museum, excavation }) => [museum.path, excavation.path]).sort(),
    ).toEqual(
      RELIC_VAULT_ARTIFACTS.flatMap(({ museumImage, excavationImage }) => [
        museumImage,
        excavationImage,
      ]).sort(),
    );
    expect(
      manifest.artifacts.reduce(
        (total, { museum, excavation }) => total + museum.bytes + excavation.bytes,
        0,
      ),
    ).toBe(manifest.output.totalBytes);

    const worker = await readFile(path.join(publicRoot, "service-worker.js"), "utf8");
    expect(worker).toContain(`const CATALOG_REVISION = ${RELIC_VAULT_CATALOG_REVISION};`);
    for (const asset of WORLD_ASSET_PATHS) {
      expect(worker).toContain(`"${asset}"`);
      const bytes = await readFile(path.join(publicRoot, asset));
      const relicAsset = path.basename(asset).startsWith("relic-");
      expect(bytes.byteLength).toBeGreaterThanOrEqual(relicAsset ? 70_000 : 120_000);
      expect(bytes.byteLength).toBeLessThanOrEqual(relicAsset ? 360_000 : 420_000);
      const expectedPrefix = path.basename(asset).match(/-([a-f0-9]{12})\.(?:jpg|webp)$/)?.[1];
      expect(expectedPrefix).toBeTruthy();
      expect(createHash("sha256").update(bytes).digest("hex")).toMatch(
        new RegExp(`^${expectedPrefix}`),
      );
    }
  });
});
