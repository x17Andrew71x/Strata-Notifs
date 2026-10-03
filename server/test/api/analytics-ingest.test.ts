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

type AnalyticsEvent = Readonly<{
  android_api_level: number;
  app_version: string;
  build_channel: "dev" | "prod";
  consent_scope_version: number;
  device_class: "foldable" | "phone" | "tablet";
  event_id: string;
  event_name: string;
  installation_id: string;
  locale: string;
  local_date: string;
  occurred_at: string;
  properties: Readonly<Record<string, unknown>>;
  schema_version: number;
  session_id: string;
  timezone_offset_minutes: number;
  version_code: number;
}>;

type AnalyticsResult = Readonly<{
  eventId: string;
  status: "accepted" | "duplicate" | "rejected";
}>;

describePostgres("analytics ingestion API", () => {
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

  async function grantAnalyticsConsent(
    app: Awaited<ReturnType<typeof buildApp>>,
    accessToken: string,
    scopeVersion = "1",
  ) {
    const response = await app.inject({
      headers: {
        authorization: `Bearer ${accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "PUT",
      payload: { granted: true, scope: "product_analytics", scopeVersion },
      url: "/v1/consents",
    });
    expect(response.statusCode).toBe(200);
  }

  function eventFor(
    installationId: string,
    overrides: Partial<AnalyticsEvent> = {},
  ): AnalyticsEvent {
    const occurredAt = overrides.occurred_at ?? new Date().toISOString();
    return {
      android_api_level: 35,
      app_version: "0.2.0-dev",
      build_channel: "dev",
      consent_scope_version: 1,
      device_class: "phone",
      event_id: randomUUID(),
      event_name: "app_opened",
      installation_id: installationId,
      locale: "en-US",
      local_date: occurredAt.slice(0, 10),
      occurred_at: occurredAt,
      properties: { launch_type: "cold" },
      schema_version: 1,
      session_id: randomUUID(),
      timezone_offset_minutes: 0,
      version_code: 2000,
      ...overrides,
    };
  }

  async function ingest(
    app: Awaited<ReturnType<typeof buildApp>>,
    accessToken: string,
    events: readonly unknown[],
  ) {
    return app.inject({
      headers: { authorization: `Bearer ${accessToken}` },
      method: "POST",
      payload: { events },
      url: "/v1/analytics/events:batch",
    });
  }

  it("accepts a bounded consented batch, replays exact event identities, and atomically accepts mixed duplicate/new retries", async () => {
    const app = await appForDatabase();
    const session = await register(app);
    await grantAnalyticsConsent(app, session.accessToken);
    const first = eventFor(session.installationId);

    const accepted = await ingest(app, session.accessToken, [first]);
    expect(accepted.statusCode).toBe(202);
    expect(accepted.json()).toEqual({
      results: [{ eventId: first.event_id, status: "accepted" } satisfies AnalyticsResult],
    });

    const duplicate = await ingest(app, session.accessToken, [first]);
    expect(duplicate.statusCode).toBe(202);
    expect(duplicate.json()).toEqual({
      results: [{ eventId: first.event_id, status: "duplicate" } satisfies AnalyticsResult],
    });

    const collision = await ingest(app, session.accessToken, [
      { ...first, properties: { launch_type: "warm" } },
    ]);
    expect(collision.statusCode).toBe(202);
    expect(collision.json()).toEqual({
      results: [{ eventId: first.event_id, status: "rejected" } satisfies AnalyticsResult],
    });

    const second = eventFor(session.installationId, {
      event_name: "onboarding_started",
      properties: { entry_point: "first_run" },
    });
    const mixed = await ingest(app, session.accessToken, [first, second]);
    expect(mixed.statusCode).toBe(202);
    expect(mixed.json()).toEqual({
      results: [
        { eventId: first.event_id, status: "duplicate" } satisfies AnalyticsResult,
        { eventId: second.event_id, status: "accepted" } satisfies AnalyticsResult,
      ],
    });

    const claims = verifyAccessToken({
      audience: testConfig.tokenAudience,
      secret: testConfig.accessTokenSecret,
      token: session.accessToken,
    });
    const stored = await admin<
      Readonly<{
        build_channel: string;
        event_id: string;
        is_synthetic: boolean;
        properties: Readonly<Record<string, unknown>>;
        user_id: string | null;
      }>[]
    >`
      SELECT event_id::text, user_id::text, build_channel::text, is_synthetic, properties
      FROM analytics_events
      WHERE installation_id = ${session.installationId}
      ORDER BY event_id
    `;
    expect(stored).toEqual(
      expect.arrayContaining([
        {
          build_channel: "dev",
          event_id: first.event_id,
          is_synthetic: false,
          properties: { launch_type: "cold" },
          user_id: claims.userId,
        },
        {
          build_channel: "dev",
          event_id: second.event_id,
          is_synthetic: false,
          properties: { entry_point: "first_run" },
          user_id: claims.userId,
        },
      ]),
    );
    expect(stored).toHaveLength(2);
  });

  it("rejects unknown contracts, free-form properties, future clocks, and overlarge batches without retaining any event", async () => {
    const app = await appForDatabase();
    const session = await register(app);
    await grantAnalyticsConsent(app, session.accessToken);

    const rejectedBatches: readonly (readonly unknown[])[] = [
      [eventFor(session.installationId, { event_name: "unsupported_event", properties: {} })],
      [eventFor(session.installationId, { schema_version: 2 })],
      [
        eventFor(session.installationId, {
          properties: { launch_type: "cold", unapproved_field: "free-form" },
        }),
      ],
      [
        eventFor(session.installationId, {
          occurred_at: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
        }),
      ],
      Array.from({ length: 51 }, () => eventFor(session.installationId)),
    ];

    for (const events of rejectedBatches) {
      const response = await ingest(app, session.accessToken, events);
      expect(response.statusCode).toBe(400);
      expect(response.json()).toEqual({ error: "request_invalid" });
    }

    const [stored] = await admin<Readonly<{ count: string }>[]>`
      SELECT count(*)::text AS count
      FROM analytics_events
      WHERE installation_id = ${session.installationId}
    `;
    expect(stored).toEqual({ count: "0" });
  });

  it("requires matching authenticated ownership and active consent, and never accepts client-selected synthetic or build markers", async () => {
    const app = await appForDatabase();
    const authorized = await register(app);
    const unconsented = await register(app);
    await grantAnalyticsConsent(app, authorized.accessToken);

    const wrongConsent = await ingest(app, unconsented.accessToken, [
      eventFor(unconsented.installationId),
    ]);
    expect(wrongConsent.statusCode).toBe(403);
    expect(wrongConsent.json()).toEqual({ error: "consent_inactive" });

    const wrongInstallation = await ingest(app, authorized.accessToken, [
      eventFor(unconsented.installationId),
    ]);
    expect(wrongInstallation.statusCode).toBe(400);
    expect(wrongInstallation.json()).toEqual({ error: "request_invalid" });

    for (const event of [
      { ...eventFor(authorized.installationId), is_synthetic: true },
      { ...eventFor(authorized.installationId), build_channel: "prod" },
    ]) {
      const response = await ingest(app, authorized.accessToken, [event]);
      expect(response.statusCode).toBe(400);
      expect(response.json()).toEqual({ error: "request_invalid" });
    }

    const [stored] = await admin<Readonly<{ count: string }>[]>`
      SELECT count(*)::text AS count
      FROM analytics_events
      WHERE installation_id IN (${authorized.installationId}, ${unconsented.installationId})
    `;
    expect(stored).toEqual({ count: "0" });
  });
});
