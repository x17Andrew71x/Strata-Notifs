import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { runAnalyticsDaily } from "../../src/jobs/analytics-daily.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";

const postgresConfig = loadPostgresTestConfig();
const describePostgres = postgresConfig ? describe : describe.skip;

const ids = {
  installation: "00000000-0000-4000-8000-000000000101",
  user: "00000000-0000-4000-8000-000000000100",
} as const;

describePostgres("daily analytics rollup job", () => {
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

    await admin`
      INSERT INTO users (id, build_channel, is_synthetic)
      VALUES (${ids.user}, 'dev', false)
    `;
    await admin`
      INSERT INTO installations (id, user_id, app_version, build_channel, is_synthetic)
      VALUES (${ids.installation}, ${ids.user}, '0.2.0-dev', 'dev', false)
    `;
    await admin`
      INSERT INTO consent_records (id, installation_id, scope, scope_version, granted)
      VALUES
        ('00000000-0000-4000-8000-000000000201', ${ids.installation}, 'product_analytics', '1', true),
        ('00000000-0000-4000-8000-000000000202', ${ids.installation}, 'notification_aggregates', '1', true)
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
        received_at,
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
      ) VALUES
        (
          '00000000-0000-4000-8000-000000000301',
          '00000000-0000-4000-8000-000000000311',
          'app_opened',
          1,
          ${ids.user},
          ${ids.installation},
          '2026-10-02T09:00:00Z',
          '2026-10-02T09:01:00Z',
          '2026-10-02',
          0,
          '0.2.0-dev',
          2,
          'dev',
          false,
          35,
          'phone',
          'en-US',
          1,
          '{"launch_type":"cold"}'::jsonb
        ),
        (
          '00000000-0000-4000-8000-000000000302',
          '00000000-0000-4000-8000-000000000312',
          'sync_failed',
          1,
          ${ids.user},
          ${ids.installation},
          '2026-10-02T10:00:00Z',
          '2026-10-02T10:01:00Z',
          '2026-10-02',
          0,
          '0.2.0-dev',
          2,
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
      ) VALUES
        (
          '00000000-0000-4000-8000-000000000401',
          ${ids.installation},
          '2026-10-02',
          0,
          1,
          1,
          3,
          '{"message":3}'::jsonb,
          '[{"hour":23,"count":3}]'::jsonb,
          2,
          100,
          'dev',
          false
        ),
        (
          '00000000-0000-4000-8000-000000000402',
          ${ids.installation},
          '2026-10-03',
          120,
          1,
          1,
          2,
          '{"email":2}'::jsonb,
          '[{"hour":0,"count":2}]'::jsonb,
          1,
          100,
          'dev',
          false
        )
    `;
  }, 30_000);

  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await admin?.end({ timeout: 5 });
    await harness?.close();
  }, 30_000);

  it("builds idempotent, privacy-safe daily marts, persists a checkpointed run, and fails visibly on stable-source drift", async () => {
    const first = await runAnalyticsDaily(runtime, { workerId: "analytics-test-worker" });
    expect(first).toEqual({
      attemptCount: 1,
      checkpoint: expect.any(String),
      failureCode: null,
      status: "succeeded",
    });

    const dailyGlobal = await admin<
      Readonly<{
        active_installation_count: number;
        analytics_event_count: number;
        game_pressure_total: number;
        local_date: string;
        notification_eligible_count: number;
      }>[]
    >`
      SELECT
        local_date::text,
        active_installation_count,
        analytics_event_count,
        notification_eligible_count,
        game_pressure_total
      FROM analytics_daily_global
      ORDER BY local_date
    `;
    expect(dailyGlobal).toEqual([
      {
        active_installation_count: 1,
        analytics_event_count: 2,
        game_pressure_total: 2,
        local_date: "2026-10-02",
        notification_eligible_count: 3,
      },
      {
        active_installation_count: 1,
        analytics_event_count: 0,
        game_pressure_total: 1,
        local_date: "2026-10-03",
        notification_eligible_count: 2,
      },
    ]);

    const [dailyInstallation] = await admin<
      Readonly<{
        analytics_event_count: number;
        game_pressure_total: number;
        notification_eligible_count: number;
      }>[]
    >`
      SELECT analytics_event_count, notification_eligible_count, game_pressure_total
      FROM analytics_daily_installation
      WHERE installation_id = ${ids.installation}
        AND local_date = '2026-10-02'::date
    `;
    expect(dailyInstallation).toEqual({
      analytics_event_count: 2,
      game_pressure_total: 2,
      notification_eligible_count: 3,
    });

    const [consentCoverage] = await admin<
      Readonly<{
        notification_aggregate_consent_count: number;
        product_analytics_consent_count: number;
        total_installation_count: number;
      }>[]
    >`
      SELECT
        total_installation_count,
        product_analytics_consent_count,
        notification_aggregate_consent_count
      FROM analytics_consent_coverage
      WHERE build_channel = 'dev'::build_channel
        AND is_synthetic = false
      ORDER BY observed_date DESC
      LIMIT 1
    `;
    expect(consentCoverage).toEqual({
      notification_aggregate_consent_count: 1,
      product_analytics_consent_count: 1,
      total_installation_count: 1,
    });

    const [releaseHealth] = await admin<
      Readonly<{ analytics_event_count: number; failure_event_count: number }>[]
    >`
      SELECT analytics_event_count, failure_event_count
      FROM analytics_release_health
      WHERE received_date = '2026-10-02'::date
        AND app_version = '0.2.0-dev'
        AND version_code = 2
    `;
    expect(releaseHealth).toEqual({ analytics_event_count: 2, failure_event_count: 1 });

    const [notificationVolume] = await admin<
      Readonly<{ eligible_count: number; reporting_installation_count: number }>[]
    >`
      SELECT eligible_count, reporting_installation_count
      FROM analytics_notification_volume
      WHERE utc_date = '2026-10-02'::date
        AND build_channel = 'dev'::build_channel
        AND is_synthetic = false
    `;
    expect(notificationVolume).toEqual({ eligible_count: 5, reporting_installation_count: 1 });

    const directRuntimeRead = await runtime<Readonly<{ installation_id: string }>[]>`
      SELECT installation_id::text FROM analytics_daily_installation
    `;
    expect(directRuntimeRead).toEqual([]);

    const repeated = await runAnalyticsDaily(runtime, { workerId: "analytics-test-worker" });
    expect(repeated).toEqual({
      attemptCount: 1,
      checkpoint: first.checkpoint,
      failureCode: null,
      status: "succeeded",
    });

    await admin`
      UPDATE analytics_daily_global
      SET notification_eligible_count = 999
      WHERE local_date = '2026-10-02'::date
        AND build_channel = 'dev'::build_channel
        AND is_synthetic = false
    `;
    const drifted = await runAnalyticsDaily(runtime, { workerId: "analytics-test-worker" });
    expect(drifted).toEqual({
      attemptCount: 1,
      checkpoint: first.checkpoint,
      failureCode: "rollup_drift",
      status: "failed",
    });

    const [failedRun] = await admin<Readonly<{ failure_code: string; status: string }>[]>`
      SELECT status::text, failure_code
      FROM job_runs
      WHERE job_name = 'analytics_daily'
      ORDER BY started_at DESC
      LIMIT 1
    `;
    expect(failedRun).toEqual({ failure_code: "rollup_drift", status: "failed" });
  });
});
