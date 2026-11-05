# Google Play privacy and deletion surfaces

## Goal

Ship one policy/data-control contract across the hosted Railway shell, the installed Android app, and Google Play disclosures.

## Approved product decisions

- Public policy pages are served by the production Railway origin.
- The installed app renders the same policy copy internally rather than opening a browser for routine reading.
- Deleting an online account removes the server identity, credentials, directly linked records, and the synced local copy on that device.
- A separate future device reset may clear local state while retaining a recoverable online account; it must not be enabled before recoverable accounts exist.
- Accepted Exhibit credit remains in the anonymous public aggregate after account deletion, while the contribution row and every installation/user link are deleted.

## Scope

1. Public `/privacy`, `/support`, and `/delete-account` routes that render without the Android bridge.
2. Matching in-app Privacy, Account & data, and Support screens under Settings.
3. A capability-gated, confirmed Android `account.delete` bridge action.
4. Android account deletion through the authenticated API, followed only on server success by an Android app-data reset.
5. Regression proof that account deletion removes the contribution row and personal unlock while preserving the Exhibit aggregate total.
6. Privacy copy that matches the implemented notification boundary, optional online data, retention, deletion, processors, and contact mechanism.

## Non-goals

- Recoverable email/Google accounts or cross-device collection sync.
- Enabling local-only reset before account recovery exists.
- Production deployment, Play submission, or legal attestation without Andrew's later approval.
- New data collection, advertising, billing, or Play Integrity integration.

## TDD order

1. Add failing web tests for public routes, in-app policy navigation, same-copy assertions, and capability-gated deletion.
2. Add failing server route tests for the three public paths and account-deletion conservation tests for Exhibits.
3. Add failing bridge/API/identity tests for exact request parsing, authenticated deletion, failure preservation, and reset ordering.
4. Implement the smallest coherent server, web, and Android changes.
5. Run focused web/server/Android tests, then formatting, lint, typecheck, builds, privacy checks, real PostgreSQL tests, fallback sync, and Android package verification.

## Rollback

- Hosted policy UI is cache-versioned and can roll back to the prior verified shell.
- The new bridge action is capability-gated; older APKs never receive an unsupported action.
- Account deletion is irreversible by design and therefore requires native confirmation and server-first success before local reset.
- No historical migration is edited; Exhibit conservation is proven against the existing cascade/aggregate model unless a new forward migration is demonstrably required.
