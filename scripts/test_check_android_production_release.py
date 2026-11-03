from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = pathlib.Path(__file__).with_name("check_android_production_release.py")
SPEC = importlib.util.spec_from_file_location("check_android_production_release", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ProductionReleaseVerifierTest(unittest.TestCase):
    def test_accepts_exact_https_production_origins(self) -> None:
        self.assertEqual(
            MODULE.normalized_https_origin("https://api.afterchime.example", trailing_slash=False),
            "https://api.afterchime.example",
        )
        self.assertEqual(
            MODULE.normalized_https_origin("https://app.afterchime.example/", trailing_slash=True),
            "https://app.afterchime.example/",
        )

    def test_rejects_ambiguous_or_unsafe_origins(self) -> None:
        for value, trailing_slash in (
            ("http://api.afterchime.example", False),
            ("https://user@api.afterchime.example", False),
            ("https://api.afterchime.example/v1", False),
            ("https://api.afterchime.example?debug=true", False),
            ("https://app.afterchime.example", True),
            ("https://app.afterchime.example/#fragment", True),
        ):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.normalized_https_origin(value, trailing_slash=trailing_slash)

    def test_parses_apksigner_certificate_digest(self) -> None:
        digest = "ab" * 32
        self.assertEqual(
            MODULE.parse_signer_sha256(f"Signer #1 certificate SHA-256 digest: {digest}\n"),
            digest,
        )
        self.assertEqual(
            MODULE.parse_signer_sha256(f"V2 Signer: certificate SHA-256 digest: {digest}\n"),
            digest,
        )
        self.assertEqual(
            MODULE.parse_signer_sha256(f"SHA256: {':'.join([digest[index:index + 2] for index in range(0, 64, 2)])}\n"),
            digest,
        )
        with self.assertRaises(ValueError):
            MODULE.parse_signer_sha256("Verifies\n")

    def test_compiled_release_requires_prod_urls_and_rejects_retired_markers(self) -> None:
        api = "https://api.afterchime.example"
        shell = "https://app.afterchime.example/"
        MODULE.scan_compiled_release([f"prefix\x00{api}\x00{shell}\x00suffix".encode()], api, shell)
        for forbidden in (
            "https://production-disabled.invalid/",
            "https://afterchime-api-dev-development.up.railway.app/",
            "devFeedback",
            "DevelopmentFeedback",
        ):
            with self.subTest(forbidden=forbidden):
                with self.assertRaises(ValueError):
                    MODULE.scan_compiled_release([f"{api}\x00{shell}\x00{forbidden}".encode()], api, shell)

    def test_zip_validation_rejects_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            safe = Path(directory) / "safe.zip"
            with zipfile.ZipFile(safe, "w") as archive:
                archive.writestr("base/dex/classes.dex", b"dex")
            self.assertEqual(MODULE.validate_zip(safe), ["base/dex/classes.dex"])

            unsafe = Path(directory) / "unsafe.zip"
            with zipfile.ZipFile(unsafe, "w") as archive:
                archive.writestr("../escape", b"bad")
            with self.assertRaises(ValueError):
                MODULE.validate_zip(unsafe)


if __name__ == "__main__":
    unittest.main()
