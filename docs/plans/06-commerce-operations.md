# 06 — Commerce and Owner Operations Implementation Plan

> **For Hermes:** Production billing and production deployment remain disabled. Development fakes must be compile-time separated and visually unmistakable.

**Goal:** Complete cosmetic commerce, decision-ready owner analytics and verified Railway development operations while creating an inert production environment.

**Architecture:** Play Billing is wrapped behind an entitlement boundary. Server verification is authoritative for online entitlement sync. Owner analytics read only derived views through separately authenticated routes. Railway development hosts API, worker and managed PostgreSQL.

**Milestone:** `0.7.0`

---

## Task 1: Product and entitlement schema

**Files:**
- Create: commerce schema/migration
- Create: `server/src/modules/commerce/catalog.ts`
- Create: `server/src/modules/commerce/entitlements.ts`
- Create: tests

Model product ID, world grants, active catalogue window, environment and verified entitlement. Test duplicate purchase, revoked/refunded purchase, bundle overlap and ownership merge. Currency/pricing display comes from Play, not server guesses.

## Task 2: Billing abstraction and dev provider

**Files:**
- Create: `android/.../commerce/BillingRepository.kt`
- Create: `.../commerce/PlayBillingRepository.kt`
- Create: `android/app/src/dev/.../DevBillingRepository.kt`
- Create: `android/app/src/prod/.../ProductionBillingBindings.kt`
- Create: tests

Test catalogue load, checkout result, pending purchase, acknowledgement, restore, disconnect/reconnect and entitlement application. Add a build test proving the dev provider/class cannot enter the prod artifact and production fails closed without verification configuration.

## Task 3: Purchase verification API

**Files:**
- Create: `server/src/modules/commerce/routes.ts`
- Create: `.../purchase-verifier.ts`
- Create: `.../google-play-verifier.ts`
- Create: tests/fakes

Keep purchase tokens out of analytics/logs. Development uses a bounded fake provider enabled only in the development environment. Production verifier requires Google credentials, package/product allowlists and acknowledgement state; absence is a hard failure.

## Task 4: Shop and world application UI

**Files:**
- Create/modify worlds/store screens and tests

Provide previews, owned state, bundle contents, restore and concise disclosures. No countdown pressure or fake discounts. Applying an owned world re-renders history; no inventory multiplication. Instrument the approved funnel only under consent.

## Task 5: Owner analytics views

**Files:**
- Add migrations for owner-safe views
- Create: `server/src/modules/admin/metrics.ts`
- Create: `server/test/admin/metrics.test.ts`
- Complete: `docs/DATA_DICTIONARY.md`

Expose activation, retention, permission, notification, collection, campaign, conversion, release-health and consent metrics. Enforce minimum cohort size where a dimension could expose one installation. No endpoint returns raw notification events or purchase tokens.

## Task 6: Owner authentication and audit

**Files:**
- Create: `server/src/security/admin-auth.ts`
- Create: admin audit schema/migration/tests

Use an environment-specific high-entropy credential or signed identity distinct from app auth, compare safely, rate-limit, produce secure session/audit records and redact credentials. Development owner credentials cannot authorize production. Mutating campaign operations require idempotency and an explicit environment assertion.

## Task 7: Railway account and cost preflight

**Steps:**
1. Verify current Railway authentication, workspace, plan/credits and existing project naming.
2. Record expected services and avoid duplicate resources.
3. Confirm managed PostgreSQL and compute costs before provisioning.
4. Create one project with isolated `development` and `production` environments.
5. Keep production services undeployed/inert.

If account access or a billable action cannot be verified, record the exact external blocker; do not substitute an unmanaged database.

## Task 8: Provision development PostgreSQL

**Steps:**
1. Add Railway's managed PostgreSQL template to development.
2. Create distinct migration and runtime roles.
3. Wire API/worker through environment references without printing URLs.
4. Apply committed migrations from a controlled command.
5. Read back migration head, constraints, RLS/force flags and runtime-role attestation.
6. Write a synthetic application fact, redeploy/restart, then read it through the API.

## Task 9: Deploy development API and worker

**Files:**
- Create: `server/Dockerfile`
- Create: `server/railway.toml` or repository-approved config
- Create: `docs/operations/RAILWAY.md`
- Create: smoke scripts/tests

Deploy from `dev` only after local gates pass. Configure API, worker/cron, development domain and environment variables. Verify liveness, readiness, version, migration head, job no-op, ingestion, rollup, campaign operation and restart recovery.

## Task 10: Backups and restoration rehearsal

Document Railway backup capability and application exports. Restore a development backup into scratch state, run integrity/migration checks and verify representative identity, analytics and campaign records without exposing rows in logs.

## Phase exit

- Dev fake billing and entitlement restore pass; prod fake path is absent.
- Owner metrics reconcile with seeded source facts and respect cohort safety.
- Railway development API/worker/PostgreSQL pass live synthetic persistence and restart proof.
- Production environment exists but has no approved deployment or shared secrets.
- Cost/resources and operations evidence are documented.
