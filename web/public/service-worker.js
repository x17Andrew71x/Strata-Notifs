const CACHE_NAME = "afterchime-shell-v17";
const CATALOG_REVISION = 1;
const LEGACY_CACHE_NAMES = [
  "afterchime-shell-v16",
  "afterchime-shell-v15",
  "afterchime-shell-v14",
  "afterchime-shell-v13",
  "afterchime-shell-v12",
  "afterchime-shell-v11",
  "afterchime-shell-v10",
  "afterchime-shell-v9",
  "afterchime-shell-v8",
  "afterchime-shell-v7",
  "afterchime-shell-v6",
  "afterchime-shell-v5",
  "afterchime-shell-v4",
];
const ENTRY = "/";
const STATE_KEY = "/.afterchime/current";
const ASSET_PATTERN =
  /^(?:\/assets\/[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css)|\/worlds\/[a-z0-9-]{1,128}-[a-f0-9]{12}\.(?:jpg|webp))$/;
const WORLD_ASSETS = [
  "/worlds/relic-dactylioceras-ammonite-museum-6a351e2bb9c1.webp",
  "/worlds/relic-dactylioceras-ammonite-excavation-e191e90964c1.webp",
  "/worlds/relic-belemnite-rostra-museum-f5aba5efde48.webp",
  "/worlds/relic-belemnite-rostra-excavation-8443912d863f.webp",
  "/worlds/relic-spiriferid-brachiopod-museum-004f488c0763.webp",
  "/worlds/relic-spiriferid-brachiopod-excavation-c4b9d6a69e27.webp",
  "/worlds/relic-gryphaea-oyster-museum-effeafad73d8.webp",
  "/worlds/relic-gryphaea-oyster-excavation-59a9be2a1cc3.webp",
  "/worlds/relic-crinoid-columnals-museum-29e80d02b875.webp",
  "/worlds/relic-crinoid-columnals-excavation-1de79a61ca50.webp",
  "/worlds/relic-rugose-horn-coral-museum-be8aaef85cef.webp",
  "/worlds/relic-rugose-horn-coral-excavation-4ace1de1f75f.webp",
  "/worlds/relic-lamniform-shark-tooth-museum-d30b4b4049c8.webp",
  "/worlds/relic-lamniform-shark-tooth-excavation-12dd64c30e62.webp",
  "/worlds/relic-carbonised-fern-frond-museum-cf7f16401d3c.webp",
  "/worlds/relic-carbonised-fern-frond-excavation-bf0f796f5125.webp",
  "/worlds/relic-domal-stromatolite-museum-d04e006b69cc.webp",
  "/worlds/relic-domal-stromatolite-excavation-d472af058429.webp",
  "/worlds/relic-echinocorys-echinoid-museum-b7d1d344cd30.webp",
  "/worlds/relic-echinocorys-echinoid-excavation-5d74c69e7db7.webp",
  "/worlds/relic-articulated-trilobite-museum-3a5690b7a828.webp",
  "/worlds/relic-articulated-trilobite-excavation-2e72ac83c032.webp",
  "/worlds/relic-articulated-fossil-fish-museum-6f4bfdf3d18a.webp",
  "/worlds/relic-articulated-fossil-fish-excavation-cfa630aac0d6.webp",
  "/worlds/relic-complete-starfish-museum-2ff6426b0f62.webp",
  "/worlds/relic-complete-starfish-excavation-ccc514baeb39.webp",
  "/worlds/relic-articulated-fossil-crab-museum-6ec3a14500c2.webp",
  "/worlds/relic-articulated-fossil-crab-excavation-d57c39d5c9d0.webp",
  "/worlds/relic-insect-amber-museum-e0486f183aa8.webp",
  "/worlds/relic-insect-amber-excavation-9149c88a34ea.webp",
  "/worlds/relic-dinosaur-embryo-egg-museum-db0ceecfe047.webp",
  "/worlds/relic-dinosaur-embryo-egg-excavation-da03275b4f32.webp",
  "/worlds/relic-archaeopteryx-slab-museum-492a72012ed0.webp",
  "/worlds/relic-archaeopteryx-slab-excavation-23601b1f8a98.webp",
  "/worlds/field-verdant-crown-16cd41ad4394.jpg",
  "/worlds/field-tidal-archive-7870aabaf855.jpg",
  "/worlds/field-cinder-vale-baba2fad26b5.jpg",
  "/worlds/control-canopy-frequency-264930918583.jpg",
  "/worlds/control-pelagic-channel-679685abf5a2.jpg",
  "/worlds/control-lunar-silence-af1ea8ce38ec.jpg",
  "/worlds/atlas-hollow-range-40fffccf4155.jpg",
  "/worlds/atlas-ember-roads-fe87b2c52620.jpg",
  "/worlds/atlas-white-quarry-40e3ba96733d.jpg",
];

