# 05 — Exhibits Community Implementation Plan

> **Authority:** Andrew's approved Exhibits v3 direction supersedes the former specimen-donation and World Formation plan. Do not restore those retired mechanics from repository history.

**Goal:** Deliver one calm, contribution-first Exhibits surface: a quarterly Featured Season, one optional Restoration Archive target, and a Past Exhibits list.

**Architecture:** PostgreSQL owns exhibit definitions, immutable maximum-cohort-equivalent targets, daily eligible-profile multiplier snapshots, idempotent acceptance-time contribution credit, shared-cap enforcement and personal eligibility. Android records a general foreground `last_opened` profile timestamp and contributes only locally earned post-excavation surplus through the authenticated native boundary. The hosted shell renders a cached 960×960 derivative through a deterministic 320×320 WebGL2/Canvas reveal mask, then opens and downloads its exact 4096×4096 master after unlock.

---

## Locked product contract

- Four Featured Seasons per year.
- Contribution begins only after the user's daily excavation is complete.
- **Contribute all** is deliberate; there is no automatic spend or repetitive per-unit tapping.
- One private per-user daily cap applies across Featured Season and Restoration Archive.
- Reward eligibility requires both the community target and the user's individual minimum.
- Public progress is percentage-only with two decimal places; raw units, reveal cells and population remain private.
- Seasonal images remain in Exhibits, separate from Museum specimens and every Series.
- The first approved development Featured Season is **Fall Seasonal Special**, open from `2026-10-01T00:00:00Z` through the end of November (`ends_at = 2026-12-01T00:00:00Z`), with 500,000 maximum-cohort-equivalent credits, a 900-energy personal minimum and a 30-energy daily cap.
- Every size comes from one approved image: keep the 4096×4096 master and deterministically derive the 960×960 reveal asset; never generate them separately.
- Personal showcases, browsing other users, feeds, following, comments, messages, ranks and leaderboards are out of scope.
- Outbound Afterchime notifications require a separate later design pass.

## Task 1 — Contribution scaling and schema

**Files:**
- `server/src/db/schema/exhibits.ts`
- next forward migration
- migration/RLS tests

Store a fixed maximum-cohort-equivalent `target_credit_units`, personal minimum and raw-energy daily cap per Exhibit. Snapshot eligible profiles once per UTC day: distinct non-synthetic profiles with current essential-online/community consent and a general server-authored `last_opened` within 28 days. Clamp the effective cohort to 100–10,000 and credit each accepted raw energy unit with `round(10,000 × 1,000,000 ÷ effective_users)` microunits. Keep personal minimums/caps raw, preserve the acceptance-time cohort/multiplier/credit on every contribution, and never revalue history. Keep the 102,400 visual cells independent: progress basis points derive from aggregate fixed-point credit against the fixed target, and the client maps that percentage across the reveal grid.

Persist exhibit definitions, installation-scoped idempotent contributions and unlock records. Force RLS on installation-owned rows. Grant the runtime role only the required reads/inserts plus bounded aggregate-progress updates. Add the scaled target/aggregate beside the existing raw target/aggregate and let database triggers prepare contribution credit, so the pre-deploy migration and a bounded rollback remain compatible with the preceding server while both revisions dual-write the legacy raw total.

## Task 2 — Authenticated Exhibits API

**Files:**
- `server/src/modules/exhibits/`
- `server/test/api/exhibits.test.ts`

Provide:

- `POST /v1/profile/open`
- `GET /v1/exhibits/current?local_date=YYYY-MM-DD`
- `POST /v1/exhibits/{id}/contributions`

`profile/open` advances the authenticated profile's general `last_opened` using server time after a real foreground app open; it does not require or depend on community consent and background workers never call it. Return aggregate Exhibit progress only as integer basis points, plus the requesting installation's private contribution/minimum. Enforce essential-online consent for Exhibits, build-channel isolation, one cap and one cohort snapshot across both destinations, row-locked progress updates and exact idempotent replay. Do not return target units, total credit, cohort counts, multipliers or participant counts.

## Task 3 — Android contribution and reconciliation boundary

**Files:**
- `android/.../community/ExhibitsRepository.kt`
- network models/client extensions
- strict native bridge actions and tests

On each authenticated foreground app open, call the dedicated profile-open endpoint before refreshing Exhibits so `last_opened` reflects general app use rather than community-tab use; do not call it from periodic/background sync. Derive banked Exhibit energy from the completed daily excavation's surplus, minus the server-acknowledged amount already contributed that local day. Persist one non-secret pending attempt with its idempotency key. Preserve it across network ambiguity; clear it only after accepted/duplicate acknowledgement or a definitive rejection. Do not expose bearer credentials to the hosted shell.

## Task 4 — Approved Exhibits v3 shell

**Files:**
- `web/src/App.tsx`
- `web/src/ExhibitReveal.tsx`
- `web/src/exhibits.ts`
- `web/src/style.css`
- approved content-addressed 960×960 reveal derivatives, 4096×4096 masters and fallback-cache inventory

Match the approved lean hierarchy exactly:

1. Featured Season image and compact progress/action panel.
2. One compact Restoration Archive row.
3. Compact Past Exhibits cards; earned colour/viewable, missed grayscale/disabled.

Earned Featured and Past entries expose the download icon before opening a near-full-screen viewer. The viewer and Android download action both use the exact content-addressed 4096×4096 master; the progressive reveal never loads that master.

Use a broad top-left to bottom-right reveal with a narrow rough pixel-scattered edge. Use WebGL2 first, retain a deterministic Canvas 2D/context-loss fallback, honour Reduce motion, and never render 102,400 DOM elements.

## Task 5 — Later content operations and live updates

Keep Exhibits in an honest empty state until approved seasonal names, dates, targets and artwork are ready. Hide empty Archive and Past sections; never publish fabricated history or placeholder campaigns. Then add the separately reviewed Draft → Ready → Active → Closed administration flow, private draft previews, atomic publication and replacement-safe content caching.

Native-authenticated refresh after app open/focus, contribution and visible-tab polling is the first-pass freshness path. A percentage-only server-sent event stream may later replace visible-tab polling after load and connection behaviour are measured; it must coalesce aggregate versions and never expose identities.

## Acceptance

- Contribution cannot occur before the personal daily excavation completes.
- Featured and Archive destinations share one enforced daily cap.
- Exact retry returns duplicate without adding or revaluing credit twice.
- General foreground app opens update `users.last_opened` independently of community consent; background sync does not.
- Daily eligible-profile snapshots clamp to 100–10,000, and each accepted contribution retains its original fixed-point multiplier/credit.
- Public state contains no raw global counters, cohort counts or multiplier data.
- 0.00%–100.00% maps deterministically across all 102,400 visual cells.
- WebGL2 and forced Canvas fallback both render the same direction.
- Active, complete/under-minimum, earned and missed states work at 320×568, 360×640, 412×915 and desktop widths without horizontal overflow.
- Seasonal assets are content-addressed, hash-verified, lineage-recorded and present in both hosted and bundled fallback inventories.
- Focused web/server/Android tests, migration/RLS proof, full repository checks and a verified development APK pass before the slice is called complete.
