# Relic Vault paired-asset contract

Every collectible is produced from **one fossil master** and two fixed scene templates. The backgrounds are never regenerated per fossil.

## Required source files

- `docs/art-direction/assets/<artifact-id>-master-chroma.png` — square fossil-only master on uniform `#00FF00`; no case, pedestal, dirt, text, frame, or cast shadow.
- `docs/art-direction/assets/relic-museum-template.png` — immutable front-facing black-stone Museum case and pedestal.
- `docs/art-direction/assets/relic-excavation-template.png` — immutable top-down dark earthen excavation tray.

The saved prompt beside each master is its reproducibility record. A generated checkerboard is not transparency; require the uniform chroma master so removal is deterministic.

## Deterministic composition

Run:

```bash
python3 scripts/render_relic_asset_pairs.py \
  --master docs/art-direction/assets/<artifact-id>-master-chroma.png \
  --artifact-id <artifact-id> \
  --output-dir <review-directory>
```

The renderer chroma-keys the master and applies the same dimensions, subject bounds, contact shadow, Museum supports, JPEG settings, and exact scene pixels to every fossil. It writes:

- `<artifact-id>-excavation.jpg` for the dig bed.
- `<artifact-id>-museum.jpg` for the Museum pedestal.

Only the fossil master may vary. Camera, crop, case, pedestal, bed, palette, output size, and overlay geometry remain fixed. Do not ask an image model to regenerate either scene for each item; prompts alone do not provide sufficient consistency.

Before promotion, inspect both outputs together, hash the accepted bytes into their public filenames, update the world catalogue/service-worker manifest, and verify that the excavation and Museum views use the same master.

## Master-generation rules

- One isolated physical specimen, centred, orthographic/front-facing enough to work in both scenes.
- No scenic background, frame, text, labels, glass, hands, tools, people, baked shadow, or checkerboard.
- Common: recognisable natural material, modest silhouette, restrained colour and sparkle.
- Uncommon/medium: stronger silhouette, richer mineral detail, one memorable feature.
- Rare: immediate visual surprise, exceptional material or preservation, richer internal light and detail without becoming gaudy.
- Exceptional and singular: reserve unmistakable one-off geometry/material phenomena; do not achieve rarity merely by adding glow.

## Initial launch set

The three launch pairs were rendered from their saved chroma masters through the fixed templates above:

- `relic-lunar-ash`: common, selection weight 60.
- `relic-fossil-choir`: uncommon/medium, selection weight 30.
- `relic-abyssal-glass`: rare, selection weight 10.

These are tuning values in `FossilCatalog.kt`, not hard-coded probability branches. Adding an item changes the total and therefore requires reviewing every item's weight.
