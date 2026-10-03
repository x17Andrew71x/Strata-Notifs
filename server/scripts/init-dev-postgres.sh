#!/usr/bin/env sh
set -eu

: "${STRATAWAKE_DEV_MIGRATOR_PASSWORD:?missing migrator password}"
: "${STRATAWAKE_DEV_RUNTIME_PASSWORD:?missing runtime password}"

psql --username "$POSTGRES_USER" --dbname postgres \
  --set=ON_ERROR_STOP=1 \
  --set=migrator_password="$STRATAWAKE_DEV_MIGRATOR_PASSWORD" \
  --set=runtime_password="$STRATAWAKE_DEV_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE stratawake_migrator
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION
  PASSWORD :'migrator_password';
CREATE ROLE stratawake_runtime
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION
  PASSWORD :'runtime_password';
CREATE DATABASE stratawake OWNER stratawake_migrator;
REVOKE ALL ON DATABASE stratawake FROM PUBLIC;
GRANT CONNECT ON DATABASE stratawake TO stratawake_runtime;
SQL
