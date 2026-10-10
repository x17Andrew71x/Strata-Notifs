import { webcrypto } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { runInNewContext } from "node:vm";
import { describe, expect, it, vi } from "vitest";

type FetchEventLike = {
  request: { method: string; url: string; mode?: string };
  response?: Promise<Response>;
  respondWith(value: Promise<Response>): void;
};

type ExtendableEventLike = {
  completion?: Promise<unknown>;
  waitUntil(value: Promise<unknown>): void;
};

type WorkerEventLike = FetchEventLike | ExtendableEventLike;

const origin = "https://shell.example";
const entryHtml = (asset: string) =>
  `<!doctype html><div id="root"></div><script src="${asset}"></script>`;

type CacheStores = Map<string, Map<string, Response>>;

type WorkerOptions = {
  stores?: CacheStores;
  transformSource?: (source: string) => string;
};

async function createWorker(
  fetcher: (input: RequestInfo | URL) => Promise<Response>,
  options: WorkerOptions = {},
) {
  const handlers = new Map<string, (event: WorkerEventLike) => void>();
  const stores = options.stores || new Map<string, Map<string, Response>>();
  const cacheFor = (name: string) => {
    const store = stores.get(name) || new Map<string, Response>();
    stores.set(name, store);
    return {
      async match(key: RequestInfo | URL) {
        const response = store.get(
          new URL(typeof key === "string" ? key : key.toString(), origin).href,
        );
        return response?.clone();
      },
      async put(key: RequestInfo | URL, response: Response) {
        store.set(
          new URL(typeof key === "string" ? key : key.toString(), origin).href,
          response.clone(),
        );
      },
    };
  };
  const self = {
    location: { origin },
    addEventListener: (type: string, handler: (event: WorkerEventLike) => void) =>
      handlers.set(type, handler),
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn() },
  };
  const source = await readFile(path.resolve(process.cwd(), "public/service-worker.js"), "utf8");
  const script = options.transformSource ? options.transformSource(source) : source;
  const workerFetcher = async (input: RequestInfo | URL) => {
    const url = new URL(responseUrl(input), origin);
    const response = await fetcher(input);
    const match = url.pathname.match(/^\/worlds\/[a-z0-9-]+-[a-f0-9]{12}\.(jpg|webp)$/);
    if (!match) return response;
    if (!response.ok && response.status !== 404) return response;
    return imageResponse("image", match[1] === "webp" ? "image/webp" : "image/jpeg");
  };
  runInNewContext(script, {
    self,
    caches: {
      open: async (name: string) => cacheFor(name),
      delete: async (name: string) => stores.delete(name),
    },
    fetch: workerFetcher,
    Response,
    URL,
    crypto: webcrypto,
  });
  return {
    async install() {
      const event: ExtendableEventLike = {
        waitUntil(value: Promise<unknown>) {
          this.completion = value;
        },
      };
      handlers.get("install")?.(event);
      await event.completion;
    },
    async activate() {
      const event: ExtendableEventLike = {
        waitUntil(value: Promise<unknown>) {
          this.completion = value;
        },
      };
      handlers.get("activate")?.(event);
      await event.completion;
    },
    async navigate() {
      const event: FetchEventLike = {
        request: { method: "GET", url: `${origin}/`, mode: "navigate" },
        respondWith(value: Promise<Response>) {
          this.response = value;
        },
      };
      handlers.get("fetch")?.(event);
      return event.response as Promise<Response>;
    },
    async asset(pathname: string) {
      const event: FetchEventLike = {
        request: { method: "GET", url: `${origin}${pathname}` },
        respondWith(value: Promise<Response>) {
          this.response = value;
        },
      };
      handlers.get("fetch")?.(event);
      return event.response as Promise<Response>;
    },
    skipWaiting: self.skipWaiting,
    clientsClaim: self.clients.claim,
  };
}

function responseUrl(input: RequestInfo | URL): string {
  if (typeof input === "string") return input;
  if (input instanceof URL) return input.href;
  return input.url;
}

function assetResponse(body = "bundle") {
  const response = new Response(body, {
    headers: { "content-type": "text/javascript; charset=utf-8" },
  });
  Object.defineProperty(response, "type", { value: "basic" });
  return response;
}

function imageResponse(body = "image", contentType = "image/jpeg") {
  const response = new Response(body, { headers: { "content-type": contentType } });
  Object.defineProperty(response, "type", { value: "basic" });
  return response;
}

