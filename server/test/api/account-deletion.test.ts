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

type Session = Readonly<{
  accessToken: string;
  installationId: string;
  refreshToken: string;
}>;

describePostgres("account deletion API", () => {
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
  }, 30_000);

  async function appForDatabase(): Promise<Awaited<ReturnType<typeof buildApp>>> {
    const app = await buildApp({
      config: { ...testConfig, databaseUrl: harness.runtimeDatabaseUrl },
      database: runtime,
    });
    apps.push(app);
    return app;
  }

  async function register(app: Awaited<ReturnType<typeof buildApp>>): Promise<Session> {
    const response = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      url: "/v1/installations",
    });
    expect(response.statusCode).toBe(201);
    return response.json() as Session;
  }

  async function seedDirectlyLinkedRecords(session: Session): Promise<string> {
    const [installation] = await admin<Readonly<{ user_id: string }>[]>`
      SELECT user_id::text
      FROM installations
      WHERE id = ${session.installationId}
    `;
    if (!installation) {
      throw new Error("registered installation missing");
    }

    await admin`
      INSERT INTO consent_records (id, installation_id, scope, scope_version, granted)
      VALUES (${randomUUID()}, ${session.installationId}, 'product_analytics', '1', true)
    `;
    await admin`
      INSERT INTO analytics_events (
        id,
        event_id,
        event_name,
        schema_version,
        user_id,
        installation_id,
        occurred_at,
        local_date,
        timezone_offset_minutes,
        app_version,
        version_code,
        build_channel,
        is_synthetic,
        android_api_level,
        device_class,
        locale,
        consent_scope_version,
        properties
      ) VALUES (
        ${randomUUID()},
        ${randomUUID()},
        'app_opened',
        1,
        ${installation.user_id},
        ${session.installationId},
        now(),
        current_date,
        0,
        '0.2.0-dev',
        1,
        'dev',
        false,
        35,
        'phone',
        'en-US',
        1,
        '{}'::jsonb
      )
    `;
    await admin`
      INSERT INTO daily_notification_aggregates (
        id,
        installation_id,
        local_date,
        timezone_offset_minutes,
        revision,
        rules_version,
        eligible_count,
        category_counts,
        hourly_buckets,
        game_pressure,
        observation_completeness,
        build_channel,
        is_synthetic
      ) VALUES (
        ${randomUUID()},
        ${session.installationId},
        current_date,
        0,
        1,
        1,
        0,
        '{}'::jsonb,
        '[]'::jsonb,
        0,
        100,
        'dev',
        false
      )
    `;
    await admin`
      INSERT INTO analytics_daily_installation (
        installation_id,
        local_date,
        build_channel,
        is_synthetic,
        analytics_event_count,
        notification_eligible_count,
        game_pressure_total
      ) VALUES (${session.installationId}, current_date, 'dev', false, 1, 0, 0)
    `;
    await admin`
      INSERT INTO analytics_daily_global (
        local_date,
        build_channel,
        is_synthetic,
        active_installation_count,
        analytics_event_count,
        notification_eligible_count,
        game_pressure_total
      ) VALUES (current_date, 'dev', false, 1, 1, 0, 0)
    `;
    await admin`
      INSERT INTO analytics_consent_coverage (
        observed_date,
        build_channel,
        is_synthetic,
        total_installation_count,
        product_analytics_consent_count,
        notification_aggregate_consent_count
      ) VALUES (current_date, 'dev', false, 1, 1, 0)
    `;
    await admin`
      INSERT INTO analytics_release_health (
        received_date,
        app_version,
        version_code,
        build_channel,
        is_synthetic,
        analytics_event_count,
        failure_event_count
      ) VALUES (current_date, '0.2.0-dev', 1, 'dev', false, 1, 0)
    `;
    await admin`
      INSERT INTO analytics_notification_volume (
        utc_date,
        build_channel,
        is_synthetic,
        eligible_count,
        reporting_installation_count
      ) VALUES (current_date, 'dev', false, 0, 1)
    `;
    return installation.user_id;
  }

  it("idempotently erases a caller's directly linked records and refresh credentials while retaining irreversible global rollups", async () => {
    const app = await appForDatabase();
    const target = await register(app);
    const survivor = await register(app);
    const targetUserId = await seedDirectlyLinkedRecords(target);

    const unauthenticated = await app.inject({ method: "DELETE", url: "/v1/account" });
    expect(unauthenticated.statusCode).toBe(401);
    expect(unauthenticated.json()).toEqual({ error: "unauthorized" });

    const deleted = await app.inject({
      headers: { authorization: `Bearer ${target.accessToken}` },
      method: "DELETE",
      url: "/v1/account",
    });
    expect(deleted.statusCode).toBe(204);

    const replayed = await app.inject({
      headers: { authorization: `Bearer ${target.accessToken}` },
      method: "DELETE",
      url: "/v1/account",
    });
    expect(replayed.statusCode).toBe(204);

    const refreshed = await app.inject({
      method: "POST",
      payload: { refreshToken: target.refreshToken },
      url: "/v1/sessions/refresh",
    });
    expect(refreshed.statusCode).toBe(401);
    expect(refreshed.json()).toEqual({ error: "invalid_refresh_token" });

    const [deletedCounts] = await admin<
      Readonly<{
        analytics_daily_installation: string;
        analytics_events: string;
        auth_refresh_tokens: string;
        consent_records: string;
        daily_notification_aggregates: string;
        idempotency_records: string;
        installations: string;
        users: string;
      }>[]
    >`
      SELECT
        (SELECT count(*)::text FROM users WHERE id = ${targetUserId}) AS users,
        (SELECT count(*)::text FROM installations WHERE id = ${target.installationId}) AS installations,
        (SELECT count(*)::text FROM auth_refresh_tokens WHERE installation_id = ${target.installationId}) AS auth_refresh_tokens,
        (SELECT count(*)::text FROM consent_records WHERE installation_id = ${target.installationId}) AS consent_records,
        (SELECT count(*)::text FROM analytics_events WHERE installation_id = ${target.installationId}) AS analytics_events,
        (SELECT count(*)::text FROM daily_notification_aggregates WHERE installation_id = ${target.installationId}) AS daily_notification_aggregates,
        (SELECT count(*)::text FROM idempotency_records WHERE installation_id = ${target.installationId}) AS idempotency_records,
        (SELECT count(*)::text FROM analytics_daily_installation WHERE installation_id = ${target.installationId}) AS analytics_daily_installation
    `;
    expect(deletedCounts).toEqual({
      analytics_daily_installation: "0",
      analytics_events: "0",
      auth_refresh_tokens: "0",
      consent_records: "0",
      daily_notification_aggregates: "0",
      idempotency_records: "0",
      installations: "0",
      users: "0",
    });

    const [survivorCount] = await admin<Readonly<{ count: string }>[]>`
      SELECT count(*)::text AS count
      FROM installations
      WHERE id = ${survivor.installationId}
    `;
    expect(survivorCount).toEqual({ count: "1" });

    const [globalCount] = await admin<Readonly<{ count: string }>[]>`
      SELECT (
        (SELECT count(*) FROM analytics_daily_global)
        + (SELECT count(*) FROM analytics_consent_coverage)
        + (SELECT count(*) FROM analytics_release_health)
        + (SELECT count(*) FROM analytics_notification_volume)
      )::text AS count
    `;
    expect(globalCount).toEqual({ count: "4" });
  });
});
