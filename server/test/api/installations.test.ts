import { createHash, randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { hashRefreshSecret, verifyAccessToken } from "../../src/security/tokens.js";
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
const trustAdminKey = "installation-trust-admin-key-with-sufficient-entropy";
const trustAdminKeyHash = createHash("sha256").update(trustAdminKey).digest("hex");

describePostgres("installation registration API", () => {
  const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
  let admin: DatabaseClient;
  let harness: PostgresTestHarness;
  let runtime: DatabaseClient;
  let runtimeB: DatabaseClient;

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
    runtimeB = createSqlClient(harness.runtimeDatabaseUrl);
  }, 30_000);

  afterEach(async () => {
    await Promise.all(apps.splice(0).map((app) => app.close()));
  });

  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await runtimeB?.end({ timeout: 5 });
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
        trusted_installations: string;
        unverified_installations: string;
        raw_secret_count: string;
        refresh_tokens: string;
      }>[]
    >`
      SELECT
        (SELECT count(*)::text FROM installations WHERE id = ${createdBody.installationId} AND is_trusted = true) AS trusted_installations,
        (SELECT count(*)::text FROM installations WHERE id = ${createdBody.installationId} AND is_trusted = false) AS unverified_installations,
        (SELECT count(*)::text FROM auth_refresh_tokens) AS refresh_tokens,
        (SELECT count(*)::text FROM auth_refresh_tokens WHERE token_hash = ${createdBody.refreshToken}) AS raw_secret_count
    `;
    expect(stored).toEqual({
      trusted_installations: "0",
      unverified_installations: "1",
      raw_secret_count: "0",
      refresh_tokens: "1",
    });
  });

  it("keeps public registrations unverified until an audited admin trust grant", async () => {
    const app = await buildApp({
      config: {
        ...testConfig,
        adminApiKeyHash: trustAdminKeyHash,
        databaseUrl: harness.runtimeDatabaseUrl,
      },
      database: runtime,
    });
    apps.push(app);
    const created = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    const installationId = created.json().installationId as string;

    const untrusted = await app.inject({
      headers: { "x-admin-key": trustAdminKey },
      method: "GET",
      url: `/v1/admin/installations/${installationId}/trust`,
    });
    expect(untrusted.statusCode).toBe(200);
    expect(untrusted.json()).toEqual({
      installationId,
      profileTrusted: false,
      source: null,
      grantedAt: null,
      trustStatus: "untrusted",
    });

    const denied = await app.inject({
      headers: { "x-admin-key": `${trustAdminKey}-wrong` },
      method: "POST",
      payload: { source: "owner_admin" },
      url: `/v1/admin/installations/${installationId}/trust`,
    });
    expect(denied.statusCode).toBe(401);

    const trusted = await app.inject({
      headers: { "x-admin-key": trustAdminKey },
      method: "POST",
      payload: { source: "owner_admin" },
      url: `/v1/admin/installations/${installationId}/trust`,
    });
    expect(trusted.statusCode).toBe(200);
    expect(trusted.json()).toEqual({ installationId, trustStatus: "trusted" });

    const readback = await app.inject({
      headers: { "x-admin-key": trustAdminKey },
      method: "GET",
      url: `/v1/admin/installations/${installationId}/trust`,
    });
    expect(readback.statusCode).toBe(200);
    expect(readback.json()).toEqual({
      installationId,
      profileTrusted: true,
      source: "owner_admin",
      grantedAt: expect.any(String),
      trustStatus: "trusted",
    });

    const [state] = await admin<
      Readonly<{ grant_count: string; installation_trusted: boolean; user_trusted: boolean }>[]
    >`
      SELECT
        installation.is_trusted AS installation_trusted,
        profile.is_trusted AS user_trusted,
        (SELECT count(*)::text FROM installation_trust_grants WHERE installation_id = installation.id) AS grant_count
      FROM installations AS installation
      INNER JOIN users AS profile ON profile.id = installation.user_id
      WHERE installation.id = ${installationId}
    `;
    expect(state).toEqual({
      grant_count: "1",
      installation_trusted: true,
      user_trusted: true,
    });
  });

  it("records a server-authored general app open without requiring community consent", async () => {
    const app = await appForDatabase();
    const unauthorized = await app.inject({ method: "POST", url: "/v1/profile/open" });
    expect(unauthorized.statusCode).toBe(401);
    expect(unauthorized.json()).toEqual({ error: "unauthorized" });

    const created = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    expect(created.statusCode).toBe(201);
    const session = created.json() as Readonly<{ accessToken: string; installationId: string }>;
    const claims = verifyAccessToken({
      audience: "afterchime-dev",
      secret: testConfig.accessTokenSecret,
      token: session.accessToken,
    });
    const previous = new Date("2026-01-01T00:00:00.000Z");
    await admin`UPDATE users SET last_opened = ${previous} WHERE id = ${claims.userId}`;
    const beforeRequest = new Date();

    const opened = await app.inject({
      headers: { authorization: ["Bear", "er ", session.accessToken].join("") },
      method: "POST",
      url: "/v1/profile/open",
    });

    expect(opened.statusCode).toBe(204);
    const [profile] = await admin<Readonly<{ last_opened: Date }>[]>`
      SELECT last_opened FROM users WHERE id = ${claims.userId}
    `;
    expect(profile?.last_opened.getTime()).toBeGreaterThanOrEqual(beforeRequest.getTime());
    expect(profile?.last_opened.getTime()).toBeLessThanOrEqual(Date.now());
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

  it("serializes rotation against rotation and logout across physical database connections", async () => {
    const app = await appForDatabase();
    const first = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    const firstSession = first.json() as Readonly<{ installationId: string; refreshToken: string }>;
    const firstHash = hashRefreshSecret(firstSession.refreshToken, testConfig.refreshTokenPepper);
    const expiry = new Date(Date.now() + 24 * 60 * 60 * 1000);
    const firstReplacement = randomUUID();
    const secondReplacement = randomUUID();

    const [firstRotation, secondRotation] = await Promise.all([
      runtime<Readonly<{ outcome: string }>[]>`
        SELECT outcome FROM public.afterchime_rotate_refresh_token(
          ${firstHash}, ${firstReplacement}, ${"a".repeat(64)}, ${expiry}
        )
      `,
      runtimeB<Readonly<{ outcome: string }>[]>`
        SELECT outcome FROM public.afterchime_rotate_refresh_token(
          ${firstHash}, ${secondReplacement}, ${"b".repeat(64)}, ${expiry}
        )
      `,
    ]);
    expect([firstRotation[0]?.outcome, secondRotation[0]?.outcome].sort()).toEqual([
      "replayed",
      "rotated",
    ]);
    const [rotationState] = await admin<Readonly<{ active: string; children: string }>[]>`
      SELECT
        count(*) FILTER (WHERE revoked_at IS NULL)::text AS active,
        count(*) FILTER (WHERE id IN (${firstReplacement}, ${secondReplacement}))::text AS children
      FROM auth_refresh_tokens
      WHERE installation_id = ${firstSession.installationId}
    `;
    expect(rotationState).toEqual({ active: "0", children: "1" });

    const second = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    const secondSession = second.json() as Readonly<{
      installationId: string;
      refreshToken: string;
    }>;
    const secondHash = hashRefreshSecret(secondSession.refreshToken, testConfig.refreshTokenPepper);
    const replacement = randomUUID();
    await Promise.all([
      runtime`
        SELECT outcome FROM public.afterchime_rotate_refresh_token(
          ${secondHash}, ${replacement}, ${"c".repeat(64)}, ${expiry}
        )
      `,
      runtimeB`SELECT public.afterchime_revoke_refresh_token(${secondHash})`,
    ]);
    const [logoutState] = await admin<Readonly<{ active: string }>[]>`
      SELECT count(*) FILTER (WHERE revoked_at IS NULL)::text AS active
      FROM auth_refresh_tokens
      WHERE installation_id = ${secondSession.installationId}
    `;
    expect(logoutState?.active).toBe("0");
  });
});
