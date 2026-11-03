#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("check_dev_feedback_exclusion.py")
SPEC = importlib.util.spec_from_file_location("check_dev_feedback_exclusion", MODULE_PATH)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DevelopmentFeedbackExclusionTests(unittest.TestCase):
    def test_rejects_runtime_migration_identifiers(self) -> None:
        errors = MODULE.reject_markers(
            "server production migrations",
            b'CREATE TABLE "dev_feedback_items" (id uuid);',
        )
        self.assertEqual(
            errors,
            ["server production migrations contains dev_feedback"],
        )


if __name__ == "__main__":
    unittest.main()
