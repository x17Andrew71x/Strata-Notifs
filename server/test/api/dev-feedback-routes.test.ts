import { createHash } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import type { DatabaseClient } from "../../src/db/client.js";
import { createAccessToken } from "../../src/security/tokens.js";
import { testConfig } from "../test-config.js";

const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
afterEach(async () => Promise.all(apps.splice(0).map((app) => app.close())));

const adminKey = "development-controller-key-with-sufficient-entropy";
const enabledConfig = {
  ...testConfig,
  adminApiKeyHash: createHash("sha256").update(adminKey).digest("hex"),
  developmentModuleEnabled: true,
  deployedRevision: "a".repeat(40),
};
const database = {} as DatabaseClient;

describe("development feedback route boundary", () => {
  it("is absent outside the explicitly enabled development environment", async () => {
    const disabled = await buildApp({ config: testConfig, database });
    apps.push(disabled);
    expect(
      (await disabled.inject({ method: "GET", url: "/v1/dev-feedback/items" })).statusCode,
    ).toBe(404);

    const production = await buildApp({
      config: { ...enabledConfig, buildChannel: "prod", tokenAudience: "afterchime-prod" },
      database,
    });
    apps.push(production);
    expect(
      (await production.inject({ method: "GET", url: "/v1/dev-feedback/items" })).statusCode,
    ).toBe(404);
  });

  it("requires installation bearer auth and the independent exact admin key", async () => {
    const app = await buildApp({ config: enabledConfig, database });
    apps.push(app);

    expect((await app.inject({ method: "GET", url: "/v1/dev-feedback/items" })).statusCode).toBe(
      401,
    );
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/v1/dev-feedback/admin/claim",
          headers: { "x-afterchime-admin-key": `${adminKey}-wrong` },
          payload: {},
        })
      ).statusCode,
    ).toBe(401);
  });

  it("rejects expanded screenshot context before repository access", async () => {
    const app = await buildApp({ config: enabledConfig, database });
    apps.push(app);
    const token = createAccessToken({
      audience: enabledConfig.tokenAudience,
      installationId: "11111111-1111-4111-8111-111111111111",
      secret: enabledConfig.accessTokenSecret,
      userId: "22222222-2222-4222-8222-222222222222",
    });
    const response = await app.inject({
      method: "POST",
      url: "/v1/dev-feedback/items",
      headers: {
        authorization: `Bearer ${token}`,
        "idempotency-key": "33333333-3333-4333-8333-333333333333",
      },
      payload: {
        title: "Tighten the action",
        description: "Reduce the empty space above it.",
        context: {
          route: "today",
          shellRevision: "b".repeat(64),
          appVersion: "0.1.10-dev-debug",
          selection: {
            kind: "region",
            rect: { x: 1, y: 2, width: 20, height: 30 },
            viewportWidth: 360,
            viewportHeight: 800,
            devId: "",
            tag: "",
            role: "",
            accessibleLabel: "",
            visibleLabel: "",
            domPath: "",
          },
          arbitraryState: { token: "forbidden" },
        },
        screenshot: {
          mediaType: "image/webp",
          contentBase64: "AQIDBA==",
          sha256: "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
          width: 360,
          height: 800,
        },
      },
    });
    expect(response.statusCode).toBe(400);
  });
});
