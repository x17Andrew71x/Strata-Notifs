# 05 — Community Exhibitions Implementation Plan

> **For Hermes:** Donations are irreversible inventory mutations. Implement server transaction/reconciliation before presentation polish.

**Goal:** Deliver seasonal collaborative mosaics, fair global notification creations and permanent contributor awards.

**Architecture:** PostgreSQL owns campaign state, specimen donation and awards transactionally. Immutable deterministic manifests let Android render community art without uploaded user media or server-side raster storage.

**Milestone:** `0.6.0`

---

## Task 1: Campaign schema and state machine

**Files:**
- Create: `server/src/db/schema/community.ts`
- Create: next migration
- Create: `server/src/modules/community/campaign-state.ts`
- Create: `server/test/community/campaign-state.test.ts`

Model draft, scheduled, open, completing, complete, expired and cancelled states with legal transitions only. Store blueprint version/hash, target, timing and environment. Test boundary timestamps, duplicate transitions and forbidden rollback.

## Task 2: Deterministic blueprint and manifest contract

**Files:**
- Create: `contracts/community/v1/*.schema.json`
- Create: `server/src/modules/community/blueprint.ts`
- Create: `android/.../community/CommunityManifest.kt`
- Create: shared fixtures/tests

Specify grid/cell geometry, palette, layer, specimen-family influence and final hash. Test identical rendering inputs across Android parser and server validator; reject excessive dimensions, unknown renderer versions and mutable completed manifests.

## Task 3: Transactional donation

**Files:**
- Create: `server/src/modules/community/routes.ts`
- Create: `server/src/modules/community/donate.ts`
- Create: `server/src/modules/community/repository.ts`
- Create: API/database tests

**Required cases:** ordinary=1, Restored=3, Centre Piece=9; locked/not-owned/already-consumed rejection; closed campaign; duplicate idempotency key; concurrent final contributions; timeout replay; forged specimen; premium-world neutrality.

One transaction must lock/consume the specimen, create inventory mutation, record contribution, update progress, fill deterministic cells and create completion/outbox facts when the target is reached.

## Task 4: Award outbox worker

**Files:**
- Create: `server/src/jobs/community-awards.ts`
- Create: `server/test/jobs/community-awards.test.ts`

Issue one contributor edition per distinct contributor. Test worker crash before/after insert, duplicate queue rows, lease expiry, restart and acknowledgement. A failed award must remain visible and retryable.

## Task 5: Android donation reconciliation

**Files:**
- Create: `android/.../community/DonateSpecimenUseCase.kt`
- Create: `android/.../data/local/PendingDonationEntity.kt`
- Create: tests

Lock local specimen before request, preserve pending state on ambiguity, query/replay with same key, and consume only from definitive server result. Test process death at each boundary and prevent a second donation attempt while unresolved.

## Task 6: Community screens

**Files:**
- Create: `android/.../ui/community/CommunityScreen.kt`
- Create: `.../CampaignDetailScreen.kt`
- Create: `.../DonationConfirmation.kt`
- Create: `.../CommunityArtworkRenderer.kt`
- Create: interaction/golden tests

Show one current exhibition and World Formation with restrained progress. Donation confirmation names exact specimen and units. Completed art appears in the museum and works offline after sync. No contributor ranking, public profile or free-form content.

## Task 7: World Formation aggregates

**Files:**
- Create: server schema migration/module/job/tests
- Extend Android aggregate fixtures

Maintain true consented notification total separately from capped game pressure. Test per-installation daily cap, revisions, duplicate batches, withdrawn consent, milestone transitions and late corrections. Public API returns only global values after a privacy threshold and may round display totals without changing stored analytics.

## Task 8: Seasonal administration

**Files:**
- Create: `server/src/modules/admin/campaigns.ts`
- Create: `server/test/admin/campaigns.test.ts`
- Create: `docs/operations/CAMPAIGNS.md`

Authenticated owner operations create/validate/schedule/open/close development campaigns. Require preview hash and explicit environment. Audit every change; forbid production actions in dev credentials and application configuration.

## Task 9: Abuse and load tests

Test registration farms/rate limits, donation replay, concurrent target crossing, impossible contribution units, aggregate inflation, oversized manifests and award fan-out. Measure query plans and indexes with realistic synthetic volume; fix measured bottlenecks only.

## Phase exit

- A development campaign proceeds draft → open → complete.
- Ordinary/restored/centre donations survive duplicate and crash tests.
- All contributors receive exactly one signed artwork.
- True totals and capped game pressure reconcile separately.
- Android renders in-progress and completed community states accessibly.
- Full server/Android/contract gates pass.
