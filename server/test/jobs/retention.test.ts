import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { runAnalyticsDaily } from "../../src/jobs/analytics-daily.js";
import { runRetention } from "../../src/jobs/retention.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";

const postgresConfig = loadPostgresTestConfig();
const describePostgres = postgresConfig ? describe : describe.skip;

const ids = {
  currentInstallation: "00000000-0000-4000-8000-000000000701",
  currentUser: "00000000-0000-4000-8000-000000000700",
  expiredInstallation: "00000000-0000-4000-8000-000000000601",
  expiredUser: "00000000-0000-4000-8000-000000000600",
} as const;

describePostgres("retention job", () => {
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
      VALUES
        (${ids.expiredUser}, 'dev', false),
        (${ids.currentUser}, 'dev', false)
    `;
    await admin`
      INSERT INTO installations (id, user_id, app_version, build_channel, is_synthetic)
      VALUES
        (${ids.expiredInstallation}, ${ids.expiredUser}, '0.2.0-dev', 'dev', false),
        (${ids.currentInstallation}, ${ids.currentUser}, '0.2.0-dev', 'dev', false)
    `;

    for (const suffix of ["11", "12"] as const) {
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
        ) VALUES (
          ${`00000000-0000-4000-8000-0000000006${suffix}`},
          ${randomUUID()},
          'app_opened',
          1,
          ${ids.expiredUser},
          ${ids.expiredInstallation},
          now() - interval '25 months',
          now() - interval '25 months',
          (current_date - interval '25 months')::date,
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
    }
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
      ) VALUES (
        ${randomUUID()},
        ${randomUUID()},
        'app_opened',
        1,
        ${ids.currentUser},
        ${ids.currentInstallation},
        now(),
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
      ) VALUES
        (
          ${randomUUID()},
          ${ids.expiredInstallation},
          (current_date - interval '25 months')::date,
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
        ),
        (
          ${randomUUID()},
          ${ids.currentInstallation},
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
      ) VALUES (
        ${ids.expiredInstallation},
        (current_date - interval '25 months')::date,
        'dev',
        false,
        2,
        0,
        0
      )
    `;
    await admin`
      INSERT INTO auth_refresh_tokens (
        id,
        installation_id,
        family_id,
        token_hash,
        issued_at,
        expires_at
      ) VALUES (
        ${randomUUID()},
        ${ids.expiredInstallation},
        ${randomUUID()},
        '0000000000000000000000000000000000000000000000000000000000000000',
        now() - interval '2 days',
        now() - interval '1 day'
      )
    `;
    await admin`
      INSERT INTO idempotency_records (
        id,
        installation_id,
        operation,
        idempotency_key,
        request_hash,
        created_at,
        expires_at
      ) VALUES (
        ${randomUUID()},
        ${ids.expiredInstallation},
        'expired_operation',
        ${randomUUID()},
        '1111111111111111111111111111111111111111111111111111111111111111',
        now() - interval '2 days',
        now() - interval '1 day'
      )
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
      ) VALUES ((current_date - interval '25 months')::date, 'dev', false, 1, 2, 0, 0)
    `;
    await admin`
      INSERT INTO analytics_consent_coverage (
        observed_date,
        build_channel,
        is_synthetic,
        total_installation_count,
        product_analytics_consent_count,
        notification_aggregate_consent_count
      ) VALUES ((current_date - interval '25 months')::date, 'dev', false, 1, 0, 0)
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
      ) VALUES ((current_date - interval '25 months')::date, '0.2.0-dev', 1, 'dev', false, 2, 0)
    `;
    await admin`
      INSERT INTO analytics_notification_volume (
        utc_date,
        build_channel,
        is_synthetic,
        eligible_count,
        reporting_installation_count
      ) VALUES ((current_date - interval '25 months')::date, 'dev', false, 0, 1)
    `;
  }, 30_000);

  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await admin?.end({ timeout: 5 });
    await harness?.close();
  }, 30_000);

  it("deletes only expired direct records in bounded resumable batches while preserving global rollups", async () => {
    const [expiredCandidates] = await admin<Readonly<{ aggregates: string; events: string }>[]>`
      SELECT
        (SELECT count(*)::text FROM analytics_events WHERE received_at < now() - interval '24 months') AS events,
        (SELECT count(*)::text FROM daily_notification_aggregates WHERE local_date < (current_date - interval '24 months')::date) AS aggregates
    `;
    expect(expiredCandidates).toEqual({ aggregates: "1", events: "2" });

    const first = await runRetention(runtime, { batchSize: 2, workerId: "retention-test-worker" });
    expect(first).toMatchObject({ attemptCount: 1, failureCode: null, status: "succeeded" });
    expect(first.checkpoint).toContain("analytics_events:2");

    const [afterFirst] = await admin<
      Readonly<{
        aggregates: string;
        events: string;
        idempotency: string;
        marts: string;
        tokens: string;
      }>[]
    >`
      SELECT
        (SELECT count(*)::text FROM analytics_events WHERE installation_id = ${ids.expiredInstallation}) AS events,
        (SELECT count(*)::text FROM daily_notification_aggregates WHERE installation_id = ${ids.expiredInstallation}) AS aggregates,
        (SELECT count(*)::text FROM analytics_daily_installation WHERE installation_id = ${ids.expiredInstallation}) AS marts,
        (SELECT count(*)::text FROM auth_refresh_tokens WHERE installation_id = ${ids.expiredInstallation}) AS tokens,
        (SELECT count(*)::text FROM idempotency_records WHERE installation_id = ${ids.expiredInstallation}) AS idempotency
    `;
    expect(afterFirst).toEqual({
      aggregates: "1",
      events: "0",
      idempotency: "1",
      marts: "1",
      tokens: "1",
    });

    await runRetention(runtime, { batchSize: 2, workerId: "retention-test-worker" });
    await runRetention(runtime, { batchSize: 2, workerId: "retention-test-worker" });
    const settled = await runRetention(runtime, {
      batchSize: 2,
      workerId: "retention-test-worker",
    });
    expect(settled).toMatchObject({ attemptCount: 1, failureCode: null, status: "succeeded" });
    expect(settled.checkpoint).toContain("analytics_events:0");

    const [expiredCounts] = await admin<
      Readonly<{
        aggregates: string;
        events: string;
        idempotency: string;
        marts: string;
        tokens: string;
      }>[]
    >`
      SELECT
        (SELECT count(*)::text FROM analytics_events WHERE installation_id = ${ids.expiredInstallation}) AS events,
        (SELECT count(*)::text FROM daily_notification_aggregates WHERE installation_id = ${ids.expiredInstallation}) AS aggregates,
        (SELECT count(*)::text FROM analytics_daily_installation WHERE installation_id = ${ids.expiredInstallation}) AS marts,
        (SELECT count(*)::text FROM auth_refresh_tokens WHERE installation_id = ${ids.expiredInstallation}) AS tokens,
        (SELECT count(*)::text FROM idempotency_records WHERE installation_id = ${ids.expiredInstallation}) AS idempotency
    `;
    expect(expiredCounts).toEqual({
      aggregates: "0",
      events: "0",
      idempotency: "0",
      marts: "0",
      tokens: "0",
    });

    const [currentCounts] = await admin<Readonly<{ aggregates: string; events: string }>[]>`
      SELECT
        (SELECT count(*)::text FROM analytics_events WHERE installation_id = ${ids.currentInstallation}) AS events,
        (SELECT count(*)::text FROM daily_notification_aggregates WHERE installation_id = ${ids.currentInstallation}) AS aggregates
    `;
    expect(currentCounts).toEqual({ aggregates: "1", events: "1" });

    const rollup = await runAnalyticsDaily(runtime, { workerId: "analytics-retention-test" });
    expect(rollup.status).toBe("succeeded");

    const [globalRollups] = await admin<Readonly<{ count: string }>[]>`
      SELECT (
        (SELECT count(*) FROM analytics_daily_global WHERE local_date < current_date - interval '24 months')
        + (SELECT count(*) FROM analytics_consent_coverage WHERE observed_date < current_date - interval '24 months')
        + (SELECT count(*) FROM analytics_release_health WHERE received_date < current_date - interval '24 months')
        + (SELECT count(*) FROM analytics_notification_volume WHERE utc_date < current_date - interval '24 months')
      )::text AS count
    `;
    expect(globalRollups).toEqual({ count: "4" });

    const [runs] = await admin<Readonly<{ count: string; running: string }>[]>`
      SELECT
        count(*)::text AS count,
        count(*) FILTER (WHERE status = 'running'::job_run_status)::text AS running
      FROM job_runs
      WHERE job_name = 'retention'
    `;
    expect(runs).toEqual({ count: "4", running: "0" });
  });
});
