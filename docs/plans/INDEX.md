# Afterchime implementation plans

> **For Hermes:** Execute these plans in order. Each phase uses test-first slices, independent review of delegated work, real command output, and coherent commits. Production is forbidden until Andrew approves it.

**Canonical product contract:** [`../PRODUCT_SPEC.md`](../PRODUCT_SPEC.md)<br>
**Integration branch:** `dev`<br>
**Current milestone:** `0.3.0`<br>
**Last revised:** 2026-10-03

## Version milestones

| Version | Plan | Exit condition | Status |
|---|---|---|---|
| `0.1.0` | [00 Foundation](00-foundation.md) | Reproducible monorepo, contracts, CI and local quality gates | Complete |
| `0.2.0` | [01 Server data foundation](01-server-data-foundation.md) | Real-PostgreSQL identity, consent, analytics ingest and safe migrations | Complete |
| `0.3.0` | [02 Android capture and generator](02-android-capture-generator.md) | Privacy-reduced capture, day sealing and deterministic specimens work offline | In progress |
| `0.4.0` | [03 Museum, worlds and UI](03-museum-worlds-ui.md) | Complete local game loop, concise Compose UI and launch renderers | Pending |
| `0.5.0` | [04 Online sync and analytics](04-online-sync-analytics.md) | Consent-aware online identity, batching, sync and derived metrics | Pending |
| `0.6.0` | [05 Community exhibitions](05-community-exhibitions.md) | Donations, seasonal mosaics, World Formation and awards survive replay/restart | Pending |
| `0.7.0` | [06 Commerce and owner operations](06-commerce-operations.md) | Dev billing, entitlements, owner metrics and dev Railway operations are complete | Pending |
| `0.8.0` | [07 Beta hardening and delivery](07-beta-hardening-delivery.md) | Review findings fixed; installable verified APK delivered; prod inert | Pending |

## Verified progress

- **2026-10-02 — Foundation Task 5:** Replaced the broad analytics schema with closed v1 lifecycle/onboarding contracts, shared valid/invalid fixtures, server and Android fixture validation, OpenAPI batch bounds, and the data dictionary. `bash scripts/check.sh` passed.
- **2026-10-02 — Foundation Task 6 and phase exit:** Added development-only GitHub Actions quality gates, trusted-run test/APK artifacts, pinned action revisions, supported dependency review, and Dependabot coverage for the pnpm workspace, Gradle, and Actions. Local server checks, JUnit report generation, actionlint, and `./scripts/check.sh` passed. GitHub CI run #1 for `c03d9027be3bd4c144953b6e428674e7bdf3f358` completed successfully on `dev`: Quality gates passed, Dependency review correctly skipped outside its supported event/capability boundary, and non-empty trusted-run test-report and dev-debug APK artifacts were retained. A fresh remote clone also passed `./scripts/check.sh` with the explicitly supplied host Android SDK prerequisite. Foundation is complete; no APK was delivered.

- **2026-10-03 — Server data foundation Task 1:** Added a pinned PostgreSQL 17.6 Docker harness and CI role-attestation lane, isolated migration/runtime credentials, non-owner/non-superuser/non-`BYPASSRLS` startup attestation, and readiness version/migration-head output. The 10-case runtime suite passed against an ephemeral native PostgreSQL 17 fallback; local Docker launch is blocked by this host's nested-container `runc` sysctl restriction, while Compose syntax and the pinned image were independently verified. `./scripts/check.sh` passed.
- **2026-10-03 — Server data foundation Task 2:** Added the Drizzle identity, analytics, and operations schemas plus the generated initial migration for pseudonymous users/installations, refresh-token families, consent history, analytics envelope fields, privacy-safe daily aggregates, idempotency, outbox jobs, run records, and schema metadata. User-owned data has forced RLS and runtime-only grants. The real PostgreSQL 17.6 suite passed all 10 role-attestation and 5 schema/migration/RLS cases; `./scripts/check.sh` passed.
- **2026-10-03 — Server data foundation Task 3:** Added anonymous installation registration with strict reduced device metadata, server-derived dev/prod token audiences, 15-minute signed access tokens, hashed one-time refresh secrets, idempotent registration, per-client registration limiting, rotation-family replay revocation, and logout. The forward migration confines token operations to security-definer functions while preserving forced-RLS runtime restrictions; no prohibited notification fields are accepted or persisted. An isolated disposable PostgreSQL run passed all 30 server tests (including the 4 registration API, 5 migration/RLS, and 10 runtime-role cases); `bash scripts/check.sh` passed.
- **2026-10-03 — Server data foundation Task 4:** Added authenticated, idempotent versioned consent updates for essential online, product analytics, and notification aggregates. Each request records the append-only audit history but returns only the installation’s current scope states; replay conflicts cannot alter consent. The reusable consent guard permits only an active exact scope/version, ready for Task 5’s ingestion boundary. The real PostgreSQL suite passed all 21 runtime-role, migration, identity, and consent cases against an isolated PostgreSQL 17.11 fallback; the pinned Docker 17.6 harness remains blocked locally by the host `runc` sysctl restriction. `bash scripts/check.sh` passed. Task 5 (strict analytics batch ingestion) is next.

