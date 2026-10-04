#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CGROUP_FILE="$(mktemp)"
OUTPUT_FILE="$(mktemp)"
BEFORE_PIDS="$(mktemp)"
AFTER_PIDS="$(mktemp)"
trap 'rm -f "$CGROUP_FILE" "$OUTPUT_FILE" "$BEFORE_PIDS" "$AFTER_PIDS"' EXIT

pgrep -f 'org.gradle|GradleDaemon' | sort -n > "$BEFORE_PIDS" || true

printf '0::/user.slice/user-1001.slice/user@1001.service/app.slice/hermes-gateway.service\n' > "$CGROUP_FILE"

set +e
AFTERCHIME_CGROUP_FILE="$CGROUP_FILE" "$ROOT/android/gradlew" --version > "$OUTPUT_FILE" 2>&1
status=$?
set -e

if [[ "$status" -ne 75 ]]; then
  printf 'Expected gateway Gradle guard to exit 75, got %s.\n' "$status" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

grep -Fq 'Refusing to run Gradle inside hermes-gateway.service' "$OUTPUT_FILE"
pgrep -f 'org.gradle|GradleDaemon' | sort -n > "$AFTER_PIDS" || true
if [[ -n "$(comm -13 "$BEFORE_PIDS" "$AFTER_PIDS")" ]]; then
  printf 'Gateway guard test unexpectedly left a Gradle process running.\n' >&2
  exit 1
fi

printf 'Gradle gateway guard passed.\n'