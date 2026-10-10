# Relic Vault paired-asset contract

Every collectible is produced from **one fossil master** and two fixed scene templates. The backgrounds are never regenerated per fossil.

## Required source files

- `docs/art-direction/assets/<artifact-id>-master-chroma.png` — square fossil-only master on uniform `#00FF00`; no case, pedestal, dirt, text, frame, or cast shadow.
- `docs/art-direction/assets/relic-museum-template.png` — immutable front-facing black-stone Museum case and pedestal.
- `docs/art-direction/assets/relic-excavation-template.png` — immutable top-down dark earthen excavation tray.

`FOSSIL_CATALOG_GENERATION.json` records the accepted generation request and treatment, while `FOSSIL_CATALOG_ASSETS.json` records every approved master and output digest. A generated checkerboard is not transparency; require the uniform chroma master so removal is deterministic.

## Deterministic composition

Run:

```bash
python3 scripts/render_relic_asset_pairs.py \
  --master docs/art-direction/assets/<artifact-id>-master-chroma.png \
  --artifact-id <artifact-id> \
  --output-dir <review-directory>
```

The renderer chroma-keys the master and applies the same dimensions, subject bounds, contact shadow, Museum supports, WebP settings, and exact scene pixels to every fossil. It writes:

- `<artifact-id>-excavation.webp` for the dig bed.
- `<artifact-id>-museum.webp` for the Museum pedestal.

Only the fossil master may vary. Camera, crop, case, pedestal, bed, palette, output size, and overlay geometry remain fixed. Do not ask an image model to regenerate either scene for each item; prompts alone do not provide sufficient consistency.

Before promotion, inspect both outputs together, hash the accepted bytes into their public filenames, update the world catalogue/service-worker manifest, and verify that the excavation and Museum views use the same master.

## Master-generation rules

- One isolated physical specimen, centred, orthographic/front-facing enough to work in both scenes.
- No scenic background, frame, text, labels, glass, hands, tools, people, baked shadow, or checkerboard.
- Common: recognisable natural material with visibly adhered dry-earth clumps, grit or smears; faded, matte and low-saturation rather than merely recoloured brown.
- Uncommon/medium: stronger anatomical completeness, still weathered and dusty, with modestly clearer detail than common.
- Rare: exceptional preservation and meticulous preparation with controlled museum-natural colour; never oversaturated or jewel-like.
- Exceptional and singular: reserve unmistakable one-off geometry/material phenomena; do not achieve rarity merely by adding glow.

## Approved production set

The first production catalogue contains 17 paired fossils: 10 common, 5 uncommon and 2 rare. Exact IDs, source-master hashes, output hashes, dimensions and byte sizes live in `FOSSIL_CATALOG_ASSETS.json`; scientific rationale and guardrails live in `FOSSIL_CATALOG_RESEARCH.md`.

Production exports are 960×960 lossy WebP images at quality 82 with encoder method 6, exact RGB handling and no EXIF. The selected encoding measured higher fidelity than the former quality-86 JPEG set (mean PSNR 36.210 dB versus 35.616 dB; mean absolute error 2.809 versus 3.000), passed full-frame and 2× fine-detail review, and reduced the 34-image catalogue from 8,275,478 to 6,581,638 bytes. That resolution preserves more than 2.5 source pixels per CSS pixel across the 360px WebView surface, while the complete Relic Vault catalogue remains capped below 7 MiB.

Run `python3 scripts/promote_fossil_catalog_assets.py` after an approved master changes. The script renders both fixed scenes, enforces geometry and byte budgets, writes content-addressed filenames, removes retired Relic Vault JPEG/WebP exports and refreshes `FOSSIL_CATALOG_ASSETS.json`. Catalogue code and the service-worker manifest must then be updated to those exact paths and verified by tests.
