import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { testConfig } from "./test-config.js";

const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
const roots: string[] = [];
afterEach(async () => {
  await Promise.all(apps.splice(0).map((app) => app.close()));
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});
async function shellRoot(): Promise<string> {
  const root = await mkdtemp(path.join(os.tmpdir(), "afterchime-shell-"));
  roots.push(root);
  await mkdir(path.join(root, "assets"));
  await writeFile(
    path.join(root, "index.html"),
    '<!doctype html><script src="/assets/main-AbCdEf123456.js"></script>',
  );
  await writeFile(
    path.join(root, "service-worker.js"),
    "self.addEventListener('fetch', () => {});",
  );
  await writeFile(path.join(root, "assets", "main-AbCdEf123456.js"), "safe asset");
  return root;
}

describe("web shell routes", () => {
  it("serves the onboarding entry with a strict CSP and revalidation cache policy", async () => {
    const app = await buildApp({ config: testConfig, webRoot: await shellRoot() });
    apps.push(app);
    const response = await app.inject({ method: "GET", url: "/" });
    expect(response.statusCode).toBe(200);
    expect(response.body).toContain("doctype html");
    expect(response.headers["cache-control"]).toBe("no-cache, must-revalidate");
    expect(response.headers["content-security-policy"]).toContain("script-src 'self'");
    expect(response.headers["content-security-policy"]).not.toContain("unsafe-inline");
  });

  it("publishes only non-secret protocol metadata and explicit health", async () => {
    const app = await buildApp({ config: testConfig, webRoot: await shellRoot() });
    apps.push(app);
    const metadata = await app.inject({ method: "GET", url: "/shell/metadata" });
    expect(metadata.json()).toEqual({ shellVersion: 1, bridgeVersion: 1, entry: "/" });
    expect(metadata.headers["cache-control"]).toBe("no-cache, must-revalidate");
    expect((await app.inject({ method: "GET", url: "/shell/health" })).json()).toEqual({
      status: "ok",
      shell: true,
    });
  });

  it("reserves immutable caching for hashed build assets and rejects unsafe paths", async () => {
    const root = await shellRoot();
    await writeFile(path.join(root, "assets", "unhashed.js"), "not content addressed");
    const app = await buildApp({ config: testConfig, webRoot: root });
    apps.push(app);
    const asset = await app.inject({ method: "GET", url: "/assets/main-AbCdEf123456.js" });
    expect(asset.statusCode).toBe(200);
    expect(asset.headers["cache-control"]).toBe("public, max-age=31536000, immutable");
    const unhashed = await app.inject({ method: "GET", url: "/assets/unhashed.js" });
    expect(unhashed.statusCode).toBe(404);
    expect(unhashed.headers["cache-control"]).not.toContain("immutable");
    expect(
      (await app.inject({ method: "GET", url: "/assets/%2e%2e%2findex.html" })).statusCode,
    ).toBe(404);
  });
});
