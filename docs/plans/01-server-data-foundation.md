# 01 — Server Data Foundation Implementation Plan

> **For Hermes:** Use disposable real PostgreSQL for every migration and authorization claim. Never provision or mutate production.

**Goal:** Deliver safe pseudonymous identity, consent, strict analytics ingestion and committed PostgreSQL migrations.

**Architecture:** Fastify routes call explicit domain services and Drizzle repositories. Migrations run with an owner role; runtime uses a distinct non-owner role with forced RLS. Analytics events and business facts remain separate.

**Milestone:** `0.2.0`

---

## Task 1: Reproducible PostgreSQL test harness

**Files:**
- Create: `server/compose.test.yml`
- Create: `server/src/db/client.ts`
- Create: `server/src/db/migrate.ts`
- Create: `server/src/db/runtime-attestation.ts`
- Create: `server/test/support/postgres.ts`
- Create: `server/test/db/runtime-role.test.ts`

**Steps:**
1. Start pinned PostgreSQL in Docker with separate migration and runtime credentials.
2. Write a RED test that rejects runtime superuser, owner, `BYPASSRLS` or role-assumption paths.
3. Implement connection factories and startup attestation.
4. Prove test isolation with schema-per-suite or database-per-suite cleanup.
5. Record exact PostgreSQL version and migration head in readiness output.

## Task 2: Initial schema and forward migration

**Files:**
- Create: `server/src/db/schema/identity.ts`
- Create: `server/src/db/schema/analytics.ts`
- Create: `server/src/db/schema/operations.ts`
- Create: `server/drizzle.config.ts`
- Create: `server/migrations/0001_*.sql`
- Create: `server/test/db/migration.test.ts`

**Tables:** users, installations, auth refresh tokens, consent records, analytics events, daily notification aggregates, idempotency records, outbox jobs, job runs and schema metadata.

**Steps:**
1. Write migration tests asserting tables, columns, types, constraints and indexes.
2. Generate and manually inspect the migration.
3. Add immutable identifiers, timestamps and explicit build-channel/synthetic markers.
4. Enable/force RLS on user-owned rows; grant only required runtime operations.
5. Apply from empty database and read back exact policies and constraints.
6. Reapply/no-op and prove migration history integrity.

## Task 3: Anonymous installation registration

**Files:**
- Create: `server/src/modules/identity/routes.ts`
- Create: `server/src/modules/identity/service.ts`
- Create: `server/src/modules/identity/repository.ts`
- Create: `server/src/security/tokens.ts`
- Create: `server/test/api/installations.test.ts`

**TDD cases:**
- valid registration returns installation identity, short-lived access token and one-time refresh secret;
- refresh secret is stored only as a hash;
- duplicate idempotency key returns the original installation;
- malformed device metadata and oversized inputs are rejected;
- registration rate limit activates;
- production audience cannot be issued from dev configuration.

Implement rotating refresh-token families, replay detection, revocation and logout. No email, name, advertising ID or hardware identifier is accepted.

## Task 4: Consent history

**Files:**
- Create: `server/src/modules/consent/routes.ts`
- Create: `server/src/modules/consent/service.ts`
- Create: `server/test/api/consent.test.ts`

**Steps:**
1. Test versioned consent grants and withdrawals for essential online, product analytics and notification aggregates.
2. Reject analytics ingestion without an active matching consent version.
3. Preserve auditable history while exposing only current state to ordinary clients.
4. Verify one installation cannot change another's consent.

## Task 5: Strict analytics batch ingestion

**Files:**
- Create: `server/src/modules/analytics/routes.ts`
- Create: `server/src/modules/analytics/registry.ts`
- Create: `server/src/modules/analytics/service.ts`
- Create: `server/src/modules/analytics/repository.ts`
- Create: `server/test/api/analytics-ingest.test.ts`

**TDD cases:** accepted batch, duplicate retry, mixed duplicate/new batch, unknown event, unknown schema version, prohibited property, free-form text, invalid clock, overlarge batch, wrong consent, wrong installation and synthetic marker enforcement.

Use one transaction per bounded batch. Return per-event accepted/duplicate/rejected status without reflecting sensitive input.

## Task 6: Notification aggregate ingestion

**Files:**
- Create: `server/src/modules/notifications/routes.ts`
- Create: `server/src/modules/notifications/service.ts`
- Create: `server/test/api/notification-aggregates.test.ts`

Accept daily/hourly approved aggregates only: count, observed buckets, coarse category counts, capped game-pressure value, observation completeness and generator rules version. Reject source tokens, colours, package names and content. Prove idempotent replacement uses an explicit revision and cannot double-count.

## Task 7: Aggregation jobs

**Files:**
- Create: `server/src/jobs/runner.ts`
- Create: `server/src/jobs/analytics-daily.ts`
- Create: `server/src/db/schema/analytics-marts.ts`
- Create: `server/migrations/0002_*.sql`
- Create: `server/test/jobs/analytics-daily.test.ts`

Build checkpointed, idempotent rollups for daily global, installation, consent coverage, release health and notification volume. Reconcile source and aggregate totals, record every run, and fail visibly on drift.

## Task 8: Deletion and retention

**Files:**
- Create: `server/src/modules/account/routes.ts`
- Create: `server/src/modules/account/deletion.ts`
- Create: `server/src/jobs/retention.ts`
- Create: `server/test/api/account-deletion.test.ts`
- Create: `server/test/jobs/retention.test.ts`

Prove account deletion removes directly linked events, tokens and installation rows while preserving only irreversible global rollups. Retention deletes/anonymises expired raw data in bounded chunks and is safe to resume.

## Phase exit

Run:
- server format/lint/typecheck/unit suite;
- disposable PostgreSQL migration tests;
- runtime-role/RLS denial suite;
- duplicate/replay and deletion tests;
- dependency production audit.

Read back migration head and schema from the exercised database. No Railway service is required yet.
