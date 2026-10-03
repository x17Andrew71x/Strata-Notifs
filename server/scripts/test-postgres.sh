#!/usr/bin/env bash
set -Eeuo pipefail

server_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$server_root"

if command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
elif docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
else
  printf '%s\n' 'Docker Compose is required for PostgreSQL integration tests.' >&2
  exit 1
fi

project="afterchime-test-${RANDOM}${RANDOM}"
env_file="$(mktemp)"
password="$(openssl rand -hex 32)"
chmod 600 "$env_file"
printf 'AFTERCHIME_TEST_POSTGRES_PASSWORD=%s\n' "$password" > "$env_file"

cleanup() {
  "${compose[@]}" --env-file "$env_file" -f compose.test.yml -p "$project" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$env_file"
}
trap cleanup EXIT

"${compose[@]}" --env-file "$env_file" -f compose.test.yml -p "$project" up -d
container="$("${compose[@]}" --env-file "$env_file" -f compose.test.yml -p "$project" ps -q postgres)"
for _ in $(seq 1 30); do
  health="$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
  if [ "$health" = "healthy" ]; then
    break
  fi
  sleep 1
done

if [ "${health:-}" != "healthy" ]; then
  printf '%s\n' 'PostgreSQL test container did not become healthy.' >&2
  exit 1
fi

binding_host="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostIp}}' "$container")"
if [ "$binding_host" != "127.0.0.1" ]; then
  printf '%s\n' 'PostgreSQL test endpoint was not loopback.' >&2
  exit 1
fi

endpoint="$("${compose[@]}" --env-file "$env_file" -f compose.test.yml -p "$project" port postgres 5432)"
port="${endpoint##*:}"
case "$port" in
  '' | *[!0-9]*)
    printf '%s\n' 'PostgreSQL test endpoint did not contain a valid port.' >&2
    exit 1
    ;;
esac

export AFTERCHIME_TEST_ADMIN_DATABASE_URL="postgresql://postgres:${password}@127.0.0.1:${port}/postgres"
export AFTERCHIME_TEST_CLUSTER_OWNED=true
pnpm exec vitest run test/db/runtime-role.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/db/migration.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/jobs/analytics-daily.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/jobs/retention.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/api/installations.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/api/account-deletion.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/api/consent.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/api/analytics-ingest.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
pnpm exec vitest run test/api/notification-aggregates.test.ts --pool=threads --maxWorkers=1 --no-file-parallelism
