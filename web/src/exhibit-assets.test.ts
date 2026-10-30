import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { EXHIBIT_ASSET_PATHS } from "./exhibits";

type ExhibitAssetManifest = Readonly<{
  exhibits: ReadonlyArray<{
    master: { path: string; sha256: string; width: number; height: number };
    reveal: { path: string; sha256: string; width: number; height: number };
  }>;
}>;

function sha256(bytes: Uint8Array): string {
  return createHash("sha256").update(bytes).digest("hex");
}

describe("approved Exhibit asset lineage", () => {
  it("keeps the content-addressed hosted and bundled copies byte-identical to the manifest", async () => {
    const webRoot = path.resolve(process.cwd());
    const repositoryRoot = path.resolve(webRoot, "..");
    const publicDirectory = path.join(webRoot, "public", "exhibits");
    const filenames = (await readdir(publicDirectory)).sort();
    expect(filenames).toEqual(
      EXHIBIT_ASSET_PATHS.map((assetPath) => path.basename(assetPath)).sort(),
    );

    const manifest = JSON.parse(
      await readFile(
        path.join(repositoryRoot, "docs", "art-direction", "AFTERCHIME_EXHIBIT_ASSETS.json"),
        "utf8",
      ),
    ) as ExhibitAssetManifest;
    const expected = new Map(
      manifest.exhibits.flatMap((exhibit) => [
        [exhibit.master.path, exhibit.master] as const,
        [exhibit.reveal.path, exhibit.reveal] as const,
      ]),
    );

    for (const assetPath of EXHIBIT_ASSET_PATHS) {
      const filename = path.basename(assetPath);
      const hostedBytes = await readFile(path.join(publicDirectory, filename));
      const bundledBytes = await readFile(
        path.join(repositoryRoot, "android", "app", "src", "main", "assets", filename),
      );
      const digest = sha256(hostedBytes);
      const record = expected.get(assetPath);
      expect(record).toBeTruthy();
      expect(digest).toBe(record?.sha256);
      expect(filename).toContain(`-${digest.slice(0, 12)}.webp`);
      expect(sha256(bundledBytes)).toBe(digest);
      expect([record?.width, record?.height]).toEqual(
        filename.includes("-4k-") ? [4096, 4096] : [960, 960],
      );
    }
  });
});
