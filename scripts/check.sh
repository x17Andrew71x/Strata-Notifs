#!/usr/bin/env bash
# Runs every currently available local quality gate. It intentionally fails when a prerequisite is absent.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

corepack pnpm install --frozen-lockfile
corepack pnpm privacy:test
corepack pnpm privacy:check
corepack pnpm format:check
corepack pnpm lint
corepack pnpm test
corepack pnpm build
(
  cd android
  ./gradlew --no-daemon testDevDebugUnitTest lintDevDebug assembleDevDebug
)
