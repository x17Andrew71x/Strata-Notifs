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

async function createWorker(fetcher: (input: RequestInfo | URL) => Promise<Response>) {
  const handlers = new Map<string, (event: WorkerEventLike) => void>();
  const store = new Map<string, Response>();
  const cache = {
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
  const self = {
    location: { origin },
    addEventListener: (type: string, handler: (event: WorkerEventLike) => void) =>
      handlers.set(type, handler),
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn() },
  };
  const script = await readFile(path.resolve(process.cwd(), "public/service-worker.js"), "utf8");
  runInNewContext(script, {
    self,
    caches: { open: async () => cache },
    fetch: fetcher,
    Response,
    URL,
    crypto: webcrypto,
  });
  return {
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

describe("last-known-good web shell", () => {
  it("primes the first validated online shell during activation for the next offline launch", async () => {
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

    const cached = await worker.navigate();
    expect(cached.status).toBe(200);
    expect(await cached.text()).toContain('id="root"');
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
});
