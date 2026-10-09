# Afterchime shell foundation

## Runtime split

`web/` is the remotely updateable React/Vite onboarding and future presentation surface. The Fastify service serves the built `web/dist` output: `/` is revalidated, `/assets/<name>-<hash>.{js,css}` is immutable, `/service-worker.js` is revalidated, `/shell/metadata` contains only shell/bridge versions and the entry route, and `/shell/health` is an explicit static-shell health response. Missing web output returns a bounded 503 instead of shadowing API routes. The Docker build produces the web bundle and copies it next to the API runtime; set `AFTERCHIME_WEB_ROOT` only to that build output directory.

`android/` remains responsible for notification capture/reduction, Room/DataStore/Keystore state, date-bound fossil selection, dig-energy accounting, museum persistence, and Android-only capabilities. The strict v2 bridge exposes reduced shell state plus bounded preference, excavation, museum and development-world actions. `excavation.dig` accepts only a tile index; source tokens, notification timestamps/content, secrets, arbitrary URLs, filesystem operations and network functions never cross the bridge.

## Origin and shell safety

Set the development APK's remote entry with the Gradle property `afterchime.devShellUrl=https://<development-host>/` or the equivalent `-P` option. It is validated as HTTPS with no embedded userinfo. The default is an inert development-only placeholder; configure the isolated development service host explicitly before producing a remote-shell development APK. The prod build remains pointed at `production-disabled.invalid` and has production disabled. Do not put API keys, Railway credentials, production settings, or user tokens in Vite variables or build configuration.

The WebView uses AndroidX WebViewAssetLoader for the bundled `https://appassets.androidplatform.net` shell. Only that origin and the exact configured HTTPS remote origin are allowed for shell navigation and bridge messages. It rejects off-origin navigation and subresources, popups, file/content access, downloads, and mixed content. JavaScript is enabled only because the reviewed shell requires it; the only native entry point is an origin-restricted AndroidX WebMessage listener, not `JavascriptInterface`.

## Offline, update, and rollback

Every APK contains a responsive local HTML/CSS/JS fallback at `android/app/src/main/assets/web/` plus the exact content-addressed world artwork at the Android asset root. `WebViewAssetLoader` serves `/assets/` and `/worlds/` from that bundle. First launch or remote load/TLS/HTTP failure opens the complete local collection shell; gameplay and paired excavation/Museum imagery are not blocked by authentication or connectivity. Only a production build registers the same-origin service worker. The worker fetches every referenced hashed script/style and world-image asset, validates types, computes SHA-256 digests, stores content under digest-addressed keys, and advances the current pointer only after complete success. Offline reads verify stored digests; a failed or partial update preserves the previous pointer.

A web-only UI/presentation change can be deployed to the isolated development Railway service without replacing an installed APK when it remains compatible with the current v2 bridge and origin contract. A bridge change, native permission/capability, offline fallback asset change, notification listener, local database/storage, balance logic, billing, or other Android/device-only change requires a new APK. Roll back a bad web release by restoring the previous development web artifact; clients retain the prior complete shell until the restored version is fetched.

Development Railway service, its domain, database, and credentials must be isolated from production by the deployment owner. This code does not deploy, create Railway resources, inspect live services, or enable production. Validate service/build paths and device/offline behaviour in the authorized development environment only after Alfred's deferred gates are cleared.

## Validation map

- `web/src/App.test.tsx` checks onboarding, paired excavation/Museum imagery, energy/tile interaction, rarity presentation, and bounded bridge requests.
- `web/src/bridge.test.ts` rejects unknown versions, malformed IDs/types, extra fields and invalid dig requests while accepting legacy v2 state from older APKs.
- `web/src/service-worker.test.ts` checks initial cache priming, the complete world-art inventory, offline restart, fail-closed startup, and partial-update rollback.
- `server/test/shell.test.ts` checks shell headers/cache policy, metadata and health, hashed-only immutable caching, and traversal rejection.
- `android/app/src/test/.../MainActivityTest.kt` checks initial bundled startup, remote-failure policy, origin filtering, strict bridge parsing, and the native notification-settings intent.
- Run `pnpm --filter @afterchime/web test`, `pnpm --filter @afterchime/web build`, `pnpm --dir server test -- shell.test.ts`, repository privacy/format/typecheck/build gates, and Android tests on the isolated Afterchime builder. Android and Railway/device checks are manager-deferred for this implementation task.
