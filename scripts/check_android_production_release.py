#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import stat
import subprocess
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

PACKAGE_ID = "com.techfullymade.afterchime"
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
FORBIDDEN_COMPILED_MARKERS = (
    b"afterchime-api-dev-development.up.railway.app",
    b"10.0.2.2",
    b"shell-disabled.invalid",
    b"production-disabled.invalid",
    b"dev_feedback",
    b"devFeedback",
    b"DevFeedback",
    b"DevelopmentFeedback",
    b"dev-feedback",
)


def normalized_https_origin(value: str, *, trailing_slash: bool) -> str:
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise ValueError(f"invalid production origin: {value!r}") from error
    expected_path = "/" if trailing_slash else ""
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or port is not None
        or parsed.path != expected_path
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(f"production origin must be an exact HTTPS origin: {value!r}")
    return value


def parse_signer_sha256(output: str) -> str:
    match = re.search(
        r"(?:certificate SHA-256 digest|SHA256):\s*([0-9A-Fa-f:]+)",
        output,
    )
    if not match:
        raise ValueError("signer output did not contain a certificate SHA-256 digest")
    digest = match.group(1).replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("apksigner returned an invalid certificate SHA-256 digest")
    return digest


def validate_zip(path: Path) -> list[str]:
    if not path.is_file() or path.stat().st_size <= 0:
        raise ValueError(f"artifact is missing or empty: {path}")
    with zipfile.ZipFile(path) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f"corrupt ZIP member in {path}: {corrupt}")
        names: list[str] = []
        for info in archive.infolist():
            member = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            if (
                member.is_absolute()
                or ".." in member.parts
                or "\\" in info.filename
                or stat.S_ISLNK(mode)
            ):
                raise ValueError(f"unsafe ZIP member in {path}: {info.filename}")
            names.append(info.filename)
    return names


def compiled_dex_payloads(path: Path) -> list[bytes]:
    validate_zip(path)
    with zipfile.ZipFile(path) as archive:
        payloads = [archive.read(name) for name in archive.namelist() if name.endswith(".dex")]
    if not payloads:
        raise ValueError(f"artifact contains no compiled DEX payload: {path}")
    return payloads


def scan_compiled_release(payloads: list[bytes], api_base_url: str, shell_url: str) -> None:
    for expected in (api_base_url.encode(), shell_url.encode()):
        if not any(expected in payload for payload in payloads):
            raise ValueError(f"compiled release is missing expected production origin: {expected.decode()}")
    for marker in FORBIDDEN_COMPILED_MARKERS:
        if any(marker in payload for payload in payloads):
            raise ValueError(f"compiled release contains forbidden marker: {marker.decode()}")


def command_output(arguments: list[str]) -> str:
    result = subprocess.run(arguments, check=False, capture_output=True, text=True, timeout=180)
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise ValueError(f"command failed ({arguments[0]}, exit {result.returncode}): {detail[:500]}")
    return result.stdout


def verify_release(args: argparse.Namespace) -> dict[str, str | int]:
    apk = Path(args.apk).resolve()
    bundle = Path(args.bundle).resolve()
    api_base_url = normalized_https_origin(args.api_base_url, trailing_slash=False)
    shell_url = normalized_https_origin(args.shell_url, trailing_slash=True)
    expected_cert = args.expected_cert_sha256.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
        raise ValueError("expected signer certificate SHA-256 must contain exactly 64 hexadecimal digits")

    validate_zip(apk)
    validate_zip(bundle)
    application_id = command_output([args.apkanalyzer, "manifest", "application-id", str(apk)]).strip()
    version_code_text = command_output([args.apkanalyzer, "manifest", "version-code", str(apk)]).strip()
    version_name = command_output([args.apkanalyzer, "manifest", "version-name", str(apk)]).strip()
    if application_id != PACKAGE_ID:
        raise ValueError(f"unexpected production package ID: {application_id}")
    if not version_code_text.isdigit() or int(version_code_text) <= 0:
        raise ValueError(f"invalid production version code: {version_code_text}")
    if not SEMVER.fullmatch(version_name):
        raise ValueError(f"invalid production version name: {version_name}")

    signer_output = command_output([args.apksigner, "verify", "--verbose", "--print-certs", str(apk)])
    actual_cert = parse_signer_sha256(signer_output)
    if actual_cert != expected_cert:
        raise ValueError(f"production signer mismatch: expected {expected_cert}, received {actual_cert}")
    command_output([args.zipalign, "-c", "-P", "16", "-v", "4", str(apk)])
    bundle_verification = command_output([args.jarsigner, "-verify", str(bundle)])
    if "jar verified." not in bundle_verification.lower():
        raise ValueError("App Bundle signature did not verify")
    bundle_cert = parse_signer_sha256(
        command_output([args.keytool, "-printcert", "-jarfile", str(bundle)])
    )
    if bundle_cert != expected_cert:
        raise ValueError(
            f"App Bundle signer mismatch: expected {expected_cert}, received {bundle_cert}"
        )

    scan_compiled_release(compiled_dex_payloads(apk), api_base_url, shell_url)
    scan_compiled_release(compiled_dex_payloads(bundle), api_base_url, shell_url)
    return {
        "applicationId": application_id,
        "versionCode": int(version_code_text),
        "versionName": version_name,
        "signerSha256": actual_cert,
        "apiBaseUrl": api_base_url,
        "shellUrl": shell_url,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify a signed Afterchime production APK and App Bundle.")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--api-base-url", required=True)
    parser.add_argument("--shell-url", required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--zipalign", default="zipalign")
    parser.add_argument("--jarsigner", default="jarsigner")
    parser.add_argument("--keytool", default="keytool")
    return parser.parse_args()


def main() -> int:
    try:
        receipt = verify_release(parse_args())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Production Android release verification failed: {error}")
        return 1
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
