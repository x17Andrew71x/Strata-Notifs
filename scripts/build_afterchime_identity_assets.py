#!/usr/bin/env python3
"""Build the selected Afterchime identity and excavation background assets."""

from __future__ import annotations

import hashlib
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ART_DIR = ROOT / "docs" / "art-direction"
SOURCE_DIR = ART_DIR / "assets"
ICON_SOURCE = SOURCE_DIR / "afterchime-icon-ammonite-source.png"
SHALE_TEXTURE = SOURCE_DIR / "afterchime-identity-shale-texture.png"
DIRT_TEXTURE = SOURCE_DIR / "afterchime-identity-dirt-texture.png"
BACKGROUND_SOURCE = SOURCE_DIR / "afterchime-excavation-background-source.png"
ICON_MASTER = SOURCE_DIR / "afterchime-icon-master.png"
EXCAVATION_TEMPLATE = SOURCE_DIR / "relic-excavation-template.png"
WEB_MARK = ROOT / "web" / "public" / "afterchime-mark.webp"
ANDROID_MARK = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "res"
    / "drawable-nodpi"
    / "ic_afterchime_art.webp"
)
MANIFEST = ART_DIR / "AFTERCHIME_IDENTITY_ASSETS.json"
ICON_SIZE = 1024
GRID_SIZE = 5
TILE_LAYOUT = {
    (2, 0),
    (4, 0),
    (0, 1),
    (3, 1),
    (2, 2),
    (1, 3),
    (4, 3),
    (0, 4),
    (2, 4),
}
BASE_COLOUR = (68, 48, 32)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def add_vignette(image: Image.Image) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    for inset in range(0, 116, 4):
        alpha = int(110 * (1 - inset / 116) ** 1.7)
        draw.rounded_rectangle(
            (inset, inset, image.width - 1 - inset, image.height - 1 - inset),
            radius=max(0, 178 - inset),
            outline=alpha,
            width=7,
        )
    shade = Image.new("RGBA", image.size, (0, 0, 0, 0))
    shade.putalpha(mask.filter(ImageFilter.GaussianBlur(15)))
    return Image.alpha_composite(image.convert("RGBA"), shade)


def deterministic_noise(size: tuple[int, int], rng: random.Random) -> Image.Image:
    pixels = bytes(rng.randrange(256) for _ in range(size[0] * size[1]))
    return Image.frombytes("L", size, pixels)


def add_dirt_grunge(image: Image.Image, seed: int) -> Image.Image:
    rng = random.Random(seed)
    dirt = Image.open(DIRT_TEXTURE).convert("RGB")
    crop_size = min(dirt.height, dirt.width)
    crop_left = rng.randint(0, dirt.width - crop_size)
    dirt = dirt.crop((crop_left, 0, crop_left + crop_size, crop_size)).resize(
        image.size,
        Image.Resampling.LANCZOS,
    )
    dirt = ImageEnhance.Color(dirt).enhance(0.72).convert("RGBA")

    noise = deterministic_noise(image.size, rng).filter(ImageFilter.GaussianBlur(18))
    mask = noise.point(lambda value: max(0, min(72, int((value - 112) * 1.7))))
    edge_mask = Image.new("L", image.size, 0)
    edge_draw = ImageDraw.Draw(edge_mask)
    edge_draw.ellipse((115, 115, image.width - 115, image.height - 115), fill=38)
    edge_mask = ImageOps.invert(edge_mask).filter(ImageFilter.GaussianBlur(45))
    mask = Image.blend(mask, edge_mask, 0.28)
    dirt.putalpha(mask)
    result = Image.alpha_composite(image.convert("RGBA"), dirt)

    grit = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(grit, "RGBA")
    colours = [(73, 48, 30), (111, 76, 47), (48, 37, 29), (145, 103, 66)]
    for _ in range(150):
        x = rng.randint(34, image.width - 35)
        y = rng.randint(34, image.height - 35)
        radius = rng.choices((1, 2, 3, 5, 8), weights=(28, 31, 23, 13, 5))[0]
        colour = rng.choice(colours)
        draw.ellipse(
            (x - radius, y - radius, x + radius, y + radius),
            fill=(*colour, rng.randint(34, 105)),
        )
    return Image.alpha_composite(result, grit.filter(ImageFilter.GaussianBlur(0.45)))


