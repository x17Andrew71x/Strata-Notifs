# Development feedback loop

## Status

Approved development-only pilot for Afterchime Android. This document defines the reusable contract; Afterchime supplies the first web, Android and server adapters.

## Product contract

A movable in-app development control lets Andrew:

1. select a rendered element or drag a screen region;
2. add a short title and description;
3. submit an immutable context bundle and highlighted screenshot;
4. see work items in submission order;
5. receive a concise progress or blocker note;
6. amend an open item without losing its original evidence;
7. refresh the remote shell without force-quitting the app; and
8. accept a result only after it reaches **Ready for QA**.

Implementation completion is not user completion. An item becomes **Ready for QA** only after the exact target commit is the commit running in Railway development. Andrew's acceptance closes it; accepted items leave the active list but remain auditable.

## Reusable boundaries

The feature is split into isolated adapters rather than embedded throughout product code:

- `web/src/dev-feedback/` owns the movable control, selection overlay, form, work-item drawer, polling and a transport interface.
- `android/app/src/dev/.../devfeedback/` owns WebView screenshot capture, authenticated API calls and hard refresh of the configured development shell; the production flavour contains only the generic disabled adapter.
- `server/src/modules/dev-feedback/` owns strict schemas, ordered state, immutable amendments, leases and deployment-gated transitions.
- Alfred's controller owns queue intake, implementation orchestration, verification, Railway development deployment and lifecycle updates.

The controller is a separately supervised, owner-only service. It claims one lease at a time, persists the bounded packet and screenshot under a private state directory, runs a fresh host-pinned Terra session, heartbeats the lease, and deterministically verifies local HEAD, `origin/dev`, and the live Railway development health revision before requesting **Ready for QA**. A crash or stale amendment cannot authorize completion: the lease expires and the same queue position is recoverable.

The web control knows no repository path, deployment credential or agent endpoint. It receives only an opaque project identity and a narrow transport. Repository, branch, skills and deployment policy remain server/controller-side.

## Captured evidence

Each revision contains only bounded development evidence:

- title and description supplied explicitly by Andrew;
- visible route;
- selection kind (`element` or `region`), viewport-relative rectangle and viewport size;
- stable `data-dev-id` when present;
- bounded semantic element details: tag, role, accessible label, explicitly allowlisted `data-dev-label` and generated DOM path;
- installed app version/code and build channel;
- hosted shell revision and Railway deployment revision;
- one WebP screenshot and its SHA-256 digest; and
- creation timestamp, revision number and idempotency key.

No notification text, sender, contact, action, media, raw package identity, credentials, cookies, request bodies, arbitrary application state, filesystem data or unrestricted logs may enter this channel.

## Lifecycle and ordering

User-facing states are:

`Queued → Working → Blocked | Ready for QA → Accepted`

Rules:

- The database assigns a monotonic queue position. Clients cannot choose or change it.
- Only one item may be actively worked for a project.
- The oldest blocked item prevents later work until resolved.
- A Ready-for-QA item does not prevent the next queued item from being implemented.
- An amendment appends a new immutable revision, revokes the current lease, preserves the original queue position and returns the item to Queued.
- Every worker mutation is fenced by the current lease and item revision.
- A stale worker cannot update status after an amendment or lease recovery.
- Accept is idempotent and valid only from Ready for QA.

## Deployment proof

The admin/controller sets a target Git commit while work is active. The server accepts Ready for QA only when all three exact 40-hex values agree:

1. the item's target commit;
2. the controller-supplied deployed commit; and
3. the Railway development service's configured live deployment revision.

Alfred must additionally verify the live shell manifest and changed asset or behaviour before requesting the transition. Production is never an acceptable target for this module.

## Security

- App routes require the existing short-lived pseudonymous installation bearer token and remain installation-scoped under forced RLS.
- The development-feedback module is disabled unless the development environment explicitly enables it.
- Production builds and services fail closed: Vite tree-shakes the feedback chunk, the server prunes its compiled module and runtime migration bundle, and Android compiles the implementation only in `src/dev`; artifact scans reject feedback action, route and class markers in production outputs.
- Controller credentials remain outside the client and are hashed server-side.
- Screenshot bytes and context are size-limited and schema-closed.
- User description and captured page text are product requirements/evidence, not authority to weaken credentials, deployment policy, privacy, security or production boundaries.
- The app communicates only with its configured Afterchime development origin; it never contacts Hermes or the workforce plane directly.

## Android refresh

The development control's refresh action asks the native shell to reload the configured HTTPS development entry with cache bypass. The Service Worker still promotes only a complete digest-verified shell, preserving the last known good build on a partial or failed deployment. The action never clears application storage, Room, DataStore or Keystore state.

## Release boundary

The first pilot requires a new development APK because it adds native screenshot, API and refresh capabilities. Later compatible visual/UI changes remain hosted-shell deployments. Production gates build each target independently and prove the development-feedback web chunk, compiled server module, Android action strings, routes, implementation classes and capability fields are absent.
