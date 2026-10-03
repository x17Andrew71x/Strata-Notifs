#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CGROUP_FILE="$(mktemp)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$CGROUP_FILE" "$OUTPUT_FILE"' EXIT

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
if pgrep -f 'org.gradle|GradleDaemon' >/dev/null 2>&1; then
  printf 'Gateway guard test unexpectedly left a Gradle process running.\n' >&2
  exit 1
fi

printf 'Gradle gateway guard passed.\n'