def tile_polygon(
    left: int,
    top: int,
    size: int,
    rng: random.Random,
) -> list[tuple[int, int]]:
    chip = 8
    return [
        (left + chip + rng.randint(-3, 4), top + rng.randint(-2, 4)),
        (left + size - chip + rng.randint(-4, 3), top + rng.randint(-2, 4)),
        (left + size + rng.randint(-4, 2), top + chip + rng.randint(-3, 4)),
        (left + size + rng.randint(-4, 2), top + size - chip + rng.randint(-4, 3)),
        (left + size - chip + rng.randint(-3, 4), top + size + rng.randint(-4, 2)),
        (left + chip + rng.randint(-4, 3), top + size + rng.randint(-4, 2)),
        (left + rng.randint(-2, 4), top + size - chip + rng.randint(-3, 4)),
        (left + rng.randint(-2, 4), top + chip + rng.randint(-4, 3)),
    ]


def render_tile(
    canvas: Image.Image,
    shale: Image.Image,
    dirt: Image.Image,
    box: tuple[int, int, int, int],
    seed: int,
) -> None:
    rng = random.Random(seed)
    left, top, right, bottom = box
    size = right - left
    polygon = tile_polygon(left, top, size, rng)

    shadow_mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(shadow_mask).polygon(
        [(x + 5, y + 8) for x, y in polygon],
        fill=180,
    )
    shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(6))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.putalpha(shadow_mask)
    canvas.alpha_composite(shadow)

    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).polygon(polygon, fill=255)
    patch_size = min(300, shale.width // 3, shale.height // 3)
    shale_left = rng.randint(0, shale.width - patch_size)
    shale_top = rng.randint(0, shale.height - patch_size)
    texture = shale.crop(
        (shale_left, shale_top, shale_left + patch_size, shale_top + patch_size),
    ).resize((size + 4, size + 4), Image.Resampling.LANCZOS)
    if rng.choice((True, False)):
        texture = ImageOps.mirror(texture)
    texture = texture.rotate(rng.choice((0, 90, 180, 270)))
    texture = ImageEnhance.Color(texture).enhance(0.72)
    texture = ImageEnhance.Contrast(texture).enhance(1.08)
    texture = ImageEnhance.Brightness(texture).enhance(0.66)

    dirt_left = rng.randint(0, dirt.width - patch_size)
    dirt_top = rng.randint(0, dirt.height - patch_size)
    dirt_patch = dirt.crop(
        (dirt_left, dirt_top, dirt_left + patch_size, dirt_top + patch_size),
    ).resize((size + 4, size + 4), Image.Resampling.LANCZOS)
    dirt_patch = ImageEnhance.Brightness(dirt_patch).enhance(0.62)
    texture = Image.blend(texture.convert("RGBA"), dirt_patch.convert("RGBA"), 0.3)
    tint = Image.new("RGBA", texture.size, (*BASE_COLOUR, 255))
    texture = Image.blend(texture, tint, 0.22)
    texture_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    texture_layer.paste(texture, (left - 2, top - 2))
    texture_layer.putalpha(mask)
    canvas.alpha_composite(texture_layer)

    shade = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shade_draw = ImageDraw.Draw(shade)
    for y in range(top, bottom + 1):
        alpha = int(62 * ((y - top) / max(1, size)))
        shade_draw.line((left, y, right, y), fill=(0, 0, 0, alpha), width=1)
    shade.putalpha(
        Image.composite(
            shade.getchannel("A"),
            Image.new("L", canvas.size, 0),
            mask,
        ),
    )
    canvas.alpha_composite(shade)

    draw = ImageDraw.Draw(canvas, "RGBA")
    draw.line(
        polygon + [polygon[0]],
        fill=(171, 129, 82, 135),
        width=3,
        joint="curve",
    )
    draw.line((polygon[0], polygon[1], polygon[2]), fill=(218, 181, 124, 58), width=3)
    draw.line((polygon[4], polygon[5], polygon[6]), fill=(4, 3, 2, 120), width=4)
    for _ in range(34):
        x = rng.randint(left + 9, right - 9)
        y = rng.randint(top + 9, bottom - 9)
        if mask.getpixel((x, y)) == 0:
            continue
        radius = rng.choice((1, 1, 2, 2, 3))
        delta = rng.randint(-28, 25)
        colour = tuple(max(0, min(255, channel + delta)) for channel in BASE_COLOUR)
        draw.ellipse(
            (x - radius, y - radius, x + radius, y + radius),
            fill=(*colour, rng.randint(75, 150)),
        )


def build_icon_master() -> None:
    image = Image.open(ICON_SOURCE).convert("RGB").resize(
        (ICON_SIZE, ICON_SIZE),
        Image.Resampling.LANCZOS,
    )
    image = add_dirt_grunge(add_vignette(image), seed=65 * 177)
    margin, gap = 45, 10
    cell = (ICON_SIZE - margin * 2 - gap * (GRID_SIZE - 1)) // GRID_SIZE
    grid_end = margin + cell * GRID_SIZE + gap * (GRID_SIZE - 1)
    grooves = Image.new("RGBA", image.size, (0, 0, 0, 0))
    groove_draw = ImageDraw.Draw(grooves, "RGBA")
    for index in range(1, GRID_SIZE):
        position = margin + index * cell + (index - 1) * gap + gap // 2
        groove_draw.line((margin, position, grid_end, position), fill=(4, 3, 2, 45), width=3)
        groove_draw.line((position, margin, position, grid_end), fill=(4, 3, 2, 45), width=3)
    image.alpha_composite(grooves)

    shale = Image.open(SHALE_TEXTURE).convert("RGBA")
    dirt = Image.open(DIRT_TEXTURE).convert("RGBA")
    for column, row in sorted(TILE_LAYOUT, key=lambda position: (position[1], position[0])):
        left = margin + column * (cell + gap)
        top = margin + row * (cell + gap)
        render_tile(
            image,
            shale,
            dirt,
            (left, top, left + cell, top + cell),
            seed=6500 + row * 11 + column,
        )
    ICON_MASTER.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(ICON_MASTER, "PNG", optimize=True)


def build_excavation_template() -> None:
    source = Image.open(BACKGROUND_SOURCE).convert("RGB")
    smooth = source.filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.SMOOTH)
    flat = ImageOps.posterize(ImageEnhance.Color(smooth).enhance(0.82), 5)
    template = Image.blend(source, flat, 0.72)
    template.save(EXCAVATION_TEMPLATE, "PNG", optimize=True)