self.addEventListener("install", (event) => event.waitUntil(installWorker()));
self.addEventListener("activate", (event) => event.waitUntil(activateWorker()));

async function installWorker() {
  const cache = await caches.open(CACHE_NAME);
  try {
    if (await fetchAndPromoteShell(cache)) await self.skipWaiting();
  } catch (_) {
    // Keep the prior controller until this worker has a complete verified shell.
  }
}

async function activateWorker() {
  const cache = await caches.open(CACHE_NAME);
  let promoted = false;
  try {
    promoted = await fetchAndPromoteShell(cache);
  } catch (_) {
    // An offline first launch falls through to Android's bundled shell.
  }
  if (promoted || (await hasCompleteCurrentShell(cache))) {
    await Promise.all(LEGACY_CACHE_NAMES.map((name) => caches.delete(name)));
    await self.clients.claim();
  }
}

async function fetchAndPromoteShell(cache) {
  const response = await fetch(ENTRY, {
    cache: "no-cache",
    credentials: "same-origin",
    redirect: "error",
  });
  return promoteCompleteShell(response, cache);
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) return;
  if (url.pathname === ENTRY && request.mode === "navigate") {
    event.respondWith(entryWithLastKnownGood(request));
  } else if (ASSET_PATTERN.test(url.pathname)) {
    event.respondWith(assetWithOfflineFallback(request));
  }
});

async function entryWithLastKnownGood(request) {
  const cache = await caches.open(CACHE_NAME);
  try {
    const response = await fetch(request, {
      cache: "no-cache",
      credentials: "same-origin",
      redirect: "error",
    });
    if (await promoteCompleteShell(response.clone(), cache)) return response;
  } catch (_) {
    // Use only the last entry whose complete script/style set was cached and verified.
  }
  return (await cachedEntry()) || Response.error();
}

async function promoteCompleteShell(response, cache) {
  if (!response.ok || !/^text\/html\b/i.test(response.headers.get("content-type") || ""))
    return false;
  const html = await response.clone().text();
  if (!html.includes('id="root"') || html.length > 512 * 1024) return false;
  const entryReferences = [
    ...html.matchAll(
      /(?:src|href)="(\/assets\/[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css))"/g,
    ),
  ].map((match) => match[1]);
  if (entryReferences.length === 0 || entryReferences.length > 32) return false;
  const references = [...new Set([...entryReferences, ...WORLD_ASSETS])];

  const assets = [];
  for (const pathname of new Set(references)) {
    const assetUrl = new URL(pathname, self.location.origin);
    const asset = await fetch(assetUrl, { credentials: "same-origin", redirect: "error" });
    const contentType = asset.headers.get("content-type") || "";
    if (
      !asset.ok ||
      asset.type !== "basic" ||
      !["text/javascript", "application/javascript", "text/css", "image/jpeg", "image/webp"].some(
        (type) => contentType.startsWith(type),
      )
    )
      return false;
    const bytes = await asset.clone().arrayBuffer();
    assets.push({ pathname, response: asset, digest: await sha256(bytes) });
  }

  const entryBytes = await response.clone().arrayBuffer();
  const entryDigest = await sha256(entryBytes);
  const entryKey = versionedKey("entry", entryDigest);
  const assetState = assets.map(({ pathname, digest }) => ({ pathname, digest }));

  // Store complete, digest-addressed content before atomically advancing the current pointer.
  for (const asset of assets) {
    await cache.put(versionedKey(asset.pathname, asset.digest), asset.response.clone());
  }
  await cache.put(entryKey, response.clone());
  await cache.put(
    STATE_KEY,
    new Response(
      JSON.stringify({
        catalogRevision: CATALOG_REVISION,
        entryKey,
        entryDigest,
        assets: assetState,
      }),
      {
        headers: { "content-type": "application/json" },
      },
    ),
  );
  return true;
}

