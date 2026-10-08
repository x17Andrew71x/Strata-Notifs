const CACHE_NAME = "afterchime-shell-v8";
const LEGACY_CACHE_NAMES = [
  "afterchime-shell-v7",
  "afterchime-shell-v6",
  "afterchime-shell-v5",
  "afterchime-shell-v4",
];
const ENTRY = "/";
const STATE_KEY = "/.afterchime/current";
const ASSET_PATTERN = /^\/assets\/[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css)$/;

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
  const references = [
    ...html.matchAll(
      /(?:src|href)="(\/assets\/[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css))"/g,
    ),
  ].map((match) => match[1]);
  if (references.length === 0 || references.length > 32) return false;

  const assets = [];
  for (const pathname of new Set(references)) {
    const assetUrl = new URL(pathname, self.location.origin);
    const asset = await fetch(assetUrl, { credentials: "same-origin", redirect: "error" });
    const contentType = asset.headers.get("content-type") || "";
    if (
      !asset.ok ||
      asset.type !== "basic" ||
      !["text/javascript", "application/javascript", "text/css"].some((type) =>
        contentType.startsWith(type),
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
    new Response(JSON.stringify({ entryKey, entryDigest, assets: assetState }), {
      headers: { "content-type": "application/json" },
    }),
  );
  return true;
}

async function assetWithOfflineFallback(request) {
  const cached = await cachedAsset(request.url);
  try {
    const response = await fetch(request, { credentials: "same-origin", redirect: "error" });
    if (cached && (await isExpectedAsset(response, cached.digest))) return response;
    return cached?.response || Response.error();
  } catch (_) {
    return cached?.response || Response.error();
  }
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
    !["text/javascript", "application/javascript", "text/css"].some((type) =>
      contentType.startsWith(type),
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
      typeof state.entryKey === "string" &&
      state.entryKey.startsWith(`${self.location.origin}/.afterchime/`) &&
      /^[a-f0-9]{64}$/.test(state.entryDigest) &&
      Array.isArray(state.assets) &&
      state.assets.length > 0 &&
      state.assets.length <= 32 &&
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
