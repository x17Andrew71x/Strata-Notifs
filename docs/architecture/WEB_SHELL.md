# Afterchime shell foundation

## Runtime split

`web/` is the remotely updateable React/Vite onboarding and future presentation surface. The Fastify service serves the built `web/dist` output: `/` is revalidated, `/assets/<name>-<hash>.{js,css}` is immutable, `/service-worker.js` is revalidated, `/shell/metadata` contains only shell/bridge versions and the entry route, and `/shell/health` is an explicit static-shell health response. Missing web output returns a bounded 503 instead of shadowing API routes. The Docker build produces the web bundle and copies it next to the API runtime; set `AFTERCHIME_WEB_ROOT` only to that build output directory.

`android/` remains responsible for notification capture/reduction, Room/DataStore/Keystore state, generators, and Android-only capabilities. This shell's native bridge is v1 and permits only `capabilities.get` and the user-facing `notificationAccess.openSettings` action. Its responses contain the listener-access boolean or action result only. No event, source identity, notification content, token, arbitrary URL, filesystem operation, or network function crosses the bridge. Any future bridge expansion requires a separately versioned reviewed contract and tests.

## Origin and shell safety

Set the development APK's remote entry with the Gradle property `afterchime.devShellUrl=https://<development-host>/` or the equivalent `-P` option. It is validated as HTTPS with no embedded userinfo. The default is an inert development-only placeholder; configure the isolated development service host explicitly before producing a remote-shell development APK. The prod build remains pointed at `production-disabled.invalid` and has production disabled. Do not put API keys, Railway credentials, production settings, or user tokens in Vite variables or build configuration.

The WebView uses AndroidX WebViewAssetLoader for the bundled `https://appassets.androidplatform.net` shell. Only that origin and the exact configured HTTPS remote origin are allowed for shell navigation and bridge messages. It rejects off-origin navigation and subresources, popups, file/content access, downloads, and mixed content. JavaScript is enabled only because the reviewed shell requires it; the only native entry point is an origin-restricted AndroidX WebMessage listener, not `JavascriptInterface`.

## Offline, update, and rollback

Every APK contains a responsive local HTML/CSS/JS fallback at `android/app/src/main/assets/web/`. First launch or remote load/TLS/HTTP failure opens this bundled collection shell; local gameplay is not blocked by authentication or connectivity. Only a production build registers the same-origin service worker, so a development server cannot leave stale controlled pages behind. The worker fetches every referenced hashed script/style asset, validates the entry/asset types, computes SHA-256 digests, stores content under digest-addressed keys, and advances a single current-entry pointer only after complete success. Offline entry and asset reads verify the stored digests; a failed or partial update preserves the previous pointer. The worker does not cache API responses or cross-origin requests. If there is no valid current entry, it fails closed so Android can use the bundled fallback. Clearing Android WebView site data clears the web last-known-good cache; the bundled APK fallback remains.

A web-only UI/presentation change can be deployed to the isolated development Railway service without replacing an installed APK, as long as it stays within the current v1 native bridge and same origin. A bridge change, native permission/capability, offline fallback asset change, notification listener, local database/storage, wallpaper, billing, or other Android/device-only change requires a new APK. Roll back a bad web release by restoring the previous development web artifact; clients revalidate the entry and continue using the prior cached complete entry until the restored version is fetched. The independently verified APK fallback covers first-launch/offline and service-worker cache loss.

Development Railway service, its domain, database, and credentials must be isolated from production by the deployment owner. This code does not deploy, create Railway resources, inspect live services, or enable production. Validate service/build paths and device/offline behaviour in the authorized development environment only after Alfred's deferred gates are cleared.

## Validation map

- `web/src/App.test.tsx` checks approved onboarding text, absence of user-visible “strata”, production-only worker registration, and notification settings bridge interaction/fallback.
- `web/src/bridge.test.ts` rejects unknown versions, malformed IDs/types, and extra fields.
- `web/src/service-worker.test.ts` checks initial cache priming, offline restart, fail-closed startup, and partial-update rollback.
- `server/test/shell.test.ts` checks shell headers/cache policy, metadata and health, hashed-only immutable caching, and traversal rejection.
- `android/app/src/test/.../MainActivityTest.kt` checks initial bundled startup, remote-failure policy, origin filtering, strict bridge parsing, and the native notification-settings intent.
- Run `pnpm --filter @afterchime/web test`, `pnpm --filter @afterchime/web build`, `pnpm --dir server test -- shell.test.ts`, repository privacy/format/typecheck/build gates, and Android tests on the isolated Afterchime builder. Android and Railway/device checks are manager-deferred for this implementation task.
