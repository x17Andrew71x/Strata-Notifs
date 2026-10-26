# 05 — Exhibits Community Implementation Plan

> **Authority:** Andrew's approved Exhibits v3 direction supersedes the former specimen-donation and World Formation plan. Do not restore those retired mechanics from repository history.

**Goal:** Deliver one calm, contribution-first Exhibits surface: a quarterly Featured Season, one optional Restoration Archive target, and a Past Exhibits list.

**Architecture:** PostgreSQL owns exhibit definitions, immutable contribution targets, idempotent contribution rows, shared-cap enforcement and personal eligibility. Android contributes only locally earned post-excavation surplus through the authenticated native boundary. The hosted shell renders a cached 960×960 image through a deterministic 320×320 WebGL2/Canvas reveal mask.

---

## Locked product contract

- Four Featured Seasons per year.
- Contribution begins only after the user's daily excavation is complete.
- **Contribute all** is deliberate; there is no automatic spend or repetitive per-unit tapping.
- One private per-user daily cap applies across Featured Season and Restoration Archive.
- Reward eligibility requires both the community target and the user's individual minimum.
- Public progress is percentage-only with two decimal places; raw units, reveal cells and population remain private.
- Seasonal images remain in Exhibits, separate from Museum specimens and every Series.
- Personal showcases, browsing other users, feeds, following, comments, messages, ranks and leaderboards are out of scope.
- Outbound Afterchime notifications require a separate later design pass.

## Task 1 — Contribution scaling and schema

**Files:**
- `server/src/db/schema/exhibits.ts`
- next forward migration
- migration/RLS tests

Store a fixed `target_units`, personal minimum and daily cap per exhibit. Keep the 102,400 visual cells independent: progress basis points are `floor(total_units × 10,000 ÷ target_units)`, and the client maps that percentage across the fixed reveal grid. This is the cold-start scaling mechanism. Fix values before opening and never move a live target.

Persist exhibit definitions, installation-scoped idempotent contributions and unlock records. Force RLS on installation-owned rows. Grant the runtime role only the required reads/inserts plus bounded aggregate-progress updates.

## Task 2 — Authenticated Exhibits API

**Files:**
- `server/src/modules/exhibits/`
- `server/test/api/exhibits.test.ts`

Provide:

- `GET /v1/exhibits/current?local_date=YYYY-MM-DD`
- `POST /v1/exhibits/{id}/contributions`

Return aggregate progress only as integer basis points, plus the requesting installation's private contribution/minimum. Enforce essential-online consent, build-channel isolation, one cap across both destinations, row-locked progress updates and exact idempotent replay. Do not return target units, total units or participant counts.

## Task 3 — Android contribution and reconciliation boundary

**Files:**
- `android/.../community/ExhibitsRepository.kt`
- network models/client extensions
- strict native bridge actions and tests

Derive banked Exhibit energy from the completed daily excavation's surplus, minus the server-acknowledged amount already contributed that local day. Persist one non-secret pending attempt with its idempotency key. Preserve it across network ambiguity; clear it only after accepted/duplicate acknowledgement or a definitive rejection. Do not expose bearer credentials to the hosted shell.

## Task 4 — Approved Exhibits v3 shell

**Files:**
- `web/src/App.tsx`
- `web/src/ExhibitReveal.tsx`
- `web/src/exhibits.ts`
- `web/src/style.css`
- content-addressed placeholder images and fallback-cache inventory

Match the approved lean hierarchy exactly:

1. Featured Season image and compact progress/action panel.
2. One compact Restoration Archive row.
3. Compact Past Exhibits cards; earned colour/viewable, missed grayscale/disabled.

Use a broad top-left to bottom-right reveal with a narrow rough pixel-scattered edge. Use WebGL2 first, retain a deterministic Canvas 2D/context-loss fallback, honour Reduce motion, and never render 102,400 DOM elements.

## Task 5 — Later content operations and live updates

The first pass uses deterministic content-addressed placeholder images. Before real seasonal artwork ships, add the separately reviewed Draft → Ready → Active → Closed administration flow, private draft previews, atomic publication and replacement-safe content caching.

Native-authenticated refresh after app open/focus, contribution and visible-tab polling is the first-pass freshness path. A percentage-only server-sent event stream may later replace visible-tab polling after load and connection behaviour are measured; it must coalesce aggregate versions and never expose identities.

## Acceptance

- Contribution cannot occur before the personal daily excavation completes.
- Featured and Archive destinations share one enforced daily cap.
- Exact retry returns duplicate without adding units twice.
- Public state contains no raw global counters or population data.
- 0.00%–100.00% maps deterministically across all 102,400 visual cells.
- WebGL2 and forced Canvas fallback both render the same direction.
- Active, complete/under-minimum, earned and missed states work at 320×568, 360×640, 412×915 and desktop widths without horizontal overflow.
- Placeholder assets are content-addressed, hash-verified and present in both hosted and bundled fallback inventories.
- Focused web/server/Android tests, migration/RLS proof, full repository checks and a verified development APK pass before the slice is called complete.
