# Stratawake implementation plans

> **For Hermes:** Execute these plans in order. Each phase uses test-first slices, independent review of delegated work, real command output, and coherent commits. Production is forbidden until Andrew approves it.

**Canonical product contract:** [`../PRODUCT_SPEC.md`](../PRODUCT_SPEC.md)<br>
**Integration branch:** `dev`<br>
**Current milestone:** `0.2.0`<br>
**Last revised:** 2026-10-02

## Version milestones

| Version | Plan | Exit condition | Status |
|---|---|---|---|
| `0.1.0` | [00 Foundation](00-foundation.md) | Reproducible monorepo, contracts, CI and local quality gates | Complete |
| `0.2.0` | [01 Server data foundation](01-server-data-foundation.md) | Real-PostgreSQL identity, consent, analytics ingest and safe migrations | Pending |
| `0.3.0` | [02 Android capture and generator](02-android-capture-generator.md) | Privacy-reduced capture, day sealing and deterministic specimens work offline | Pending |
| `0.4.0` | [03 Museum, worlds and UI](03-museum-worlds-ui.md) | Complete local game loop, concise Compose UI and launch renderers | Pending |
| `0.5.0` | [04 Online sync and analytics](04-online-sync-analytics.md) | Consent-aware online identity, batching, sync and derived metrics | Pending |
| `0.6.0` | [05 Community exhibitions](05-community-exhibitions.md) | Donations, seasonal mosaics, World Formation and awards survive replay/restart | Pending |
| `0.7.0` | [06 Commerce and owner operations](06-commerce-operations.md) | Dev billing, entitlements, owner metrics and dev Railway operations are complete | Pending |
| `0.8.0` | [07 Beta hardening and delivery](07-beta-hardening-delivery.md) | Review findings fixed; installable verified APK delivered; prod inert | Pending |

## Verified progress

- **2026-10-02 — Foundation Task 5:** Replaced the broad analytics schema with closed v1 lifecycle/onboarding contracts, shared valid/invalid fixtures, server and Android fixture validation, OpenAPI batch bounds, and the data dictionary. `bash scripts/check.sh` passed.
- **2026-10-02 — Foundation Task 6 and phase exit:** Added development-only GitHub Actions quality gates, trusted-run test/APK artifacts, pinned action revisions, supported dependency review, and Dependabot coverage for the pnpm workspace, Gradle, and Actions. Local server checks, JUnit report generation, actionlint, and `./scripts/check.sh` passed. GitHub CI run #1 for `c03d9027be3bd4c144953b6e428674e7bdf3f358` completed successfully on `dev`: Quality gates passed, Dependency review correctly skipped outside its supported event/capability boundary, and non-empty trusted-run test-report and dev-debug APK artifacts were retained. A fresh remote clone also passed `./scripts/check.sh` with the explicitly supplied host Android SDK prerequisite. Foundation is complete; no APK was delivered.

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