async function assetWithOfflineFallback(request) {
  const cached = await cachedAsset(request.url);
  try {
    const response = await fetch(request, { credentials: "same-origin", redirect: "error" });
    if (cached && (await isExpectedAsset(response, cached.digest))) return response;
    if (!cached && (await isValidFirstSeenContentAddressedImage(response, request.url))) {
      return response;
    }
    return cached?.response || Response.error();
  } catch (_) {
    return cached?.response || Response.error();
  }
}

async function isValidFirstSeenContentAddressedImage(response, assetUrl) {
  const match = new URL(assetUrl).pathname.match(/-([a-f0-9]{12})\.(jpg|webp)$/);
  const contentType = response.headers.get("content-type") || "";
  const expectedContentType = match?.[2] === "webp" ? "image/webp" : "image/jpeg";
  if (
    !match ||
    !response.ok ||
    response.type !== "basic" ||
    !contentType.startsWith(expectedContentType)
  ) {
    return false;
  }
  return (await sha256(await response.clone().arrayBuffer())).startsWith(match[1]);
}

async function cachedEntry() {
  for (const { cache, state } of await validCacheStates()) {
    const entry = await cache.match(state.entryKey);
    if (entry && (await sha256(await entry.clone().arrayBuffer())) === state.entryDigest)
      return entry;
  }
  return null;
}

async function hasCompleteCurrentShell(cache) {
  const state = await readCurrentState(cache);
  if (!state) return false;
  const entry = await cache.match(state.entryKey);
  if (!entry || (await sha256(await entry.clone().arrayBuffer())) !== state.entryDigest)
    return false;
  for (const asset of state.assets) {
    const response = await cache.match(versionedKey(asset.pathname, asset.digest));
    if (!response || (await sha256(await response.clone().arrayBuffer())) !== asset.digest)
      return false;
  }
  return true;
}

async function cachedAsset(assetUrl) {
  for (const { cache, state } of await validCacheStates()) {
    const record = state.assets.find(
      (asset) => new URL(asset.pathname, self.location.origin).href === assetUrl,
    );
    if (!record) continue;
    const response = await cache.match(versionedKey(record.pathname, record.digest));
    if (response && (await sha256(await response.clone().arrayBuffer())) === record.digest) {
      return { response, digest: record.digest };
    }
  }
  return null;
}

async function validCacheStates() {
  const states = [];
  const names = new Set([CACHE_NAME, ...LEGACY_CACHE_NAMES]);
  for (const name of names) {
    const cache = await caches.open(name);
    const state = await readCurrentState(cache);
    if (state) states.push({ cache, state });
  }
  return states;
}

async function isExpectedAsset(response, digest) {
  const contentType = response.headers.get("content-type") || "";
  if (
    !response.ok ||
    response.type !== "basic" ||
    !["text/javascript", "application/javascript", "text/css", "image/jpeg", "image/webp"].some(
      (type) => contentType.startsWith(type),
    )
  )
    return false;
  return (await sha256(await response.clone().arrayBuffer())) === digest;
}

async function readCurrentState(cache) {
  const response = await cache.match(STATE_KEY);
  if (!response) return null;
  try {
    const state = await response.json();
    if (
      state.catalogRevision === CATALOG_REVISION &&
      typeof state.entryKey === "string" &&
      state.entryKey.startsWith(`${self.location.origin}/.afterchime/`) &&
      /^[a-f0-9]{64}$/.test(state.entryDigest) &&
      Array.isArray(state.assets) &&
      state.assets.length > 0 &&
      state.assets.length <= 320 &&
      state.assets.every(
        (asset) => ASSET_PATTERN.test(asset.pathname) && /^[a-f0-9]{64}$/.test(asset.digest),
      )
    )
      return state;
  } catch (_) {
    // Invalid state cannot authorize cache reads.
  }
  return null;
}

function versionedKey(pathname, digest) {
  return new URL(`/.afterchime/${digest}/${encodeURIComponent(pathname)}`, self.location.origin);
}

async function sha256(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
