#!/usr/bin/env sh
set -eu

: "${AFTERCHIME_DEV_MIGRATOR_PASSWORD:?missing migrator password}"
: "${AFTERCHIME_DEV_RUNTIME_PASSWORD:?missing runtime password}"

psql --username "$POSTGRES_USER" --dbname postgres \
  --set=ON_ERROR_STOP=1 \
  --set=migrator_password="$AFTERCHIME_DEV_MIGRATOR_PASSWORD" \
  --set=runtime_password="$AFTERCHIME_DEV_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE afterchime_migrator
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION
  PASSWORD :'migrator_password';
CREATE ROLE afterchime_runtime
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION
  PASSWORD :'runtime_password';
CREATE DATABASE afterchime OWNER afterchime_migrator;
REVOKE ALL ON DATABASE afterchime FROM PUBLIC;
GRANT CONNECT ON DATABASE afterchime TO afterchime_runtime;
SQL
