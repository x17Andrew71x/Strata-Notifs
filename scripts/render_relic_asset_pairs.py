#!/usr/bin/env python3
"""Render one fossil master into the locked Relic Vault excavation and Museum scenes."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import cast

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MUSEUM_TEMPLATE = ROOT / "docs" / "art-direction" / "assets" / "relic-museum-template.png"
DEFAULT_EXCAVATION_TEMPLATE = (
    ROOT / "docs" / "art-direction" / "assets" / "relic-excavation-template.png"
)
OUTPUT_SIZE = 960
MUSEUM_SUBJECT_WIDTH_RATIO = 0.46
MUSEUM_SUBJECT_HEIGHT_RATIO = 0.45
MUSEUM_SUBJECT_CENTER_Y_RATIO = 0.43
MUSEUM_SUPPORT_BASE_Y_RATIO = 0.65
EXCAVATION_SUBJECT_WIDTH_RATIO = 0.60
EXCAVATION_SUBJECT_HEIGHT_RATIO = 0.60
SHADOW_BLUR_RADIUS = 16
SHADOW_OPACITY = 105
CHROMA_MIN_GREEN = 100
CHROMA_DOMINANCE_START = 32
CHROMA_DOMINANCE_RANGE = 82
JPEG_QUALITY = 90


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--master", type=Path, required=True, help="fossil on pure chroma-green")
    parser.add_argument("--artifact-id", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--museum-template", type=Path, default=DEFAULT_MUSEUM_TEMPLATE)
    parser.add_argument("--excavation-template", type=Path, default=DEFAULT_EXCAVATION_TEMPLATE)
    return parser.parse_args()


def chroma_key(master_path: Path) -> Image.Image:
    source = Image.open(master_path).convert("RGBA")
    keyed = Image.new("RGBA", source.size)
    output: list[tuple[int, int, int, int]] = []
    for pixel in source.get_flattened_data():
        red, green, blue, _ = cast(tuple[int, int, int, int], pixel)
        dominance = green - max(red, blue)
        if green < CHROMA_MIN_GREEN or dominance <= CHROMA_DOMINANCE_START:
            alpha = 255
        else:
            alpha = 255 - round(
                min(1.0, (dominance - CHROMA_DOMINANCE_START) / CHROMA_DOMINANCE_RANGE) * 255,
            )
        if alpha < 255:
            green = min(green, max(red, blue))
        output.append((red, green, blue, alpha))
    keyed.putdata(output)
    bounds = keyed.getchannel("A").getbbox()
    if bounds is None:
        raise SystemExit("master contained no non-green fossil pixels")
    return keyed.crop(bounds)


def fitted(subject: Image.Image, max_width: int, max_height: int) -> Image.Image:
    scale = min(max_width / subject.width, max_height / subject.height)
    size = (max(1, round(subject.width * scale)), max(1, round(subject.height * scale)))
    return subject.resize(size, Image.Resampling.LANCZOS)


def paste_with_shadow(canvas: Image.Image, subject: Image.Image, left: int, top: int) -> None:
    alpha = subject.getchannel("A")
    shadow = Image.new("RGBA", canvas.size)
    shadow_mask = Image.new("L", canvas.size)
    shadow_mask.paste(alpha, (left + 7, top + 12))
    shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(SHADOW_BLUR_RADIUS))
    shadow.putalpha(shadow_mask.point([value * SHADOW_OPACITY // 255 for value in range(256)]))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(subject, (left, top))


def render_museum(template_path: Path, subject: Image.Image) -> Image.Image:
    canvas = Image.open(template_path).convert("RGBA").resize(
        (OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS
    )
    fossil = fitted(
        subject,
        round(OUTPUT_SIZE * MUSEUM_SUBJECT_WIDTH_RATIO),
        round(OUTPUT_SIZE * MUSEUM_SUBJECT_HEIGHT_RATIO),
    )
    left = (OUTPUT_SIZE - fossil.width) // 2
    top = round(OUTPUT_SIZE * MUSEUM_SUBJECT_CENTER_Y_RATIO - fossil.height / 2)
    support_base = round(OUTPUT_SIZE * MUSEUM_SUPPORT_BASE_Y_RATIO)
    draw = ImageDraw.Draw(canvas)
    for x_ratio in (0.28, 0.72):
        x = left + round(fossil.width * x_ratio)
        draw.line((x, top + round(fossil.height * 0.77), x, support_base), fill=(104, 76, 43, 230), width=3)
        draw.line((x + 2, top + round(fossil.height * 0.77), x + 2, support_base), fill=(196, 151, 84, 95), width=1)
    paste_with_shadow(canvas, fossil, left, top)
    return canvas.convert("RGB")


def render_excavation(template_path: Path, subject: Image.Image) -> Image.Image:
    canvas = Image.open(template_path).convert("RGBA").resize(
        (OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS
    )
    fossil = fitted(
        subject,
        round(OUTPUT_SIZE * EXCAVATION_SUBJECT_WIDTH_RATIO),
        round(OUTPUT_SIZE * EXCAVATION_SUBJECT_HEIGHT_RATIO),
    )
    left = (OUTPUT_SIZE - fossil.width) // 2
    top = (OUTPUT_SIZE - fossil.height) // 2
    paste_with_shadow(canvas, fossil, left, top)
    return canvas.convert("RGB")


def main() -> None:
    args = parse_args()
    if not args.artifact_id or any(character not in "abcdefghijklmnopqrstuvwxyz0123456789-" for character in args.artifact_id):
        raise SystemExit("artifact id must contain only lowercase letters, digits, and hyphens")
    subject = chroma_key(args.master)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    outputs = {
        args.output_dir / f"{args.artifact_id}-museum.jpg": render_museum(args.museum_template, subject),
        args.output_dir / f"{args.artifact_id}-excavation.jpg": render_excavation(
            args.excavation_template, subject
        ),
    }
    for path, image in outputs.items():
        image.save(path, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True, exif=b"")
        print(path)


if __name__ == "__main__":
    main()
