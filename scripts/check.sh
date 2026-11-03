#!/usr/bin/env bash
# Runs every currently available local quality gate. It intentionally fails when a prerequisite is absent.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./scripts/test_gradle_gateway_guard.sh
corepack pnpm install --frozen-lockfile
corepack pnpm privacy:test
corepack pnpm privacy:check
python3 -m unittest scripts/test_check_dev_feedback_exclusion.py
corepack pnpm format:check
corepack pnpm lint
corepack pnpm --filter @afterchime/web exec vitest run --pool=threads --maxWorkers=1 --no-file-parallelism
corepack pnpm --filter @afterchime/server exec vitest run --pool=threads --maxWorkers=1 --no-file-parallelism
corepack pnpm build
(
  cd android
  ./gradlew --no-daemon testDevDebugUnitTest lintDevDebug assembleDevDebug
)
