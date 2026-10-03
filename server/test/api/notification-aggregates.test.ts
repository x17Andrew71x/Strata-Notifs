import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
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

type Category =
  | "alarm"
  | "call"
  | "email"
  | "event"
  | "message"
  | "navigation"
  | "other"
  | "progress"
  | "reminder"
  | "social"
  | "transport"
  | "workout";

type NotificationAggregate = Readonly<{
  category_counts: Readonly<Partial<Record<Category, number>>>;
  consent_scope_version: string;
  eligible_count: number;
  game_pressure: number;
  hourly_buckets: readonly Readonly<{ count: number; hour: number }>[];
  local_date: string;
  observation_completeness: number;
  revision: number;
  rules_version: number;
  timezone_offset_minutes: number;
}>;

type AggregateResult = Readonly<{
  localDate: string;
  revision: number;
  status: "accepted" | "duplicate";
}>;

describePostgres("notification aggregate API", () => {
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

  async function grantNotificationAggregateConsent(
    app: Awaited<ReturnType<typeof buildApp>>,
    accessToken: string,
    scopeVersion = "2026-10-03",
  ) {
    const response = await app.inject({
      headers: {
        authorization: `Bearer ${accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "PUT",
      payload: { granted: true, scope: "notification_aggregates", scopeVersion },
      url: "/v1/consents",
    });
    expect(response.statusCode).toBe(200);
  }

  function aggregateFor(overrides: Partial<NotificationAggregate> = {}): NotificationAggregate {
    return {
      category_counts: { message: 3 },
      consent_scope_version: "2026-10-03",
      eligible_count: 3,
      game_pressure: 2,
      hourly_buckets: [{ count: 3, hour: 9 }],
      local_date: "2026-10-02",
      observation_completeness: 100,
      revision: 1,
      rules_version: 1,
      timezone_offset_minutes: 0,
      ...overrides,
    };
  }

  async function upsert(
    app: Awaited<ReturnType<typeof buildApp>>,
    accessToken: string,
    aggregate: unknown,
  ) {
    return app.inject({
      headers: { authorization: `Bearer ${accessToken}` },
      method: "PUT",
      payload: aggregate as object,
      url: "/v1/notification-aggregates/daily",
    });
  }

  it("persists one server-owned daily aggregate, replays an exact revision, and replaces it only with a newer revision", async () => {
    const app = await appForDatabase();
    const session = await register(app);
    await grantNotificationAggregateConsent(app, session.accessToken);
    const first = aggregateFor();

    const accepted = await upsert(app, session.accessToken, first);
    expect(accepted.statusCode).toBe(202);
    expect(accepted.json()).toEqual({
      result: {
        localDate: first.local_date,
        revision: first.revision,
        status: "accepted",
      } satisfies AggregateResult,
    });

    const repeated = await upsert(app, session.accessToken, first);
    expect(repeated.statusCode).toBe(202);
    expect(repeated.json()).toEqual({
      result: {
        localDate: first.local_date,
        revision: first.revision,
        status: "duplicate",
      } satisfies AggregateResult,
    });

    const corrected = aggregateFor({
      category_counts: { call: 1, message: 3 },
      eligible_count: 4,
      game_pressure: 3,
      hourly_buckets: [
        { count: 1, hour: 8 },
        { count: 3, hour: 9 },
      ],
      revision: 2,
    });
    const replaced = await upsert(app, session.accessToken, corrected);
    expect(replaced.statusCode).toBe(202);
    expect(replaced.json()).toEqual({
      result: {
        localDate: corrected.local_date,
        revision: corrected.revision,
        status: "accepted",
      } satisfies AggregateResult,
    });

    const stored = await admin<
      Readonly<{
        build_channel: string;
        category_counts: Readonly<Record<string, number>>;
        eligible_count: number;
        game_pressure: number;
        hourly_buckets: readonly Readonly<{ count: number; hour: number }>[];
        is_synthetic: boolean;
        observation_completeness: number;
        revision: number;
        rules_version: number;
      }>[]
    >`
      SELECT
        revision,
        rules_version,
        eligible_count,
        category_counts,
        hourly_buckets,
        game_pressure,
        observation_completeness,
        build_channel::text,
        is_synthetic
      FROM daily_notification_aggregates
      WHERE installation_id = ${session.installationId}
        AND local_date = ${first.local_date}::date
    `;
    expect(stored).toEqual([
      {
        build_channel: "dev",
        category_counts: corrected.category_counts,
        eligible_count: corrected.eligible_count,
        game_pressure: corrected.game_pressure,
        hourly_buckets: corrected.hourly_buckets,
        is_synthetic: false,
        observation_completeness: corrected.observation_completeness,
        revision: corrected.revision,
        rules_version: corrected.rules_version,
      },
    ]);
  });

  it("rejects unconsented, malformed, inconsistent, and source-derived aggregate payloads without storing a row", async () => {
    const app = await appForDatabase();
    const session = await register(app);
    const aggregate = aggregateFor();

    const unauthenticated = await app.inject({
      method: "PUT",
      payload: aggregate,
      url: "/v1/notification-aggregates/daily",
    });
    expect(unauthenticated.statusCode).toBe(401);
    expect(unauthenticated.json()).toEqual({ error: "unauthorized" });

    const unconsented = await upsert(app, session.accessToken, aggregate);
    expect(unconsented.statusCode).toBe(403);
    expect(unconsented.json()).toEqual({ error: "consent_inactive" });

    await grantNotificationAggregateConsent(app, session.accessToken);
    const rejectedPayloads: readonly unknown[] = [
      aggregateFor({ category_counts: { message: 2 } }),
      aggregateFor({ hourly_buckets: [{ count: 2, hour: 9 }] }),
      aggregateFor({
        hourly_buckets: [
          { count: 1, hour: 9 },
          { count: 2, hour: 9 },
        ],
      }),
      { ...aggregate, source_tokens: ["opaque"] },
      { ...aggregate, source_colours: ["#000000"] },
      { ...aggregate, package_names: ["not-retained"] },
      { ...aggregate, content: "not-retained" },
      { ...aggregate, build_channel: "prod" },
      { ...aggregate, is_synthetic: true },
    ];

    for (const payload of rejectedPayloads) {
      const response = await upsert(app, session.accessToken, payload);
      expect(response.statusCode).toBe(400);
      expect(response.json()).toEqual({ error: "request_invalid" });
    }

    const [stored] = await admin<Readonly<{ count: string }>[]>`
      SELECT count(*)::text AS count
      FROM daily_notification_aggregates
      WHERE installation_id = ${session.installationId}
    `;
    expect(stored).toEqual({ count: "0" });
  });

  it("does not permit same-revision mutation, stale replacement, or a client-selected installation target", async () => {
    const app = await appForDatabase();
    const authorized = await register(app);
    const other = await register(app);
    await grantNotificationAggregateConsent(app, authorized.accessToken);
    await grantNotificationAggregateConsent(app, other.accessToken);
    const first = aggregateFor();

    expect((await upsert(app, authorized.accessToken, first)).statusCode).toBe(202);

    const sameRevisionMutation = await upsert(
      app,
      authorized.accessToken,
      aggregateFor({ category_counts: { call: 1, message: 2 } }),
    );
    expect(sameRevisionMutation.statusCode).toBe(409);
    expect(sameRevisionMutation.json()).toEqual({ error: "aggregate_revision_conflict" });

    const revised = aggregateFor({
      category_counts: { call: 1, message: 3 },
      eligible_count: 4,
      game_pressure: 3,
      hourly_buckets: [
        { count: 1, hour: 8 },
        { count: 3, hour: 9 },
      ],
      revision: 2,
    });
    expect((await upsert(app, authorized.accessToken, revised)).statusCode).toBe(202);

    const stale = await upsert(app, authorized.accessToken, first);
    expect(stale.statusCode).toBe(409);
    expect(stale.json()).toEqual({ error: "aggregate_revision_conflict" });

    const clientSelectedTarget = await upsert(app, other.accessToken, {
      ...aggregateFor(),
      installation_id: authorized.installationId,
    });
    expect(clientSelectedTarget.statusCode).toBe(400);
    expect(clientSelectedTarget.json()).toEqual({ error: "request_invalid" });

    const counts = await admin<Readonly<{ count: string; installation_id: string }>[]>`
      SELECT installation_id::text, count(*)::text AS count
      FROM daily_notification_aggregates
      WHERE installation_id IN (${authorized.installationId}, ${other.installationId})
      GROUP BY installation_id
      ORDER BY installation_id
    `;
    expect(counts).toEqual([{ count: "1", installation_id: authorized.installationId }]);
  });
});
