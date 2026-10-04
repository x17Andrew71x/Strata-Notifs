# Afterchime shell foundation

## Runtime split

`web/` is the remotely updateable React/Vite onboarding and future presentation surface. The Fastify service serves the built `web/dist` output: `/` is revalidated, `/assets/<name>-<hash>.{js,css}` is immutable, `/service-worker.js` is revalidated, `/shell/metadata` contains only shell/bridge versions and the entry route, and `/shell/health` is an explicit static-shell health response. Missing web output returns a bounded 503 instead of shadowing API routes. The Docker build produces the web bundle and copies it next to the API runtime; set `AFTERCHIME_WEB_ROOT` only to that build output directory.

`android/` remains responsible for notification capture/reduction, Room/DataStore/Keystore state, generators, and Android-only capabilities. This shell's native bridge is v1 and permits only `capabilities.get` and the user-facing `notificationAccess.openSettings` action. Its responses contain the listener-access boolean or action result only. No event, source identity, notification content, token, arbitrary URL, filesystem operation, or network function crosses the bridge. Any future bridge expansion requires a separately versioned reviewed contract and tests.

## Origin and shell safety

Set the development APK's remote entry with the Gradle property `afterchime.devShellUrl=https://<development-host>/` or the equivalent `-P` option. It is validated as HTTPS with no embedded userinfo. The placeholder default is a development-only Railway host; configure the isolated development service host explicitly before producing a development APK. The prod build remains pointed at `production-disabled.invalid` and has production disabled. Do not put API keys, Railway credentials, production settings, or user tokens in Vite variables or build configuration.

The WebView uses AndroidX WebViewAssetLoader for the bundled `https://appassets.androidplatform.net` shell. Only that origin and the exact configured HTTPS remote origin are allowed for shell navigation and bridge messages. It rejects off-origin navigation and subresources, popups, file/content access, downloads, and mixed content. JavaScript is enabled only because the reviewed shell requires it; the only native entry point is an origin-restricted AndroidX WebMessage listener, not `JavascriptInterface`.

## Offline, update, and rollback

Every APK contains a responsive local HTML/CSS/JS fallback at `android/app/src/main/assets/web/`. First launch or remote load/TLS/HTTP failure opens this bundled collection shell; local gameplay is not blocked by authentication or connectivity. A valid remote page registers the same-origin service worker. It refreshes the entry from network, but only replaces the cached entry after checking that its root marker and every referenced hashed JS/CSS asset are present and readable. It keeps the last complete shell and serves it when the network is unavailable; hashed assets use a network-first request with that exact cached URL as fallback. Failed/partial entry updates do not evict the prior good entry. The service worker does not cache API responses or cross-origin requests. Clearing Android WebView site data clears the web last-known-good cache; the bundled APK fallback remains.

A web-only UI/presentation change can be deployed to the isolated development Railway service without replacing an installed APK, as long as it stays within the current v1 native bridge and same origin. A bridge change, native permission/capability, offline fallback asset change, notification listener, local database/storage, wallpaper, billing, or other Android/device-only change requires a new APK. Roll back a bad web release by restoring the previous development web artifact; clients revalidate the entry and continue using the prior cached complete entry until the restored version is fetched. The independently verified APK fallback covers first-launch/offline and service-worker cache loss.

Development Railway service, its domain, database, and credentials must be isolated from production by the deployment owner. This code does not deploy, create Railway resources, inspect live services, or enable production. Validate service/build paths and device/offline behaviour in the authorized development environment only after Alfred's deferred gates are cleared.

## Validation map

- `web/src/App.test.tsx` checks approved onboarding text, absence of user-visible “strata”, and notification settings bridge interaction/fallback.
- `web/src/bridge.test.ts` rejects unknown versions, malformed IDs/types, and extra fields.
- `server/test/shell.test.ts` checks shell headers/cache policy, metadata and health, hashed asset caching, and traversal rejection.
- `android/app/src/test/.../MainActivityTest.kt` checks the hardened WebView, native notification-settings intent, and local fallback copy.
- Run `pnpm --filter @afterchime/web test`, `pnpm --filter @afterchime/web build`, `pnpm --dir server test -- shell.test.ts`, repository privacy/format/typecheck/build gates, and Android tests on the isolated Afterchime builder. Android and Railway/device checks are manager-deferred for this implementation task.