- **2026-10-03 — Server data foundation Task 5:** Added strict versioned analytics batch ingestion with a closed registry, bounded 128 KiB/50-event requests, authenticated installation ownership, matching active analytics consent, server-derived build/synthetic markers, and one transactional idempotent write path. Responses expose only event IDs and `accepted`/`duplicate`/`rejected` status. The isolated PostgreSQL 17.11 suite passed all 37 server tests, including accepted, duplicate, collision-rejected, mixed retry, malformed/free-form, clock, consent, ownership, and synthetic-marker cases; `bash scripts/check.sh` passed. Task 6 (notification aggregate ingestion) is next.
- **2026-10-03 — Server data foundation Task 6:** Added authenticated, consent-version-gated daily notification aggregate ingestion. The strict aggregate-only contract accepts capped counts, one bucket per hour, a closed coarse category set, game pressure, observation completeness, and rules version; it rejects source-derived or client-owned metadata. One installation-local day has one explicit revision: exact replays are duplicates, only a newer revision replaces it, and no path adds a second row. The isolated native PostgreSQL suite passed all 27 database-backed cases, including the new aggregate contract, and the remaining 13 pure server cases, formatting, privacy checks, OpenAPI parse, lint, typecheck, and build passed. Task 7 (aggregation jobs) is next.
- **2026-10-03 — Server data foundation Task 7:** Added a runtime-attested worker command and a security-definer, advisory-lock-protected daily rollup. It writes checkpointed/idempotent installation, global, consent-coverage, release-health, and UTC notification-volume marts; records bounded leases and safe run outcomes; preserves installation-scoped forced RLS; and fails visibly rather than overwriting stable-source drift. The isolated local PostgreSQL 14 suite passed all 41 server tests with extended test/hook timeouts for this host’s slower disposable cluster, including migration/RLS and rollup drift cases. Formatting, lint/typecheck, build, and notification-privacy checks passed. Task 8 (deletion and retention) is next.
- **2026-10-03 — Server data foundation Task 8:** Added the authenticated, idempotent `DELETE /v1/account` path and a scope-bound security-definer erasure transaction. It removes an account’s direct installations, refresh credentials, consent history, events, notification aggregates, idempotency records and installation marts while leaving only non-reidentifiable global rollups. Added a bounded, advisory-lock-protected retention worker; it resumes safely across batches, deletes expired direct records after 24 months, and records only safe category-level outcomes. The daily rollup now preserves historical global marts after source expiry. The isolated PostgreSQL 17.6 suite passed all 30 database-backed cases, including retention, account deletion, migration/RLS, identity, consent and ingestion; final focused deletion/retention rerun passed. `pnpm check`, `bash scripts/check.sh`, privacy checks, formatting, server build and Android dev lint/build passed. The host’s nested-container `runc` restriction still prevents the Compose port-mapping harness, so PostgreSQL verification used the same pinned image through isolated host networking.
- **2026-10-03 — Server data foundation phase exit:** Re-exercised `pnpm check`, the zero-vulnerability production dependency audit, and every database suite serially against the pinned disposable PostgreSQL 17.6 image. The ordinary Compose harness remains blocked only by the host `runc` port-publish sysctl restriction; its loopback host-network fallback passed and cleaned up. Plan 01 is complete. Plan 02 Task 1 (privacy-reduced capture boundary) is next; no Railway service is required or authorised by this phase.
- **2026-10-03 — Android capture and generator Task 1:** Added the direct Android notification reducer and immutable reduced model. The only emitted values are the received timestamp, local hour, a category closed to the server contract, and HMAC-derived local source token/colour; system, own-app, group-summary, ongoing, and configured categories are rejected. The privacy scanner now allows raw package identity only in that reducer rather than exempting the whole file, and regression tests reject content-bearing Android member access. Focused Android/scanner tests and `bash scripts/check.sh` passed. Task 2 (local database and migrations) is next; no Railway service is required or authorised.
- **2026-10-03 — Android capture and generator Task 2:** Added the Room v1 local store for the approved reduced-event, day-state and sealed-specimen fields, with local-day and anchored-specimen uniqueness, generator version and observation state. Focused persistence tests cover approved-column storage, reads, both uniqueness rules and failed-transaction rollback. The exported-schema v1 create/validate contract compiles into the device-test APK; runtime execution remains open because this host has neither an attached Android device nor an installed AVD. Focused JVM/privacy tests and the complete `bash scripts/check.sh` suite passed. Task 3 remains next; no Railway service is required or authorised.
- **2026-10-03 — Android capture and generator Task 3:** Added the protected Android notification-listener service, a bounded single-worker callback queue, and persisted per-local-day active/disconnected/revoked access evidence. Reduced events receive a deterministic ID from approved fields so callback retries are idempotent; access state retains loss evidence across reconnection and rejects stale callbacks. Room v2 and its forward v1→v2 migration are committed, with the migration test compiled into the device-test APK; runtime migration execution remains open because this host has neither an attached Android device nor an installed AVD. Focused JVM/privacy tests, Android test-APK assembly, and `bash scripts/check.sh` passed. Task 4 (day summary calculation) is next; no Railway service is required or authorised.
- **2026-10-03 — Android capture and generator Task 4:** Added pure local day summarisation over reduced events: true counts remain distinct from per-source elapsed-hour caps and the server-aligned daily game-pressure cap; summaries contain only aggregate category/hour, diversity, quiet-interval, burst, and day/night measures. Observed and unobserved days remain distinct, and exact zone-day boundaries handle DST-forward, DST-back, and travel fixtures without merging repeated clock hours. The focused 10-case summarizer suite and `bash scripts/check.sh` passed. Task 5 (deterministic generator) is next; no Railway service is required or authorised.
- **2026-10-03 — Android capture and generator Task 5:** Added the pure versioned V1 specimen generator. It derives date-bound HMAC entropy from the device-local secret, canonicalises only approved aggregate day features, and emits deterministic family, tier and bounded renderer inputs. Unobserved input returns an explicit non-specimen result, preventing a missing observation interval from becoming a quiet trace. Privacy-safe JSON golden vectors and property coverage prove stable output, every observed pattern class can reach every tier, and notification count does not monotonically increase rarity. Focused generator tests, Android unit/lint/build, privacy checks, and `bash scripts/check.sh` passed. Task 6 (sealing state machine) is next; no Railway service is required or authorised.

