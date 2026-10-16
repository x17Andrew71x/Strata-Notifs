# Development feedback loop

## Status

Approved development-only pilot for Afterchime Android. This document defines the reusable contract; Afterchime supplies the first web, Android and server adapters.

## Product contract

A movable in-app development control lets Andrew:

1. select a rendered element or drag a screen region;
2. add a short title and description;
3. submit an immutable context bundle and highlighted screenshot;
4. open work items to inspect the submitted request in submission order;
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

The controller is a separately supervised, owner-only service. It claims one five-minute renewable lease at a time, persists the bounded packet and screenshot under a private state directory, and runs a fresh host-pinned Terra session in a dedicated Git clone. The interpretive worker receives bounded file/search, skill, web and vision capabilities but no terminal or writable Git metadata. It can edit only allowlisted product/test paths; it cannot run commands, create commits, publish, or access the canonical checkout, controller secrets, deployment credentials, the user service bus or ordinary Git remote. Each model run executes in a child transient systemd unit with a 3.5 GiB memory ceiling, no swap, a one-hour wall-clock ceiling, a private user/home/filesystem view and a lifecycle binding to the controller.

After the model returns, the controller validates path, file-count, type and byte limits, then runs the fixed release gates in a second credential-free sandbox. The verifier receives neither provider authentication nor deployment credentials, and candidate bytes must remain unchanged through verification. Only then does the controller create the commit, import it through the isolated clone boundary, fast-forward the clean canonical checkout, push it and wait for Railway development. Lease heartbeats fence preparation, evidence capture, verification and every irreversible integration step. The controller itself is OOM-protected, so a child OOM or timeout cannot kill queue supervision.

Before every attempt the controller records the clean development baseline and resets only the isolated clone to it. If a run is interrupted, it durably preserves the clone's bounded tracked patch, local commits and allowed untracked evidence, records truncated or omitted evidence in a capped manifest, then resets that clone to `origin/dev`; the canonical checkout remains clean. If the controller itself restarts after a controller-created candidate commit, the durable state and isolated HEAD let it finish integration or deployment verification without repeating model work. Otherwise, the short lease expires, the same queue position is reclaimed and the recorded running attempt enters the bounded recovery path. The controller retries up to three times with increasing backoff, applies a global state/run quota, and turns repeated infrastructure failures into **Blocked** rather than looping forever or exhausting disk.

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

The development panel, trigger and selection hint are synchronously removed from the capture frame only; the selected-area highlight remains visible over the underlying product screen. Closing the panel clears its unsent draft. The panel always renders above the selection highlight while Andrew is entering details.

No notification text, sender, contact, action, media, raw package identity, credentials, cookies, request bodies, arbitrary application state, filesystem data or unrestricted logs may enter this channel.

## Lifecycle and ordering

User-facing states are:

`Queued → Working → Blocked | Ready for QA → Accepted`

Rules:

- The database assigns a monotonic queue position. Clients cannot choose or change it.
- Only one item may be actively worked for a project.
- The oldest blocked item prevents later work until resolved.
- A Ready-for-QA item does not prevent the next queued item from being implemented.
- A queued, blocked or Ready-for-QA amendment appends a new immutable revision, preserves the original queue position and returns the item to Queued. Active Working items are locked against amendment so a visible edit cannot revoke a live worker lease.
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
