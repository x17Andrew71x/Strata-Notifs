#!/usr/bin/env python3
"""Fail closed when release source references prohibited notification fields."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

SOURCE_ROOTS = ("android/app/src/main", "server/src", "contracts", ".github")
TEXT_EXTENSIONS = {".cjs", ".js", ".json", ".kts", ".kt", ".mjs", ".properties", ".py", ".sh", ".ts", ".tsx", ".xml", ".yaml", ".yml"}
REDUCER_ALLOWLIST = Path(
    "android/app/src/main/java/com/techfullymade/stratawake/capture/NotificationReducer.kt"
)


@dataclass(frozen=True)
class Rule:
    label: str
    pattern: re.Pattern[str]


RULES = (
    Rule(
        "notification content field",
        re.compile(
            r"\b(?:notification|sbn)\s*\.\s*(?:title|text|tickerText|extras|actions|remoteInput|media|messages)\b"
            r"|\b(?:notification|sbn)\s*\.\s*notification\s*\.\s*(?:title|text|tickerText|extras|actions|remoteInput|media|messages)\b"
            r"|\bnotification(?:Title|Text|Body|Sender|Extras|Actions|Media|Messages)\b",
            re.IGNORECASE,
        ),
    ),
    Rule(
        "raw package identity",
        re.compile(
            r"\b(?:rawPackageName|notificationPackageName)\b"
            r"|\b(?:notification|sbn)\s*\.\s*packageName\b"
            r"|\bgetPackageName\s*\(",
            re.IGNORECASE,
        ),
    ),
    Rule(
        "notification key",
        re.compile(
            r"\b(?:notificationKey|rawNotificationKey)\b"
            r"|\b(?:notification|sbn)\s*\.\s*key\b"
            r"|\bgetKey\s*\(",
            re.IGNORECASE,
        ),
    ),
)


def release_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for source_root in SOURCE_ROOTS:
        candidate = root / source_root
        if not candidate.exists():
            continue
        for path in candidate.rglob("*"):
            if path.is_file() and path.suffix.lower() in TEXT_EXTENSIONS:
                files.append(path)
    return sorted(files)


def relative(root: Path, path: Path) -> Path:
    return path.resolve().relative_to(root.resolve())


def violations(root: Path) -> list[str]:
    findings: list[str] = []
    for path in release_files(root):
        rel = relative(root, path)
        if rel == REDUCER_ALLOWLIST:
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            findings.append(f"{rel}: unreadable non-UTF-8 release source")
            continue
        for line_number, line in enumerate(lines, start=1):
            for rule in RULES:
                if rule.pattern.search(line):
                    findings.append(f"{rel}:{line_number}: prohibited {rule.label}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="repository root to scan")
    args = parser.parse_args()
    root = args.root.resolve()
    findings = violations(root)
    if findings:
        print("Notification privacy boundary check failed:", file=sys.stderr)
        print("\\n".join(findings), file=sys.stderr)
        return 1
    print("Notification privacy boundary check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