def save_derivatives() -> None:
    image = Image.open(ICON_MASTER).convert("RGB")
    WEB_MARK.parent.mkdir(parents=True, exist_ok=True)
    ANDROID_MARK.parent.mkdir(parents=True, exist_ok=True)
    image.resize((256, 256), Image.Resampling.LANCZOS).save(
        WEB_MARK,
        "WEBP",
        quality=90,
        method=6,
        exact=True,
        exif=b"",
    )
    image.resize((512, 512), Image.Resampling.LANCZOS).save(
        ANDROID_MARK,
        "WEBP",
        quality=90,
        method=6,
        exact=True,
        exif=b"",
    )


def image_record(path: Path) -> dict[str, object]:
    with Image.open(path) as image:
        return {
            "path": str(path.relative_to(ROOT)),
            "sha256": sha256(path),
            "bytes": path.stat().st_size,
            "format": image.format,
            "mode": image.mode,
            "width": image.width,
            "height": image.height,
        }


def write_manifest() -> None:
    manifest = {
        "version": 1,
        "status": "approved-production-identity",
        "selection": {
            "subject": "A — Ammonite",
            "tileLayout": "D — Fossil fish",
            "grid": {"columns": 5, "rows": 5, "remainingTiles": 9, "uncoveredPercent": 64},
            "remainingTileCoordinates": sorted(
                [list(position) for position in TILE_LAYOUT],
                key=lambda position: (position[1], position[0]),
            ),
            "treatment": "realistic flat cel-shaded relief with dirt, shale dust, grit, clumps, and scrape marks",
        },
        "generation": {
            "model": "openai-codex/gpt-image-2-medium",
            "iconSourceRequestId": "5cd48e08-5b5e-421b-820c-4dce3b98bd76",
            "shaleTextureRequestId": "e119980e-3a6b-4d67-a9ae-42b041ee9d40",
            "dirtTextureRequestId": "ef1005ef-544b-4d5a-91ce-6e6b4f8fa1db",
            "excavationBackgroundRequestId": "4b45e8cc-74ef-4baf-9492-96eee10d8b32",
            "builder": "scripts/build_afterchime_identity_assets.py",
        },
        "sources": [
            image_record(ICON_SOURCE),
            image_record(SHALE_TEXTURE),
            image_record(DIRT_TEXTURE),
            image_record(BACKGROUND_SOURCE),
        ],
        "outputs": [
            image_record(ICON_MASTER),
            image_record(EXCAVATION_TEMPLATE),
            image_record(WEB_MARK),
            image_record(ANDROID_MARK),
        ],
    }
    encoded = json.dumps(manifest, indent=2)
    for column, row in sorted(TILE_LAYOUT, key=lambda position: (position[1], position[0])):
        encoded = encoded.replace(
            f"[\n        {column},\n        {row}\n      ]",
            f"[{column}, {row}]",
        )
    MANIFEST.write_text(encoded + "\n", encoding="utf-8")
    print(json.dumps({"outputs": len(manifest["outputs"]), "manifest": str(MANIFEST.relative_to(ROOT))}))


def main() -> None:
    build_icon_master()
    build_excavation_template()
    save_derivatives()
    write_manifest()


if __name__ == "__main__":
    main()
