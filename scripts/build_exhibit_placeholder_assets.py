#!/usr/bin/env python3
"""Build deterministic placeholder artwork for the Exhibits first pass."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "web" / "public" / "exhibits"
ANDROID_OUTPUT = ROOT / "android" / "app" / "src" / "main" / "assets"
SIZE = 960

PALETTES = {
    "returning-light": ("#18202b", "#d09a62", "#171c1a", "#fff1bd"),
    "long-night": ("#11182a", "#4c2d28", "#0b0d0c", "#eadbb8"),
    "monsoon-choir": ("#17353e", "#d39a52", "#16211d", "#f0d69d"),
    "ember-flight": ("#351b17", "#ba653e", "#171411", "#e6b263"),
    "tideglass": ("#17323b", "#6a8c8e", "#102126", "#bdd4ce"),
}


def interpolate(left: tuple[int, int, int], right: tuple[int, int, int], amount: float) -> tuple[int, int, int]:
    return tuple(round(a + (b - a) * amount) for a, b in zip(left, right, strict=True))


def rgb(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


def artwork(name: str, palette: tuple[str, str, str, str]) -> Image.Image:
    top, bottom, land, light = map(rgb, palette)
    image = Image.new("RGB", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        colour = interpolate(top, bottom, y / (SIZE - 1))
        for x in range(SIZE):
            grain = ((x * 17 + y * 31 + (x * y) % 29) % 19) - 9
            pixels[x, y] = tuple(max(0, min(255, channel + grain // 3)) for channel in colour)

    draw = ImageDraw.Draw(image, "RGBA")
    sun_x = 580 if name != "long-night" else 670
    sun_y = 300 if name != "tideglass" else 250
    for radius in range(165, 45, -4):
        alpha = round(5 + (165 - radius) * 1.3)
        colour = (*light, min(160, alpha))
        draw.ellipse((sun_x - radius, sun_y - radius, sun_x + radius, sun_y + radius), fill=colour)

    draw.polygon(
        [(0, 610), (130, 420), (255, 565), (400, 380), (560, 590), (705, 430), (960, 620), (960, 960), (0, 960)],
        fill=(*interpolate(land, top, 0.18), 240),
    )
    draw.polygon(
        [(0, 690), (175, 520), (330, 665), (500, 500), (690, 690), (835, 540), (960, 660), (960, 960), (0, 960)],
        fill=(*land, 248),
    )
    draw.polygon(
        [(430, 570), (520, 650), (650, 655), (790, 570), (735, 750), (510, 960), (245, 960), (400, 785)],
        fill=(*interpolate(light, bottom, 0.55), 132),
    )

    for x, y, span in ((150, 330, 62), (315, 252, 46), (720, 238, 40)):
        draw.arc((x - span, y - span // 2, x, y + span // 2), 200, 338, fill=(33, 25, 22, 235), width=9)
        draw.arc((x, y - span // 2, x + span, y + span // 2), 202, 340, fill=(33, 25, 22, 235), width=9)

    for x, y, width in ((45, 700, 22), (92, 755, 29), (830, 690, 26), (900, 740, 34)):
        draw.polygon([(x - width, 960), (x, y), (x + width, 960)], fill=(9, 12, 11, 245))

    draw.line((210, 0, 198, 190, 225, 320, 206, 510), fill=(27, 19, 15, 90), width=4)
    draw.line((760, 0, 746, 150, 770, 275, 744, 460), fill=(27, 19, 15, 90), width=4)
    draw.rounded_rectangle((18, 18, 942, 942), radius=16, outline=(*light, 62), width=3)
    return image


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    ANDROID_OUTPUT.mkdir(parents=True, exist_ok=True)
    for stale in OUTPUT.glob("*-placeholder-*.webp"):
        stale.unlink()
    for stale in ANDROID_OUTPUT.glob("*-placeholder-*.webp"):
        stale.unlink()

    manifest: dict[str, dict[str, object]] = {}
    for name, palette in PALETTES.items():
        image = artwork(name, palette)
        temporary = OUTPUT / f"{name}-placeholder.webp"
        image.save(temporary, "WEBP", quality=82, method=6, exif=b"")
        payload = temporary.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        final = OUTPUT / f"{name}-placeholder-{digest[:12]}.webp"
        temporary.rename(final)
        shutil.copy2(final, ANDROID_OUTPUT / final.name)
        manifest[name] = {
            "path": f"/exhibits/{final.name}",
            "sha256": digest,
            "width": SIZE,
            "height": SIZE,
            "placeholder": True,
        }

    (OUTPUT / "placeholder-manifest.json").write_text(
        json.dumps({"schemaVersion": 1, "assets": manifest}, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
