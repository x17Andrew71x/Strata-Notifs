# Repository operating rules

Read `docs/PRODUCT_SPEC.md` and `docs/plans/INDEX.md` before changing code.

## Authority and environments

- Work only in local or Railway **development** resources.
- Do not deploy, migrate, seed or alter production without Andrew's later explicit approval.
- Do not add an auto-production deployment path.
- Preserve unrelated or concurrent work; inspect `git status` and the relevant diff before and after edits.

## Required method

- Follow the active phase plan and acceptance criteria.
- Use test-first implementation for behavioural changes.
- Make the smallest coherent change and commit it with its tests.
- Run focused gates first, then the broad repository gate.
- Never claim a build, migration, deployment or test succeeded unless it actually ran.
- Record material plan progress in `docs/plans/INDEX.md` only after verification.

## Privacy hard stop

Notification title, text, sender, contact identity, actions, media, extras, raw package names and notification keys must never be persisted, logged, included in analytics or transmitted. Notification handling may emit only the reduced fields approved in the canonical product specification. Any requested change that weakens this boundary must stop for Alfred's review.

## Data and security

- Secrets stay in environment variables or ignored local files.
- PostgreSQL migrations are forward-only and committed; never edit a migration already applied to a shared environment.
- Server runtime must use a non-owner, non-superuser, non-`BYPASSRLS` role.
- Every mutation and worker operation must be idempotent and covered by duplicate/retry tests.
- Analytics events use a strict allowlisted schema; never accept arbitrary free-form properties.

## Product boundaries

- Paid content is cosmetic only.
- Do not add leaderboards, adverts, loot boxes, punitive streaks or notification-volume rewards.
- Keep UI concise: one primary action per screen, four root destinations, no ornamental clutter.

## Verification

Before a coherent change is merged to `dev`:

1. inspect owned diff;
2. run affected unit/integration/UI tests;
3. run formatting, lint and type checks;
4. run clean package/build for the affected surface;
5. verify no secret or prohibited notification field entered source, fixtures, logs or artifacts.
