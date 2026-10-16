#!/usr/bin/env python3
"""Fail when production artifacts retain development-feedback implementation markers."""
from __future__ import annotations

import argparse
import pathlib
import sys
import zipfile

MARKERS = (
    b"devFeedback",
    b"dev-feedback",
    b"DevFeedback",
    b"development_feedback_identity",
    b"/v1/dev-feedback",
)
TEXT_SUFFIXES = {".css", ".html", ".js", ".json", ".map", ".sql", ".xml"}


def artifact_bytes(root: pathlib.Path) -> bytes:
    if not root.is_dir():
        raise ValueError(f"artifact directory is missing: {root}")
    chunks: list[bytes] = []
    for path in sorted(root.rglob("*")):
        if path.is_file() and not path.is_symlink() and path.suffix in TEXT_SUFFIXES:
            chunks.append(path.read_bytes())
    return b"\n".join(chunks)


def apk_bytes(path: pathlib.Path) -> bytes:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"production APK is missing: {path}")
    chunks: list[bytes] = []
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            suffix = pathlib.PurePosixPath(name).suffix
            if name.endswith(".dex") or suffix in TEXT_SUFFIXES or name.startswith("assets/"):
                chunks.append(archive.read(name))
    return b"\n".join(chunks)


def reject_markers(label: str, content: bytes) -> list[str]:
    return [f"{label} contains {marker.decode('ascii')}" for marker in MARKERS if marker in content]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--web", required=True, type=pathlib.Path)
    parser.add_argument("--server", required=True, type=pathlib.Path)
    parser.add_argument("--migrations", required=True, type=pathlib.Path)
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    args = parser.parse_args()
    errors: list[str] = []
    try:
        errors.extend(reject_markers("web production artifact", artifact_bytes(args.web)))
        module = args.server / "modules" / "dev-feedback"
        if module.exists():
            errors.append("server production artifact contains the development-feedback module")
        errors.extend(reject_markers("server production artifact", artifact_bytes(args.server)))
        errors.extend(reject_markers("server production migrations", artifact_bytes(args.migrations)))
        errors.extend(reject_markers("Android production artifact", apk_bytes(args.apk)))
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        errors.append(str(exc))
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("Development-feedback production exclusion check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
