import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { verifyAccessToken } from "../../src/security/tokens.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";
import { testConfig } from "../test-config.js";

const postgresConfig = loadPostgresTestConfig();
const describePostgres = postgresConfig ? describe : describe.skip;
const registration = {
  appVersion: "0.2.0-dev",
  device: {
    androidApiLevel: 35,
    deviceClass: "phone",
  },
};

describePostgres("installation registration API", () => {
  const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
  let admin: DatabaseClient;
  let harness: PostgresTestHarness;
  let runtime: DatabaseClient;

  beforeAll(async () => {
    if (!postgresConfig) {
      throw new Error("PostgreSQL test configuration is required for this suite");
    }
    harness = await PostgresTestHarness.create(postgresConfig);
    await migrateDatabase(harness.migrationDatabaseUrl);
    const adminUrl = new URL(harness.adminDatabaseUrl);
    adminUrl.pathname = "/afterchime_test";
    admin = createSqlClient(adminUrl.toString());
    runtime = createSqlClient(harness.runtimeDatabaseUrl);
  }, 30_000);

  afterEach(async () => {
    await Promise.all(apps.splice(0).map((app) => app.close()));
  });

  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await admin?.end({ timeout: 5 });
    await harness?.close();
  });

  async function appForDatabase(): Promise<Awaited<ReturnType<typeof buildApp>>> {
    const app = await buildApp({
      config: {
        ...testConfig,
        databaseUrl: harness.runtimeDatabaseUrl,
      },
      database: runtime,
    });
    apps.push(app);
    return app;
  }

  it("registers only reduced device metadata, keeps the refresh secret hashed, and replays an idempotent registration without another installation", async () => {
    const app = await appForDatabase();
    const idempotencyKey = randomUUID();

    const created = await app.inject({
      headers: { "idempotency-key": idempotencyKey },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });

    expect(created.statusCode).toBe(201);
    const createdBody = created.json();
    expect(createdBody).toEqual({
      accessToken: expect.any(String),
      expiresInSeconds: 900,
      installationId: expect.any(String),
      refreshToken: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/),
      tokenType: "Bearer",
    });
    expect(
      verifyAccessToken({
        audience: "afterchime-dev",
        secret: testConfig.accessTokenSecret,
        token: createdBody.accessToken,
      }),
    ).toMatchObject({ installationId: createdBody.installationId });
    expect(() =>
      verifyAccessToken({
        audience: "afterchime-prod",
        secret: testConfig.accessTokenSecret,
        token: createdBody.accessToken,
      }),
    ).toThrow("token_audience_invalid");

    const repeated = await app.inject({
      headers: { "idempotency-key": idempotencyKey },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });

    expect(repeated.statusCode).toBe(200);
    expect(repeated.json()).toMatchObject({
      installationId: createdBody.installationId,
      refreshToken: createdBody.refreshToken,
    });
    const [stored] = await admin<
      Readonly<{
        installations: string;
        raw_secret_count: string;
        refresh_tokens: string;
      }>[]
    >`
      SELECT
        (SELECT count(*)::text FROM installations) AS installations,
        (SELECT count(*)::text FROM auth_refresh_tokens) AS refresh_tokens,
        (SELECT count(*)::text FROM auth_refresh_tokens WHERE token_hash = ${createdBody.refreshToken}) AS raw_secret_count
    `;
    expect(stored).toEqual({ installations: "1", raw_secret_count: "0", refresh_tokens: "1" });
  });

  it("rejects malformed, oversized, identifying, and client-selected production metadata", async () => {
    const app = await appForDatabase();

    for (const [index, payload] of [
      { ...registration, appVersion: "x".repeat(49) },
      { ...registration, buildChannel: "prod" },
      { ...registration, device: { ...registration.device, hardwareId: "not-accepted" } },
      { ...registration, device: { ...registration.device, androidApiLevel: 25 } },
    ].entries()) {
      const response = await app.inject({
        headers: { "idempotency-key": randomUUID() },
        method: "POST",
        payload,
        remoteAddress: `127.0.1.${index + 1}`,
        url: "/v1/installations",
      });
      expect(response.statusCode).toBe(400);
    }
  });

  it("rate limits registration attempts before they can create unbounded identities", async () => {
    const app = await appForDatabase();

    for (let attempt = 0; attempt < 3; attempt += 1) {
      const response = await app.inject({
        headers: { "idempotency-key": randomUUID() },
        method: "POST",
        payload: registration,
        url: "/v1/installations",
      });
      expect(response.statusCode).toBe(201);
    }
    const limited = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    expect(limited.statusCode).toBe(429);
  });

  it("rotates refresh-token families, detects replay, and revokes the family at logout", async () => {
    const app = await appForDatabase();
    const legacyRefresh = await app.inject({ method: "POST", url: "/v1/sessions/refresh" });
    const legacyLogout = await app.inject({ method: "DELETE", url: "/v1/sessions" });
    expect(legacyRefresh.statusCode).toBe(404);
    expect(legacyLogout.statusCode).toBe(404);
    const created = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    const initial = created.json();

    const rotated = await app.inject({
      method: "POST",
      payload: { refreshToken: initial.refreshToken },
      url: "/v1/auth/refresh",
    });
    expect(rotated.statusCode).toBe(200);
    const current = rotated.json();
    expect(current.refreshToken).not.toBe(initial.refreshToken);

    const replay = await app.inject({
      method: "POST",
      payload: { refreshToken: initial.refreshToken },
      url: "/v1/auth/refresh",
    });
    expect(replay.statusCode).toBe(401);
    const familyRevoked = await app.inject({
      method: "POST",
      payload: { refreshToken: current.refreshToken },
      url: "/v1/auth/refresh",
    });
    expect(familyRevoked.statusCode).toBe(401);

    const another = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    const anotherSession = another.json();
    const logout = await app.inject({
      method: "POST",
      payload: { refreshToken: anotherSession.refreshToken },
      url: "/v1/auth/logout",
    });
    expect(logout.statusCode).toBe(204);
    const afterLogout = await app.inject({
      method: "POST",
      payload: { refreshToken: anotherSession.refreshToken },
      url: "/v1/auth/refresh",
    });
    expect(afterLogout.statusCode).toBe(401);
  });
});