- **2026-10-03 — Android capture and generator Task 6:** Added the local transactional sealing state machine, Room v3 specimen-output persistence, and WorkManager retry boundary. Completed known days seal in deterministic order; observed days persist exactly one canonical generator output, unobserved/revoked days remain specimen-free, missed days catch up, and summary/specimen/output writes roll back together. Date-key idempotency survives duplicate workers, clock rollback and timezone changes; persisted days are excluded before secret loading so retries do not regenerate history. Focused sealing tests, the device-test APK compilation, and `bash scripts/check.sh` passed. Physical migration execution remains open because this host has neither an attached Android device nor an installed AVD. Task 7 (preferences and Keystore-backed local secret/runtime activation) is next; no Railway service is required or authorised.

Patch versions are used whenever a phase requires fixes after its coherent capability lands. Milestone numbers express ordering, not a target to reach artificially.

## Execution order

1. Establish the repository and contracts before provisioning shared services.
2. Prove the server's identity/data boundaries against disposable PostgreSQL.
3. Build local Android capture and deterministic generation before networking it.
4. Finish the local game and visual system before community complexity.
5. Add online identity, consented analytics and reconciliation.
6. Add transactional community systems and global creation.
7. Add development commerce and owner analytics, then provision/verify Railway development.
8. Perform full review, fix every material finding, build and deliver the APK.

## Cross-phase gates

Every phase must preserve:

- no notification content or raw package identity persisted/transmitted;
- local gameplay when offline or analytics consent is absent;
- strict event schemas and idempotent mutation;
- cosmetic-only paid worlds;
- clean, limited four-destination interface;
- development-only deployment;
- traceable tests for each acceptance claim.

## Definition of status

- **Pending:** no accepted implementation evidence.
- **In progress:** at least one phase slice is active; not necessarily usable.
- **Blocked:** precise external prerequisite recorded; independent work continues where possible.
- **Complete:** every exit gate actually ran and evidence is committed.

No plan may be marked complete from a worker summary alone; Alfred independently inspects changes and reruns material checks.
