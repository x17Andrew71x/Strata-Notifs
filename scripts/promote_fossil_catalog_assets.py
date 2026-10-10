#!/usr/bin/env python3
"""Promote the approved 17-fossil Relic Vault artwork into content-addressed web assets."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from render_relic_asset_pairs import (
    JPEG_QUALITY,
    JPEG_SUBSAMPLING,
    OUTPUT_SIZE,
    chroma_key,
    render_excavation,
    render_museum,
)

ROOT = Path(__file__).resolve().parents[1]
MASTER_DIR = ROOT / "docs" / "art-direction" / "assets"
OUTPUT_DIR = ROOT / "web" / "public" / "worlds"
MANIFEST_PATH = ROOT / "docs" / "art-direction" / "FOSSIL_CATALOG_ASSETS.json"
MIN_JPEG_BYTES = 120_000
MAX_JPEG_BYTES = 420_000
MAX_CATALOG_BYTES = 9 * 1024 * 1024


@dataclass(frozen=True)
class ApprovedFossil:
    artifact_id: str
    name: str
    tier: str


APPROVED_FOSSILS = (
    ApprovedFossil("relic-dactylioceras-ammonite", "Ribbed Jurassic Ammonite", "COMMON"),
    ApprovedFossil("relic-belemnite-rostra", "Belemnite Rostra", "COMMON"),
    ApprovedFossil("relic-spiriferid-brachiopod", "Spiriferid Brachiopod", "COMMON"),
    ApprovedFossil("relic-gryphaea-oyster", "Gryphaea Oyster", "COMMON"),
    ApprovedFossil("relic-crinoid-columnals", "Crinoid Columnals", "COMMON"),
    ApprovedFossil("relic-rugose-horn-coral", "Rugose Horn Coral", "COMMON"),
    ApprovedFossil("relic-lamniform-shark-tooth", "Lamniform Shark Tooth", "COMMON"),
    ApprovedFossil("relic-carbonised-fern-frond", "Carbonised Fern Frond", "COMMON"),
    ApprovedFossil("relic-domal-stromatolite", "Domal Stromatolite", "COMMON"),
    ApprovedFossil("relic-echinocorys-echinoid", "Chalk Echinoid", "COMMON"),
    ApprovedFossil("relic-articulated-trilobite", "Articulated Trilobite", "UNCOMMON"),
    ApprovedFossil("relic-articulated-fossil-fish", "Articulated Fossil Fish", "UNCOMMON"),
    ApprovedFossil("relic-complete-starfish", "Complete Fossil Starfish", "UNCOMMON"),
    ApprovedFossil("relic-articulated-fossil-crab", "Articulated Fossil Crab", "UNCOMMON"),
    ApprovedFossil("relic-insect-amber", "Insect in Amber", "UNCOMMON"),
    ApprovedFossil("relic-dinosaur-embryo-egg", "Dinosaur Embryo in Egg", "RARE"),
    ApprovedFossil("relic-archaeopteryx-slab", "Archaeopteryx Slab", "RARE"),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def save_jpeg(path: Path, image: Image.Image) -> None:
    image.save(
        path,
        "JPEG",
        quality=JPEG_QUALITY,
        subsampling=JPEG_SUBSAMPLING,
        optimize=True,
        progressive=True,
        exif=b"",
    )
    with Image.open(path) as rendered:
        if rendered.size != (OUTPUT_SIZE, OUTPUT_SIZE) or rendered.mode != "RGB":
            raise SystemExit(f"invalid promoted image geometry: {path}")
        if rendered.getexif():
            raise SystemExit(f"promoted image retained EXIF metadata: {path}")
    if not MIN_JPEG_BYTES <= path.stat().st_size <= MAX_JPEG_BYTES:
        raise SystemExit(f"promoted image outside byte budget: {path} ({path.stat().st_size})")


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    with tempfile.TemporaryDirectory(prefix="afterchime-fossil-promotion-") as temporary:
        staging = Path(temporary)
        staged_outputs: list[tuple[Path, str]] = []
        for fossil in APPROVED_FOSSILS:
            master = MASTER_DIR / f"{fossil.artifact_id}-master-chroma.png"
            if not master.is_file():
                raise SystemExit(f"missing approved master: {master}")
            subject = chroma_key(master)
            outputs = {
                "museum": render_museum(
                    MASTER_DIR / "relic-museum-template.png",
                    subject,
                ),
                "excavation": render_excavation(
                    MASTER_DIR / "relic-excavation-template.png",
                    subject,
                ),
            }
            output_records: dict[str, object] = {}
            for kind, image in outputs.items():
                unhashed = staging / f"{fossil.artifact_id}-{kind}.jpg"
                save_jpeg(unhashed, image)
                digest = sha256(unhashed)
                filename = f"{fossil.artifact_id}-{kind}-{digest[:12]}.jpg"
                staged_outputs.append((unhashed, filename))
                output_records[kind] = {
                    "path": f"/worlds/{filename}",
                    "sha256": digest,
                    "bytes": unhashed.stat().st_size,
                    "width": OUTPUT_SIZE,
                    "height": OUTPUT_SIZE,
                }
            records.append(
                {
                    "id": fossil.artifact_id,
                    "name": fossil.name,
                    "tier": fossil.tier,
                    "master": {
                        "path": f"docs/art-direction/assets/{master.name}",
                        "sha256": sha256(master),
                        "bytes": master.stat().st_size,
                    },
                    **output_records,
                }
            )

        total_bytes = sum(
            int(record[kind]["bytes"])  # type: ignore[index]
            for record in records
            for kind in ("museum", "excavation")
        )
        if total_bytes > MAX_CATALOG_BYTES:
            raise SystemExit(f"promoted catalogue exceeds byte budget: {total_bytes}")

        for stale in OUTPUT_DIR.glob("relic-*.jpg"):
            stale.unlink()
        for staged, filename in staged_outputs:
            os.replace(staged, OUTPUT_DIR / filename)

    manifest = {
        "version": 1,
        "status": "approved-production-art",
        "output": {
            "format": "JPEG",
            "width": OUTPUT_SIZE,
            "height": OUTPUT_SIZE,
            "quality": JPEG_QUALITY,
            "subsampling": "4:2:0",
            "progressive": True,
            "exif": False,
            "totalBytes": total_bytes,
            "maximumTotalBytes": MAX_CATALOG_BYTES,
        },
        "counts": {"common": 10, "uncommon": 5, "rare": 2, "pairs": 34},
        "artifacts": records,
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "artifacts": len(records),
                "images": len(records) * 2,
                "totalBytes": total_bytes,
                "manifest": str(MANIFEST_PATH.relative_to(ROOT)),
            }
        )
    )


if __name__ == "__main__":
    main()
