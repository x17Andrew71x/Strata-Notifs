# 02 — Android Capture and Generator Implementation Plan

> **For Hermes:** The privacy reducer is a hard boundary. Test prohibited notification fields before integrating UI or networking.

**Goal:** Observe notifications locally, reduce them to approved fields, seal days reliably and create deterministic specimens offline.

**Architecture:** A very narrow notification adapter maps Android objects directly into an immutable reduced event. Room persists only reduced events and derived day summaries. Pure Kotlin generator code consumes summaries and emits versioned specimens.

**Milestone:** `0.3.0`

---

## Task 1: Notification privacy reducer

**Files:**
- Create: `android/app/src/main/java/.../capture/NotificationReducer.kt`
- Create: `android/app/src/main/java/.../capture/ReducedNotification.kt`
- Create: `android/app/src/test/.../capture/NotificationReducerTest.kt`
- Create: `android/app/src/test/.../capture/ProhibitedFieldRegressionTest.kt`

**RED fixtures:** notifications containing titles, text, sender, messages, images, actions, remote input, raw extras, group summary, ongoing/system noise and maliciously large values.

**Implementation:** output only occurred timestamp, local time bucket, approved coarse category and HMAC-derived local source token/colour. Never expose raw package name beyond the reducer call stack. Filter own app, group summaries and configured excluded classes.

**Acceptance:** serialised/persisted models have no content-bearing fields; scans and tests prove fixtures cannot leak into database/log representations.

## Task 2: Local database and migrations

**Files:**
- Create: `android/app/src/main/java/.../data/local/AfterchimeDatabase.kt`
- Create: `android/app/src/main/java/.../data/local/entity/ReducedNotificationEntity.kt`
- Create: `android/app/src/main/java/.../data/local/entity/DaySummaryEntity.kt`
- Create: `android/app/src/main/java/.../data/local/entity/SpecimenEntity.kt`
- Create: `android/app/src/main/java/.../data/local/dao/*.kt`
- Create: `android/app/src/androidTest/.../DatabaseMigrationTest.kt`

Define only approved fields. Add unique day/specimen keys, generator version and observation state. Test CRUD, transaction rollback and migration identity.

## Task 3: Listener service and observation state

**Files:**
- Create: `android/app/src/main/java/.../capture/StrataNotificationListenerService.kt`
- Modify: Android manifest
- Create: `android/app/src/main/java/.../capture/ObservationRepository.kt`
- Create: `android/app/src/test/.../capture/ObservationRepositoryTest.kt`

Record when access is active, disconnected or revoked so missing permission is distinct from a quiet day. Handler work must be bounded and move persistence off the main callback. No network call occurs per notification.

## Task 4: Day summary calculation

**Files:**
- Create: `android/app/src/main/java/.../generation/DaySummary.kt`
- Create: `android/app/src/main/java/.../generation/DaySummarizer.kt`
- Create: `android/app/src/test/.../generation/DaySummarizerTest.kt`

Test empty observed, unobserved, sparse, repetitive, bursty, diverse, DST-forward, DST-back and travel fixtures. Apply per-source/time-window game caps while preserving true local counts separately.

## Task 5: Deterministic generator

**Files:**
- Create: `android/app/src/main/java/.../generation/GeneratorV1.kt`
- Create: `android/app/src/main/java/.../generation/Specimen.kt`
- Create: `android/app/src/main/java/.../generation/Family.kt`
- Create: `android/app/src/main/java/.../generation/Tier.kt`
- Create: `android/app/src/test/.../generation/GeneratorV1GoldenTest.kt`
- Create: `contracts/fixtures/generator-v1/*.json`

Build a pure deterministic mapping from approved day summary + secret-derived seed + generator version to family, tier and visual parameters. Add golden vectors and property tests proving stable results, all-pattern tier reachability, and no monotonic “more notifications equals rarer” relationship.

## Task 6: Sealing state machine

**Files:**
- Create: `android/app/src/main/java/.../sealing/SealDayWorker.kt`
- Create: `android/app/src/main/java/.../sealing/SealDayUseCase.kt`
- Create: `android/app/src/test/.../sealing/SealDayUseCaseTest.kt`
- Create: `android/app/src/test/.../sealing/SealRecoveryTest.kt`

Test normal midnight seal, app-off catch-up, duplicate worker execution, crash between summary and specimen insert, timezone changes, revoked permission, clock rollback and multiple missed days. Use one database transaction and unique constraints so each anchored day yields at most one ordinary specimen.

## Task 7: Preferences and local secret

**Files:**
- Create: `android/app/src/main/java/.../settings/UserPreferences.kt`
- Create: `android/app/src/main/java/.../security/LocalSecretStore.kt`
- Create: `android/app/src/test/.../security/LocalSecretStoreTest.kt`

Store consent/preferences with DataStore and protect source-HMAC/generator material with Android Keystore. Define explicit recovery when protected material becomes unavailable without silently changing historical identities.

## Task 8: Local repository API

**Files:**
- Create: `android/app/src/main/java/.../domain/FormationRepository.kt`
- Create: `android/app/src/main/java/.../domain/MuseumRepository.kt`
- Create: fakes under `android/app/src/test/.../fakes/`

Expose flows needed by UI without leaking database entities. Verify local gameplay remains functional with networking absent and analytics disabled.

## Phase exit

- Privacy regression suite passes.
- Reduced Room schema inspection contains only approved notification data.
- Generator vectors are deterministic.
- Quiet/noisy/unobserved days are distinct.
- Duplicate/restart/timezone sealing tests pass.
- Android lint, unit tests and `assembleDevDebug` pass.
