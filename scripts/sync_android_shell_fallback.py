#!/usr/bin/env python3
"""Copy the built hosted shell into Android's first-launch fallback."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "web" / "dist"
DEST = ROOT / "android" / "app" / "src" / "main" / "assets" / "web"
ANDROID_ASSETS = DEST.parent
ASSET_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css)$")
WORLD_ASSET_PATTERN = re.compile(r"^[a-z0-9-]{1,128}-[a-f0-9]{12}\.(?:jpg|webp)$")
CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; "
    "style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; "
    "object-src 'none'; base-uri 'none'; form-action 'none'"
)


def main() -> None:
    index_path = DIST / "index.html"
    assets_path = DIST / "assets"
    worlds_path = DIST / "worlds"
    if not index_path.is_file() or not assets_path.is_dir() or not worlds_path.is_dir():
        raise SystemExit("web/dist is missing; run pnpm web:build first")

    assets = sorted(path for path in assets_path.iterdir() if path.is_file())
    if not assets or any(not ASSET_PATTERN.fullmatch(path.name) for path in assets):
        raise SystemExit("web/dist/assets contains missing or non-content-addressed files")
    world_assets = sorted(path for path in worlds_path.iterdir() if path.is_file())
    if not world_assets or any(not WORLD_ASSET_PATTERN.fullmatch(path.name) for path in world_assets):
        raise SystemExit("web/dist/worlds contains missing or non-content-addressed files")

    html = index_path.read_text(encoding="utf-8")
    html = html.replace('src="/assets/', 'src="/assets/web/assets/')
    html = html.replace('href="/assets/', 'href="/assets/web/assets/')
    if 'id="root"' not in html or "/assets/web/assets/" not in html:
        raise SystemExit("built shell entry did not contain the expected root and hashed assets")
    html = html.replace(
        "<title>",
        f'<meta http-equiv="Content-Security-Policy" content="{CSP}"><title>',
        1,
    )

    destination_assets = DEST / "assets"
    if destination_assets.exists():
        shutil.rmtree(destination_assets)
    destination_assets.mkdir(parents=True, exist_ok=True)
    for asset in assets:
        shutil.copyfile(asset, destination_assets / asset.name)
    for stale_world_asset in ANDROID_ASSETS.iterdir():
        if stale_world_asset.is_file() and WORLD_ASSET_PATTERN.fullmatch(stale_world_asset.name):
            stale_world_asset.unlink()
    for world_asset in world_assets:
        shutil.copyfile(world_asset, ANDROID_ASSETS / world_asset.name)
    DEST.mkdir(parents=True, exist_ok=True)
    (DEST / "index.html").write_text(html, encoding="utf-8")

    referenced = set(re.findall(r"/assets/web/assets/([A-Za-z0-9_.-]+)", html))
    copied = {asset.name for asset in assets}
    if referenced != copied:
        raise SystemExit(f"fallback reference mismatch: referenced={sorted(referenced)} copied={sorted(copied)}")
    print(
        f"synced {len(copied)} shell assets and {len(world_assets)} content-addressed world images"
    )


if __name__ == "__main__":
    main()
