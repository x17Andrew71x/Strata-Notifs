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
  it("exposes Relic Vault and one card-only Signal Ruins series", () => {
    expect(WORLD_DEFINITIONS.map(({ id, name, baseline }) => ({ id, name, baseline }))).toEqual([
      { id: "PRIMEVAL_STRATA", name: "Relic Vault", baseline: true },
      { id: "SIGNAL_RUINS", name: "Signal Ruins", baseline: false },
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

  it("keeps Signal Ruins card-only and includes only its one preview asset", () => {
    const signalRuins = WORLD_DEFINITIONS[1];
    expect(signalRuins.artifacts).toEqual([]);
    expect(signalRuins.cardImage).toBe("/worlds/signal-ruins-artifact-67f7d4054fb5.webp");
    expect(WORLD_ASSET_PATHS).toHaveLength(35);
    expect(new Set(WORLD_ASSET_PATHS)).toHaveLength(35);
  });

  it("maps retired fictional fossil ids onto canonical research-backed identities", () => {
    for (const [legacyId, canonicalId] of Object.entries(LEGACY_RELIC_ARTIFACT_ALIASES)) {
      expect(artifactById(legacyId)).toBe(artifactById(canonicalId));
      expect(artifactById(legacyId)?.id).toBe(canonicalId);
    }
  });

  it("assigns a stable Relic Vault artifact and legacy world ids fail closed to it", () => {
    const first = artifactFor("PRIMEVAL_STRATA", "specimen_42");
    expect(artifactFor("PRIMEVAL_STRATA", "specimen_42")).toEqual(first);
    expect(RELIC_VAULT_ARTIFACTS).toContain(first);
    expect(artifactFor("DEEP_SPACE", "specimen_42")).toEqual(first);
  });

  it("maps compatibility world ids to authored public identities", () => {
    expect(worldDefinition("PRIMEVAL_STRATA").name).toBe("Relic Vault");
    expect(worldDefinition("BOTANICAL_ARCHIVE").name).toBe("Relic Vault");
    expect(worldDefinition("DEEP_SPACE").name).toBe("Relic Vault");
    expect(worldDefinition("THE_ABYSS").name).toBe("Relic Vault");
    expect(WORLD_DEFINITIONS.map((world) => world.name)).toEqual(["Relic Vault", "Signal Ruins"]);
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
      catalogRevision: number;
      templates: {
        museum: { path: string; sha256: string };
        excavation: { path: string; sha256: string };
      };
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
    expect(manifest.catalogRevision).toBe(RELIC_VAULT_CATALOG_REVISION);
    expect(manifest.templates.excavation.path).toBe(
      "docs/art-direction/assets/relic-excavation-template.png",
    );
    expect(manifest.templates.excavation.sha256).toBe(
      createHash("sha256")
        .update(
          await readFile(path.resolve(process.cwd(), "../", manifest.templates.excavation.path)),
        )
        .digest("hex"),
    );
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
      expect(bytes.byteLength).toBeGreaterThanOrEqual(relicAsset ? 70_000 : 100_000);
      expect(bytes.byteLength).toBeLessThanOrEqual(relicAsset ? 360_000 : 420_000);
      const expectedPrefix = path.basename(asset).match(/-([a-f0-9]{12})\.(?:jpg|webp)$/)?.[1];
      expect(expectedPrefix).toBeTruthy();
      const digest = createHash("sha256").update(bytes).digest("hex");
      expect(digest).toMatch(new RegExp(`^${expectedPrefix}`));
      if (!relicAsset) {
        expect(bytes.byteLength).toBe(117_270);
        expect(digest).toBe("67f7d4054fb5ce5d2cb1b0b1028f34343ec56c32539882bd443903b4d44ed501");
      }
    }
  });

  it("binds the selected ammonite identity and D tile layout to exact web and Android assets", async () => {
    const repositoryRoot = path.resolve(process.cwd(), "..");
    const manifest = JSON.parse(
      await readFile(
        path.join(repositoryRoot, "docs/art-direction/AFTERCHIME_IDENTITY_ASSETS.json"),
        "utf8",
      ),
    ) as {
      version: number;
      selection: {
        subject: string;
        tileLayout: string;
        grid: { columns: number; rows: number; remainingTiles: number; uncoveredPercent: number };
        remainingTileCoordinates: number[][];
      };
      androidComposition: {
        foregroundScalePercent: number;
        edgeInsetPercent: number;
        blendFeatherPercent: number;
        fullBleedTemplatePath: string;
      };
      outputs: Array<{
        path: string;
        sha256: string;
        bytes: number;
        width: number;
        height: number;
      }>;
    };

    expect(manifest.version).toBe(2);
    expect(manifest.androidComposition).toEqual({
      foregroundScalePercent: 86,
      edgeInsetPercent: 7,
      blendFeatherPercent: 2.2,
      fullBleedTemplatePath: "docs/art-direction/assets/relic-excavation-template.png",
    });
    expect(manifest.selection).toMatchObject({
      subject: "A — Ammonite",
      tileLayout: "D — Fossil fish",
      grid: { columns: 5, rows: 5, remainingTiles: 9, uncoveredPercent: 64 },
    });
    expect(manifest.selection.remainingTileCoordinates).toEqual([
      [2, 0],
      [4, 0],
      [0, 1],
      [3, 1],
      [2, 2],
      [1, 3],
      [4, 3],
      [0, 4],
      [2, 4],
    ]);
    for (const output of manifest.outputs) {
      const bytes = await readFile(path.join(repositoryRoot, output.path));
      expect(bytes.byteLength).toBe(output.bytes);
      expect(createHash("sha256").update(bytes).digest("hex")).toBe(output.sha256);
    }
    expect(manifest.outputs).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: "web/public/afterchime-mark.webp",
          width: 256,
          height: 256,
        }),
        expect.objectContaining({
          path: "android/app/src/main/res/drawable-nodpi/ic_afterchime_art.webp",
          width: 512,
          height: 512,
        }),
      ]),
    );
    const worker = await readFile(path.join(process.cwd(), "public/service-worker.js"), "utf8");
    expect(worker).toContain('const BRAND_ASSETS = ["/afterchime-mark.webp"]');
  });
});
