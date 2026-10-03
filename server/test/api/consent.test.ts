import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { ConsentRepository } from "../../src/modules/consent/repository.js";
import { ConsentInactiveError, ConsentService } from "../../src/modules/consent/service.js";
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

type ConsentUpdate = Readonly<{
  granted: boolean;
  scope: "essential_online" | "notification_aggregates" | "product_analytics";
  scopeVersion: string;
}>;

describePostgres("consent API", () => {
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

  async function register(app: Awaited<ReturnType<typeof buildApp>>) {
    const response = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    expect(response.statusCode).toBe(201);
    return response.json() as Readonly<{ accessToken: string; installationId: string }>;
  }

  async function updateConsent(
    app: Awaited<ReturnType<typeof buildApp>>,
    accessToken: string,
    update: ConsentUpdate,
    idempotencyKey = randomUUID(),
  ) {
    return app.inject({
      headers: {
        authorization: `Bearer ${accessToken}`,
        "idempotency-key": idempotencyKey,
      },
      method: "PUT",
      payload: update,
      url: "/v1/consents",
    });
  }

  it("records independent, versioned grants and withdrawals, returns only current state, and replays duplicate requests without another audit row", async () => {
    const app = await appForDatabase();
    const session = await register(app);
    const firstVersion = "2026-10-03";

    for (const update of [
      { granted: true, scope: "essential_online", scopeVersion: firstVersion },
      { granted: true, scope: "product_analytics", scopeVersion: firstVersion },
      { granted: true, scope: "notification_aggregates", scopeVersion: firstVersion },
    ] satisfies readonly ConsentUpdate[]) {
      const response = await updateConsent(app, session.accessToken, update);
      expect(response.statusCode).toBe(200);
    }

    const withdrawal = {
      granted: false,
      scope: "product_analytics",
      scopeVersion: "2026-10-04",
    } satisfies ConsentUpdate;
    const idempotencyKey = randomUUID();
    const updated = await updateConsent(app, session.accessToken, withdrawal, idempotencyKey);
    expect(updated.statusCode).toBe(200);
    expect(updated.json()).toEqual({
      consents: [
        { granted: true, scope: "essential_online", scopeVersion: firstVersion },
        { granted: true, scope: "notification_aggregates", scopeVersion: firstVersion },
        { granted: false, scope: "product_analytics", scopeVersion: "2026-10-04" },
      ],
    });

    const repeated = await updateConsent(app, session.accessToken, withdrawal, idempotencyKey);
    expect(repeated.statusCode).toBe(200);
    expect(repeated.json()).toEqual(updated.json());

    const conflictingReplay = await updateConsent(
      app,
      session.accessToken,
      { ...withdrawal, granted: true },
      idempotencyKey,
    );
    expect(conflictingReplay.statusCode).toBe(409);

    const history = await admin<
      Readonly<{ granted: boolean; scope: string; scope_version: string }>[]
    >`
      SELECT granted, scope::text, scope_version
      FROM consent_records
      WHERE installation_id = ${session.installationId}
      ORDER BY scope::text, scope_version
    `;
    expect(history).toEqual([
      { granted: true, scope: "essential_online", scope_version: firstVersion },
      { granted: true, scope: "notification_aggregates", scope_version: firstVersion },
      { granted: true, scope: "product_analytics", scope_version: firstVersion },
      { granted: false, scope: "product_analytics", scope_version: "2026-10-04" },
    ]);
  });

  it("requires an authenticated installation and makes only an active matching consent version available to later analytics ingestion", async () => {
    const app = await appForDatabase();
    const installationA = await register(app);
    const installationB = await register(app);
    const scopeVersion = "2026-10-03";

    const unauthenticated = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "PUT",
      payload: { granted: true, scope: "product_analytics", scopeVersion },
      url: "/v1/consents",
    });
    expect(unauthenticated.statusCode).toBe(401);

    const malformedTarget = await app.inject({
      headers: {
        authorization: `Bearer ${installationA.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "PUT",
      payload: {
        granted: true,
        installationId: installationB.installationId,
        scope: "product_analytics",
        scopeVersion,
      },
      url: "/v1/consents",
    });
    expect(malformedTarget.statusCode).toBe(400);

    const granted = await updateConsent(app, installationA.accessToken, {
      granted: true,
      scope: "product_analytics",
      scopeVersion,
    });
    expect(granted.statusCode).toBe(200);

    const service = new ConsentService(new ConsentRepository(runtime));
    const claimsA = verifyAccessToken({
      audience: testConfig.tokenAudience,
      secret: testConfig.accessTokenSecret,
      token: installationA.accessToken,
    });
    const claimsB = verifyAccessToken({
      audience: testConfig.tokenAudience,
      secret: testConfig.accessTokenSecret,
      token: installationB.accessToken,
    });

    await expect(
      service.requireActiveConsent({
        ...claimsA,
        scope: "product_analytics",
        scopeVersion,
      }),
    ).resolves.toBeUndefined();
    await expect(
      service.requireActiveConsent({
        ...claimsA,
        scope: "product_analytics",
        scopeVersion: "2026-10-04",
      }),
    ).rejects.toBeInstanceOf(ConsentInactiveError);
    await expect(
      service.requireActiveConsent({
        ...claimsB,
        scope: "product_analytics",
        scopeVersion,
      }),
    ).rejects.toBeInstanceOf(ConsentInactiveError);

    const [otherInstallationHistory] = await admin<Readonly<{ count: string }>[]>`
      SELECT count(*)::text AS count
      FROM consent_records
      WHERE installation_id = ${installationB.installationId}
    `;
    expect(otherInstallationHistory).toEqual({ count: "0" });
  });
});
