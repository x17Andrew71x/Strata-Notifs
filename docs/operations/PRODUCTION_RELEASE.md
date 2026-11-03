# Production release

Afterchime production is approval-gated. Preparing this repository or Railway environment does not authorise a `main` merge, production deployment, DNS cutover, Play upload, or public launch.

## Production boundaries

- `dev` deploys only to Railway development.
- `main` is the reviewed production source branch.
- Railway production must use its own PostgreSQL instance, volume, migrator credentials, restricted runtime role, token secret, refresh-token pepper, domain, and generated service URL.
- Production must set `NODE_ENV=production`, `ALLOW_DEV_AUTH=false`, `DEV_FEEDBACK_ENABLED=false`, and `BILLING_PROVIDER=google-play`. Server startup rejects development authentication, fake billing, shared Decree/PJS feedback credentials, and development feedback in production.
- Commerce is not currently published: no purchase route or Google Play verifier exists. `BILLING_PROVIDER=google-play` prevents a future fake-provider path from becoming a production default; it does not claim purchases are implemented.

## Android release inputs

`prodRelease` fails closed unless all of these are supplied:

```text
AFTERCHIME_PROD_API_BASE_URL=https://<production-host>
AFTERCHIME_PROD_SHELL_URL=https://<production-host>/
AFTERCHIME_PROD_STORE_FILE=/protected/path/afterchime-upload.jks
AFTERCHIME_PROD_STORE_PASSWORD=<secret>
AFTERCHIME_PROD_KEY_ALIAS=<alias>
AFTERCHIME_PROD_KEY_PASSWORD=<secret>
```

The API origin must have no trailing slash; the shell origin must end in `/`. Both must be exact HTTPS origins without credentials, paths, ports, queries, or fragments. The committed `production-disabled.invalid` values remain safe defaults and cannot produce a release artifact.

Ordinary CI assembles the minified `prodReview` variant with the debug review signer so it can inspect production-flavour bytecode without weakening `prodRelease`. A release build has no inert or unsigned bypass.

Use Google Play App Signing with one dedicated upload key. Preserve the upload keystore and passwords outside the repository; losing the upload key creates a recovery event, while reusing the development debug key would destroy release custody.

## GitHub production environment

The manual **Production Android candidate** workflow runs only from `main`. Configure the GitHub `production` environment with approval protection and these values:

**Variables**

- `AFTERCHIME_PROD_API_BASE_URL`
- `AFTERCHIME_PROD_SHELL_URL`

**Secrets**

- `AFTERCHIME_UPLOAD_KEYSTORE_BASE64`
- `AFTERCHIME_UPLOAD_STORE_PASSWORD`
- `AFTERCHIME_UPLOAD_KEY_ALIAS`
- `AFTERCHIME_UPLOAD_KEY_PASSWORD`
- `AFTERCHIME_UPLOAD_CERT_SHA256`

The workflow rebuilds the web fallback, requires a clean fallback diff, builds the production server artifact, creates signed APK/AAB outputs without Gradle configuration-cache persistence, verifies the signer and compiled origins, rejects development/retired markers, writes checksums, and uploads one SHA-named seven-day artifact set.

## Pre-merge gate

1. Freeze one clean candidate SHA on `dev` and require GitHub CI and Security success for that SHA.
2. Run the full server, real-PostgreSQL, web, privacy, dependency, Android unit/lint/release-configuration, production-runtime-migration, and artifact-exclusion gates.
3. Independently review the exact candidate. Any repair creates a new candidate and invalidates prior receipts.
4. Prove Railway production is still source-less/inert, has no deployment, and does not share the development database or credentials.
5. Complete a physical `0.1.21` → `0.1.22` development over-install pass before promoting equivalent native code.

## Promotion and launch authorisation

Only after explicit approval:

1. Provision an isolated Railway production PostgreSQL service and volume; apply migrations with the production migrator role and attest the restricted runtime role. Add a private daily cron service from the same exact image/SHA with `node dist/jobs/runner.js`, no public domain, and the same restricted runtime database role; require successful analytics/retention run receipts.
2. Create the generated production domain and set production variables without printing values.
3. Create the sole `main` production trigger immediately before promotion.
4. Fast-forward `main` to the exact reviewed SHA—never merge a different descendant merely because CI is green.
5. Require Railway deployment metadata and `/health/live` to report the exact SHA and `environment=production`.
6. Run non-mutating production health, migration-head, runtime-role, authentication-denial, shell-byte, service-worker, and artifact-origin checks. Synthetic accounts or durable production records require separate approval.
7. Run the manual production Android workflow and verify its APK/AAB signer receipt against the retained upload-key certificate.
8. Perform a physical production build install/launch, notification-access, offline fallback, remote-shell handoff, process-death, and online opt-in smoke.
9. Confirm backup/restore, rollback commit, provider access, Play listing/data-safety declarations, privacy policy, support route, and monitoring ownership.

Only then may the release record say `Launch authorisation: GO`. Until every item is evidenced, the state remains `HOLD`.
