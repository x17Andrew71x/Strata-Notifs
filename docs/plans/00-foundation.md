# 00 — Foundation Implementation Plan

> **For Hermes:** Execute task-by-task with test-first changes and frequent coherent commits. Work on `dev`; production is out of scope.

**Goal:** Create a reproducible monorepo, explicit contracts and local/CI quality gates for Android and server development.

**Architecture:** Native Android and a TypeScript server share versioned JSON/OpenAPI fixtures rather than runtime source. Root scripts orchestrate each independently and fail on contract drift.

**Milestone:** `0.1.0`

---

## Task 1: Establish branches and repository hygiene

**Files:**
- Create: `.gitignore`
- Create: `.editorconfig`
- Create: `.gitattributes`
- Create: `LICENSE` after owner/licence choice is recorded; until then mark all rights reserved in README
- Modify: `README.md`

**Steps:**
1. Record the initial documentation baseline on `main`.
2. Create and push `dev`; make all implementation commits there.
3. Ignore Android/Gradle, Node, IDE, local environment, signing, test-output and APK artifacts while retaining wrappers and lockfiles.
4. Add LF/text rules and binary declarations for media.
5. Verify `git check-ignore` rejects `.env`, keystores, `local.properties`, build trees and node modules.
6. Scan staged content for private keys, Railway URLs with credentials and tokens.

**Acceptance:** clean clone has only intentional files; deploy key is not in the repository; `main` has no deployment automation.

## Task 2: Pin toolchains and root commands

**Files:**
- Create: `.tool-versions`
- Create: `package.json`
- Create: `pnpm-workspace.yaml`
- Create: `scripts/check.sh`
- Create: `scripts/check-privacy-boundary.py`

**Steps:**
1. Pin Node 22 and the selected JDK after verifying Android Gradle Plugin compatibility.
2. Enable Corepack and commit the exact package manager version.
3. Add root commands for format, lint, typecheck, unit tests, integration tests, Android tests and builds.
4. Write a deterministic privacy-boundary scanner that rejects prohibited notification-field names outside narrowly allowlisted Android adapter code and documentation.
5. Add scanner fixtures proving both rejection and permitted reduced fields.
6. Run the root check from a clean shell and record exact prerequisites rather than silently skipping missing toolchains.

**Acceptance:** one command runs every currently available gate and fails if a child command fails.

## Task 3: Scaffold the strict TypeScript workspace

**Files:**
- Create: `server/package.json`
- Create: `server/tsconfig.json`
- Create: `server/src/index.ts`
- Create: `server/src/config.ts`
- Create: `server/src/app.ts`
- Create: `server/test/health.test.ts`
- Create: `server/vitest.config.ts`
- Create: `server/eslint.config.js`

**TDD steps:**
1. Write a health-route test expecting build metadata and no dependency probe.
2. Run it and verify RED because the app does not exist.
3. Implement the minimal Fastify application factory and `/health/live` route.
4. Add strict environment parsing that fails closed for commands requiring database/auth configuration.
5. Run unit tests, typecheck, lint and production compilation.

**Acceptance:** no global singleton is required by tests; logs use JSON and redact `authorization`, cookies, purchase tokens and database URLs.

## Task 4: Scaffold the Android application

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/techfullymade/afterchime/AfterchimeApp.kt`
- Create: `android/app/src/main/java/com/techfullymade/afterchime/MainActivity.kt`
- Create: `android/app/src/test/.../VersionTest.kt`

**Steps:**
1. Generate a standard Gradle wrapper from a verified distribution; do not handcraft wrapper binaries.
2. Configure `dev` and `prod` product flavours with distinct application IDs, names, icons and API placeholders.
3. Set semantic version `0.1.0` and version code `1000` from one checked function.
4. Build the smallest Compose activity and a unit test for semantic-to-version-code conversion.
5. Configure production cleartext denial and ensure dev-only network overrides cannot enter prod.
6. Run JVM unit tests, lint and `assembleDevDebug`.

**Acceptance:** dev APK is buildable from a clean clone; no signing secret is committed; production cannot use a development URL.

## Task 5: Create shared contract foundations

**Files:**
- Create: `contracts/events/v1/envelope.schema.json`
- Create: `contracts/events/v1/*.schema.json`
- Create: `contracts/openapi/afterchime-v1.yaml`
- Create: `contracts/fixtures/valid/*`
- Create: `contracts/fixtures/invalid/*`
- Create: `server/test/contracts.test.ts`
- Create: `android/app/src/test/.../ContractFixtureTest.kt`
- Create: `docs/DATA_DICTIONARY.md`

**Steps:**
1. Define the common analytics envelope without free-form user text or arbitrary properties.
2. Define initial lifecycle/onboarding event schemas and size limits.
3. Add valid and invalid fixtures, including prohibited notification content.
4. Make server and Android tests consume the same fixtures.
5. Add a data-dictionary template with grain, owner, source, sensitivity and retention.

**Acceptance:** both stacks reject invalid fixture shapes; unknown event names fail closed.

## Task 6: Add CI without deployment

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/dependabot.yml`

**Steps:**
1. Run server checks and Android JVM/build checks on pushes to `dev` and pull requests.
2. Cache dependencies without caching secrets or build outputs that can mask clean-build defects.
3. Upload test reports and dev APK only on successful non-fork trusted runs, with short retention.
4. Add dependency review where GitHub supports it.
5. Confirm there is no Railway or Play deployment job.

**Acceptance commands:**
- `corepack pnpm install --frozen-lockfile`
- `corepack pnpm --dir server check`
- `./scripts/check.sh`
- `cd android && ./gradlew testDevDebugUnitTest lintDevDebug assembleDevDebug`

## Phase exit

- Clean-clone local checks pass.
- CI checks pass on `dev`.
- Contract drift and prohibited-field fixtures fail as expected.
- `0.1.0` dev APK assembles.
- Update `INDEX.md` to Complete only after evidence is committed.
