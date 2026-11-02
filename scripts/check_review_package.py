#!/usr/bin/env python3
"""Verify the committed Afterchime development review package."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def command(*arguments: str) -> str:
    result = subprocess.run(arguments, check=True, capture_output=True, text=True)
    return result.stdout


def newest_tool(directory: Path, executable: str) -> Path:
    candidates = sorted(directory.glob(f"*/{executable}"))
    if not candidates:
        raise RuntimeError(f"Android tool is missing: {executable}")
    return candidates[-1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, type=Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--signer-sha256", required=True)
    parser.add_argument("--api-base", required=True)
    parser.add_argument("--shell-url", required=True)
    args = parser.parse_args()

    android_home = Path(os.environ.get("ANDROID_HOME", ""))
    if not android_home.is_dir():
        raise RuntimeError("ANDROID_HOME is unavailable")
    apksigner = newest_tool(android_home / "build-tools", "apksigner")
    zipalign = newest_tool(android_home / "build-tools", "zipalign")
    apkanalyzers = sorted((android_home / "cmdline-tools").glob("*/bin/apkanalyzer"))
    if not apkanalyzers:
        raise RuntimeError("Android tool is missing: apkanalyzer")
    apkanalyzer = apkanalyzers[-1]

    with zipfile.ZipFile(args.zip) as package:
        entries = package.namelist()
        if len(entries) != 1 or not entries[0].endswith(".apk") or package.testzip() is not None:
            raise RuntimeError("review package must contain exactly one intact APK")
        with tempfile.TemporaryDirectory(prefix="afterchime-review-") as temporary:
            apk = Path(temporary) / Path(entries[0]).name
            apk.write_bytes(package.read(entries[0]))

            signature = command(str(apksigner), "verify", "--verbose", "--print-certs", str(apk))
            digest_match = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", signature)
            if not digest_match or digest_match.group(1) != args.signer_sha256:
                raise RuntimeError("review package signer does not match the established development key")
            subprocess.run(
                [str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)],
                check=True,
                stdout=subprocess.DEVNULL,
            )

            manifest = ET.fromstring(command(str(apkanalyzer), "manifest", "print", str(apk)))
            application = manifest.find("application")
            if application is None:
                raise RuntimeError("review APK application manifest is missing")
            expected_manifest = {
                "package": args.package,
                "versionCode": args.version_code,
                "versionName": args.version_name,
                "debuggable": "false",
                "usesCleartextTraffic": "false",
            }
            actual_manifest = {
                "package": manifest.attrib.get("package"),
                "versionCode": manifest.attrib.get(f"{ANDROID_NS}versionCode"),
                "versionName": manifest.attrib.get(f"{ANDROID_NS}versionName"),
                "debuggable": application.attrib.get(f"{ANDROID_NS}debuggable", "false"),
                "usesCleartextTraffic": application.attrib.get(
                    f"{ANDROID_NS}usesCleartextTraffic", "default"
                ),
            }
            if actual_manifest != expected_manifest:
                raise RuntimeError(f"review APK manifest mismatch: {actual_manifest}")
            if f"{ANDROID_NS}networkSecurityConfig" not in application.attrib:
                raise RuntimeError("review APK network security policy is missing")

            build_config = command(
                str(apkanalyzer),
                "dex",
                "code",
                "--class",
                "com.techfullymade.afterchime.BuildConfig",
                str(apk),
            )
            expected_fields = [
                f'API_BASE_URL:Ljava/lang/String; = "{args.api_base}"',
                'BUILD_TYPE:Ljava/lang/String; = "review"',
                "DEBUG:Z = false",
                "DEV_FEEDBACK_ENABLED:Z = true",
                'FLAVOR:Ljava/lang/String; = "dev"',
                "PRODUCTION_ENABLED:Z = false",
                f'SHELL_URL:Ljava/lang/String; = "{args.shell_url}"',
                f'VERSION_NAME:Ljava/lang/String; = "{args.version_name}"',
            ]
            if not all(field in build_config for field in expected_fields):
                raise RuntimeError("review APK BuildConfig is not the approved development configuration")
            if "10.0.2.2" in build_config or "shell-disabled.invalid" in build_config:
                raise RuntimeError("review APK retained an inert local Gradle default")

    print("Afterchime review package verification passed.")


if __name__ == "__main__":
    main()