describe("last-known-good web shell", () => {
  it("serves a valid first-seen content-addressed image while the new worker fills its cache", async () => {
    const body = "image";
    const digest = Array.from(
      new Uint8Array(await webcrypto.subtle.digest("SHA-256", new TextEncoder().encode(body))),
    )
      .map((byte) => byte.toString(16).padStart(2, "0"))
      .join("");
    const worker = await createWorker(async () => new Response("missing", { status: 404 }));

    const response = await worker.asset(`/worlds/relic-first-load-${digest.slice(0, 12)}.webp`);

    expect(response.status).toBe(200);
    expect(await response.text()).toBe(body);
  });

  it("rejects a first-seen image whose bytes do not match its content-addressed path", async () => {
    const worker = await createWorker(async () => new Response("missing", { status: 404 }));

    const response = await worker.asset("/worlds/relic-first-load-000000000000.webp");

    expect(response.type).toBe("error");
  });

  it("primes the first validated online shell during activation for the next offline launch", async () => {
    let offline = false;
    const stores: CacheStores = new Map();
    const worker = await createWorker(
      async (input) => {
        if (offline) throw new TypeError("offline");
        const url = new URL(responseUrl(input), origin);
        if (url.pathname === "/")
          return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
            headers: { "content-type": "text/html" },
          });
        if (url.pathname === "/assets/main-AbCdEf123456.js") return assetResponse();
        return new Response("missing", { status: 404 });
      },
      { stores },
    );

    await worker.activate();
    const state = stores.get("afterchime-shell-v18")?.get(`${origin}/.afterchime/current`);
    expect(await state?.clone().json()).toMatchObject({ catalogRevision: 1 });
    offline = true;

    const cached = await worker.navigate();
    expect(cached.status).toBe(200);
    expect(await cached.text()).toContain('id="root"');
  });

  it("removes legacy app caches only after the current shell is complete", async () => {
    const stores: CacheStores = new Map([
      ["afterchime-shell-v7", new Map()],
      ["unrelated-cache", new Map()],
    ]);
    const worker = await createWorker(
      async (input) => {
        const url = new URL(responseUrl(input), origin);
        if (url.pathname === "/")
          return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
            headers: { "content-type": "text/html" },
          });
        if (url.pathname === "/assets/main-AbCdEf123456.js") return assetResponse();
        return new Response("missing", { status: 404 });
      },
      { stores },
    );

    await worker.activate();

    expect(stores.has("afterchime-shell-v7")).toBe(false);
    expect(stores.has("unrelated-cache")).toBe(true);
  });

  it("promotes only a complete digest-verified shell and serves it after an offline restart", async () => {
    let offline = false;
    const worker = await createWorker(async (input) => {
      if (offline) throw new TypeError("offline");
      const url = new URL(responseUrl(input), origin);
      if (url.pathname === "/")
        return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
          headers: { "content-type": "text/html" },
        });
      if (url.pathname === "/assets/main-AbCdEf123456.js") return assetResponse();
      return new Response("missing", { status: 404 });
    });
    const online = await worker.navigate();
    expect(online.status).toBe(200);
    offline = true;
    const cached = await worker.navigate();
    expect(cached.status).toBe(200);
    expect(await cached.text()).toContain('id="root"');
  });

  it("includes the exact authored world artwork in the verified offline shell", async () => {
    let offline = false;
    const worker = await createWorker(async (input) => {
      if (offline) throw new TypeError("offline");
      const url = new URL(responseUrl(input), origin);
      if (url.pathname === "/")
        return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
          headers: { "content-type": "text/html" },
        });
      if (url.pathname === "/assets/main-AbCdEf123456.js") return assetResponse();
      return new Response("missing", { status: 404 });
    });

    await worker.activate();
    offline = true;
    const artwork = await worker.asset(
      "/worlds/relic-dactylioceras-ammonite-museum-6a351e2bb9c1.webp",
    );
    expect(artwork.status).toBe(200);
    expect(artwork.headers.get("content-type")).toBe("image/webp");
    expect(await artwork.text()).toBe("image");
  });

  it("keeps the prior entry after an incomplete update and fails closed without a valid entry", async () => {
    let update = false;
    let offline = false;
    const worker = await createWorker(async (input) => {
      if (offline) throw new TypeError("offline");
      const url = new URL(responseUrl(input), origin);
      if (url.pathname === "/")
        return new Response(
          entryHtml(update ? "/assets/new-AbCdEf123456.js" : "/assets/old-AbCdEf123456.js"),
          { headers: { "content-type": "text/html" } },
        );
      return update ? new Response("missing", { status: 503 }) : assetResponse("old");
    });
    expect((await worker.navigate()).status).toBe(200);
    update = true;
    expect((await worker.navigate()).status).toBe(200);
    offline = true;
    expect(await (await worker.navigate()).text()).toContain("old-AbCdEf123456.js");

    const empty = await createWorker(async () => {
      throw new TypeError("offline");
    });
    expect((await empty.navigate()).type).toBe("error");
  });

  it("does not replace a complete prior shell when an updated worker cannot verify its own shell", async () => {
    const stores: CacheStores = new Map();
    let offline = false;
    const fetcher = async (input: RequestInfo | URL) => {
      if (offline) throw new TypeError("offline");
      const url = new URL(responseUrl(input), origin);
      if (url.pathname === "/")
        return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
          headers: { "content-type": "text/html" },
        });
      if (url.pathname === "/assets/main-AbCdEf123456.js") return assetResponse("prior");
      return new Response("missing", { status: 404 });
    };
    const prior = await createWorker(fetcher, {
      stores,
      transformSource: (source) =>
        source
          .replace(
            'const CACHE_NAME = "afterchime-shell-v18";',
            'const CACHE_NAME = "afterchime-shell-v17";',
          )
          .replace('  "afterchime-shell-v17",\n', ""),
    });
    await prior.activate();
    offline = true;

    const update = await createWorker(fetcher, { stores });
    await update.install();
    expect(update.skipWaiting).not.toHaveBeenCalled();
    await update.activate();
    expect(update.clientsClaim).not.toHaveBeenCalled();

    const cached = await update.navigate();
    expect(cached.status).toBe(200);
    expect(await cached.text()).toContain("main-AbCdEf123456.js");
  });

  it("rejects a successful but digest-mismatched asset response in favour of the verified cache", async () => {
    let tampered = false;
    const worker = await createWorker(async (input) => {
      const url = new URL(responseUrl(input), origin);
      if (url.pathname === "/")
        return new Response(entryHtml("/assets/main-AbCdEf123456.js"), {
          headers: { "content-type": "text/html" },
        });
      if (url.pathname === "/assets/main-AbCdEf123456.js")
        return assetResponse(tampered ? "tampered" : "verified");
      return new Response("missing", { status: 404 });
    });

    await worker.navigate();
    tampered = true;

    const response = await worker.asset("/assets/main-AbCdEf123456.js");
    expect(await response.text()).toBe("verified");
  });
});
