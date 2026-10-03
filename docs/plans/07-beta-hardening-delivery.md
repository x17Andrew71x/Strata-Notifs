# 07 — Beta Hardening and Delivery Implementation Plan

> **For Hermes:** “Done” requires a verified installable APK and all material review findings fixed. Production remains untouched.

**Goal:** Trace every requirement to running code, repair defects, prove development operations and deliver a clean beta APK to Andrew.

**Architecture:** This phase adds no speculative feature. It audits exact built source and development runtime, fixes evidence-backed findings and refreshes all affected proof.

**Milestone:** `0.8.0` plus patch increments as fixes land.

---

## Task 1: Requirements traceability

**Files:**
- Create: `docs/reviews/REQUIREMENTS_TRACEABILITY.md`

Map every normative section of `PRODUCT_SPEC.md` to source, tests and runtime evidence. Mark missing, partial and complete separately. Implement or explicitly block every missing beta criterion before continuing.

## Task 2: Privacy and data-flow review

**Files:**
- Create: `docs/reviews/PRIVACY_REVIEW.md`
- Create/update privacy policy and Play Data Safety draft

Trace notification object → reducer → Room → generator → analytics/sync/log/export. Inspect compiled models, schemas, fixtures and development rows. Search for prohibited fields and exercise malicious notifications. Fix every leak or overcollection; rerun deletion, consent withdrawal and local-only tests.

## Task 3: Security review

**Files:**
- Create: `docs/reviews/SECURITY_REVIEW.md`

Review authentication, refresh replay, authorization, RLS, admin boundary, rate limits, idempotency, purchase verification, secrets, Android exported components, cleartext policy, dependency supply chain, logs and campaign races. Run static/dependency scans and targeted adversarial tests. Fix and rerun.

## Task 4: Analytics and decision-utility review

**Files:**
- Create: `docs/reviews/ANALYTICS_REVIEW.md`

For every event/table: verify grain, meaning, consent, idempotency, retention and one practical decision it supports. Reconcile raw fixtures to daily marts. Remove redundant/noisy events; add only missing events required by an identified decision. Verify session/login counts, per-installation notifications, global daily notifications, specimen totals, community progress, conversion and release health.

## Task 5: Economy and abuse review

**Files:**
- Create: `docs/reviews/ECONOMY_REVIEW.md`

Simulate quiet, average, noisy, adversarial and long-term collections. Prove notification volume does not monotonically improve rarity; premium worlds confer no contribution or rarity advantage; combining/donation values conserve inventory; campaign targets are attainable; replay and clock abuse fail safely.

## Task 6: Android lifecycle, battery and performance review

**Files:**
- Create: `docs/reviews/ANDROID_PERFORMANCE_REVIEW.md`

Exercise listener reconnect/revoke, process death, reboot/catch-up, midnight/DST/timezone, offline queues, background restrictions, wallpaper visibility and large museum lists. Measure startup, render, database and worker behaviour on defined profiles. Fix wakeups, unbounded work and jank found.

## Task 7: UI consistency and accessibility review

**Files:**
- Create: `docs/reviews/UI_ACCESSIBILITY_REVIEW.md`

Review every root/nested/empty/loading/error state at compact phone, large phone, landscape, tablet and large text. Compare goldens for spacing, typography, colour and animation. Exercise TalkBack labels, focus order, touch targets, contrast, reduced motion and no-colour state communication. Remove clutter rather than decorating it.

## Task 8: PostgreSQL and Railway operational review

**Files:**
- Create: `docs/reviews/OPERATIONS_REVIEW.md`

From a clean development database, apply all migrations, attest runtime role, seed synthetic workflow, restart/redeploy API and worker, verify persistence, rollups, award backlog, retention no-op and backup restoration. Confirm production remains inert and has no development credentials.

## Task 9: Broad clean-clone gates

Run from a fresh checkout at the candidate commit:

- secret/prohibited-field scan;
- install with frozen lockfiles;
- server format/lint/typecheck/unit/integration/security/dependency gates;
- disposable PostgreSQL migrations and RLS tests;
- Android format/static analysis/unit/Room/Compose/golden/lint gates;
- `assembleDevDebug` and package inspection;
- contract compatibility tests;
- development API smoke and synthetic persistence tests.

Record exact commands, commit, tool versions and outputs in `docs/reviews/RELEASE_EVIDENCE.md`.

## Task 10: APK integrity and installation evidence

**Files:**
- Candidate: `android/app/build/outputs/apk/dev/debug/*.apk`

Verify:
- non-zero valid ZIP/APK;
- application ID is development ID;
- version name/code match repository;
- signature verifies;
- min/target SDK and permissions match design;
- no production secret/domain or dev private credential is embedded;
- notification listener and exported component flags are correct;
- checksum recorded.

Install and exercise on an emulator/physical Android device when available. If native execution is unavailable, do not disguise package inspection as runtime proof; record the gap and use Andrew's trial as the remaining beta acceptance step.

## Task 11: Native Discord delivery

1. Copy/rename the verified APK to a clear user-facing filename.
2. Verify local existence, non-zero size, checksum and APK signature again.
3. Upload as a native attachment to the active Discord thread with `hermes send --json`.
4. Fetch Discord messages and verify the expected filename and non-zero attachment size.
5. Keep the ordinary assistant reply text-only.

## Task 12: Final status

- Update version for any repair patches actually landed.
- Mark each plan complete only from evidence.
- Push the exact candidate commit to `dev`.
- Leave `main` and Railway production unpromoted.
- Record remaining user acceptance observations separately from engineering defects.

## Phase exit

Afterchime is complete as a development beta only when all material review findings are fixed, broad gates pass against the exact candidate, the development runtime is healthy, and the verified APK is delivered successfully.
