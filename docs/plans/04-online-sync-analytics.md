# 04 — Online Sync and Analytics Implementation Plan

> **For Hermes:** Online capability is optional. Analytics consent and notification-aggregate consent are independent; local gameplay must remain complete when both are absent.

**Goal:** Connect the Android app to pseudonymous development services with safe credential storage, consent-aware batching, specimen registration and reliable reconciliation.

**Architecture:** Android keeps durable Room outboxes for analytics and domain mutations. Short-lived access tokens and rotating refresh credentials authorize API calls. Server remains authoritative only for online facts.

**Milestone:** `0.5.0`

---

## Task 1: Typed Android API boundary

**Files:**
- Create: `android/app/src/main/java/.../network/QuietStrataApi.kt`
- Create: `.../network/ApiModels.kt`
- Create: `.../network/NetworkModule.kt`
- Create: `android/app/src/test/.../network/ContractTest.kt`

Generate or hand-maintain typed models checked against shared fixtures. Enforce HTTPS outside dev loopback, bounded timeouts, no body logging and redacted errors. Test old/new schema compatibility and unknown-version handling.

## Task 2: Anonymous online identity

**Files:**
- Create: `.../identity/OnlineIdentityRepository.kt`
- Create: `.../identity/CredentialStore.kt`
- Create: `.../identity/TokenAuthenticator.kt`
- Create: tests

Test first registration, idempotent retry, access refresh, refresh rotation, replay/revocation response, logout and Keystore failure. Never place tokens in DataStore, logs, analytics or crash text.

## Task 3: Analytics event registry

**Files:**
- Create: `.../analytics/AnalyticsEvent.kt`
- Create: `.../analytics/AnalyticsRegistry.kt`
- Create: `.../analytics/AnalyticsRecorder.kt`
- Create: `.../data/local/AnalyticsOutboxEntity.kt`
- Create: tests

Implement explicit typed events from the canonical catalogue. No `Map<String, Any>`, arbitrary string property or notification-derived source value is permitted. Tests compare every emitted payload with shared schemas and consent scope.

## Task 4: Session and interaction instrumentation

**Files:**
- Modify relevant ViewModels/use cases only through injected recorder
- Create: `.../analytics/SessionTracker.kt`
- Create: instrumentation contract tests

Record lifecycle, onboarding, permission, navigation, reveal, museum, combine, world and share events. Test one action produces one expected event; recomposition and retry do not duplicate semantic interactions. Operational domain success is not inferred from a UI tap.

## Task 5: Analytics upload worker

**Files:**
- Create: `.../analytics/AnalyticsUploadWorker.kt`
- Create: `.../analytics/AnalyticsSyncRepository.kt`
- Create: tests

Batch bounded rows, respect consent at send time, delete only accepted/duplicate rows, quarantine permanent schema rejection and retry transient failures with backoff. Test crash after server commit, mixed responses, consent withdrawal and offline operation.

## Task 6: Notification aggregate uploader

**Files:**
- Create: `.../sync/NotificationAggregateWorker.kt`
- Create: tests

Upload true counts and capped game pressure only under matching consent. Never upload local source tokens/colours. Revisions replace rather than add to prevent global double-counting. Test late day correction and withdrawal.

## Task 7: Specimen registration and sync

**Files:**
- Create: server specimen schema/migration/module/tests
- Create: `android/.../sync/SpecimenSyncRepository.kt`
- Create: `android/.../data/local/DomainOutboxEntity.kt`
- Create: contract fixtures

Register only specimens needed for online donation/award provenance. Server verifies generator version and integrity proof without receiving raw day events. Test idempotent registration, ownership denial, forged tier rejection and local-only specimen privacy.

## Task 8: Sync cursor and awards shell

**Files:**
- Create: `server/src/modules/sync/*`
- Create: `android/.../sync/SyncWorker.kt`
- Create: tests

Return ordered server facts with opaque cursor: entitlement changes, campaign summaries and awards. Apply locally in one transaction, acknowledge only after commit, and survive duplicate pages/restart.

## Task 9: Derived product marts

**Files:**
- Extend analytics migrations/jobs/data dictionary
- Create: `server/test/jobs/product-marts.test.ts`

Add activation, retention, permission funnel, specimen distribution, collection progress, combine usage, world interest and release-health rollups. Seed synthetic events and reconcile exact expected results. Synthetic rows must remain separable from ordinary development analysis.

## Phase exit

- Local-only mode passes with network disabled.
- Each consent combination is covered.
- Registration/refresh/revocation and batch retry pass.
- Interaction events conform to strict contracts and do not duplicate on recomposition.
- Raw and derived analytics reconcile in real PostgreSQL.
- Android/server broad gates pass.
