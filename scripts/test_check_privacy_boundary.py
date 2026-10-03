#!/usr/bin/env python3
"""Regression tests for the source-level notification privacy guard."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SCANNER = REPOSITORY_ROOT / "scripts" / "check_privacy_boundary.py"


class PrivacyBoundaryScannerTest(unittest.TestCase):
    def scan(self, root: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCANNER), "--root", str(root)],
            check=False,
            capture_output=True,
            text=True,
        )

    def write_source(self, root: Path, relative_path: str, content: str) -> Path:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_rejects_raw_notification_content_field_outside_reducer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.write_source(
                root,
                "android/app/src/main/java/com/example/domain/Unsafe.kt",
                "val copied = notification.title\n",
            )

            result = self.scan(root)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(source.relative_to(root).as_posix(), result.stderr)
            self.assertIn("notification content field", result.stderr)

    def test_rejects_raw_package_identity_outside_reducer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.write_source(
                root,
                "android/app/src/main/java/com/example/domain/Unsafe.kt",
                "val rawPackageName = sbn.packageName\n",
            )

            result = self.scan(root)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(source.relative_to(root).as_posix(), result.stderr)
            self.assertIn("raw package identity", result.stderr)

    def test_allows_reduced_fields_and_narrow_reducer_access(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_source(
                root,
                "android/app/src/main/java/com/example/domain/Reduced.kt",
                "val event = Reduced(bucket = 4, category = \"message\")\n",
            )
            self.write_source(
                root,
                "android/app/src/main/java/com/techfullymade/afterchime/capture/NotificationReducer.kt",
                "val source = sbn.packageName\n",
            )

            result = self.scan(root)

            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
