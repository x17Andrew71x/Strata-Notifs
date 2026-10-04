const CACHE_NAME = "afterchime-shell-v3";
const ENTRY = "/";
const ASSET = /^\/assets\/[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css)$/;

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      try {
        const cache = await caches.open(CACHE_NAME);
        const response = await fetch(ENTRY, {
          cache: "no-cache",
          credentials: "same-origin",
          redirect: "error",
        });
        await cacheCompleteShell(response, cache);
      } catch (_) {
        // The bundled Android shell remains available when first-install caching cannot complete.
      }
      await self.skipWaiting();
    })(),
  );
});
self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const key of await caches.keys()) {
        if (key.startsWith("afterchime-shell-") && key !== CACHE_NAME) await caches.delete(key);
      }
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) return;
  if (url.pathname === ENTRY && request.mode === "navigate") {
    event.respondWith(entryWithLastKnownGood(request));
  } else if (ASSET.test(url.pathname)) {
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
    if (await cacheCompleteShell(response.clone(), cache)) return response;
  } catch (_) {
    // Use only the last entry whose complete script/style set was cached.
  }
  return (await cache.match(new URL(ENTRY, self.location.origin))) || Response.error();
}

async function cacheCompleteShell(response, cache) {
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
  const pending = [];
  for (const path of new Set(references)) {
    const assetUrl = new URL(path, self.location.origin);
    const cached = await cache.match(assetUrl);
    if (cached) continue;
    const asset = await fetch(assetUrl, { credentials: "same-origin", redirect: "error" });
    if (
      !asset.ok ||
      !["text/javascript", "application/javascript", "text/css"].some((type) =>
        (asset.headers.get("content-type") || "").startsWith(type),
      )
    )
      return false;
    pending.push([path, await responseForCache(asset)]);
  }
  for (const [assetPath, asset] of pending)
    await cache.put(new URL(assetPath, self.location.origin), asset);
  await cache.put(new URL(ENTRY, self.location.origin), await responseForCache(response));
  return true;
}

async function assetWithOfflineFallback(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  if (cached) return cached;
  try {
    const response = await fetch(request, { credentials: "same-origin", redirect: "error" });
    if (response.ok && response.type === "basic") {
      await cache.put(request, await responseForCache(response.clone()));
    }
    return response;
  } catch (_) {
    return Response.error();
  }
}

async function responseForCache(response) {
  const headers = new Headers(response.headers);
  headers.delete("content-encoding");
  headers.delete("content-length");
  return new Response(await response.arrayBuffer(), {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
