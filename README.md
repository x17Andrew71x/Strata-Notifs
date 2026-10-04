# Afterchime

Afterchime is an Android collection game that turns notification rhythm—not notification content—into private generative specimens and cooperative seasonal artworks.

The repository is in development. The canonical product and data contract is [`docs/PRODUCT_SPEC.md`](docs/PRODUCT_SPEC.md); ordered implementation work begins at [`docs/plans/INDEX.md`](docs/plans/INDEX.md).

## Safety boundary

- Never persist or transmit notification title, body, sender, actions, media, raw package name or other content.
- Work against development only. Production deployment requires Andrew's explicit approval.
- Secrets, signing keys and provider credentials do not belong in Git.
- `main` is stable source; current integration occurs on `dev`.

## Planned layout

- `android/` — native Kotlin shell, notification capture, local persistence, generators, and device capabilities
- `web/` — remotely updateable React/Vite onboarding and game presentation shell
- `server/` — Fastify/TypeScript API and Railway web-asset hosting
- `contracts/` — versioned schemas and shared fixtures
- `docs/` — canonical product, data and operational documentation
- `scripts/` — deterministic verification helpers

See [`docs/architecture/WEB_SHELL.md`](docs/architecture/WEB_SHELL.md) for the WebView bridge/privacy boundary, development host configuration, offline fallback/cache, and update/rollback responsibilities.

## Versioning

Pre-release builds use semantic `MAJOR.MINOR.PATCH` versioning. The first installable build is `0.1.0` with Android version code `1000`.
