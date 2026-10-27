import { randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";
import { testConfig } from "../test-config.js";

const postgresConfig = loadPostgresTestConfig();
const describePostgres = postgresConfig ? describe : describe.skip;

describePostgres("Exhibits API", () => {
  const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
  let admin: DatabaseClient;
  let harness: PostgresTestHarness;
  let runtime: DatabaseClient;

  beforeAll(async () => {
    if (!postgresConfig) throw new Error("PostgreSQL test configuration is required");
    harness = await PostgresTestHarness.create(postgresConfig);
    await migrateDatabase(harness.migrationDatabaseUrl);
    const adminUrl = new URL(harness.adminDatabaseUrl);
    adminUrl.pathname = "/afterchime_test";
    admin = createSqlClient(adminUrl.toString());
    runtime = createSqlClient(harness.runtimeDatabaseUrl);
    await admin`
      UPDATE exhibits
      SET
        starts_at = now() - interval '1 day',
        ends_at = CASE WHEN kind = 'featured'::exhibit_kind THEN now() + interval '47 days' ELSE NULL END,
        status = CASE
          WHEN slug IN ('returning-light-2027', 'long-night-2026') THEN 'open'::exhibit_status
          ELSE 'closed'::exhibit_status
        END
    `;
  }, 30_000);

  afterEach(async () => {
    await Promise.all(apps.splice(0).map((app) => app.close()));
    await admin`DELETE FROM users`;
    await admin`DELETE FROM exhibit_cohort_snapshots`;
    await admin`
      UPDATE exhibits
      SET
        total_units = CASE slug
          WHEN 'returning-light-2027' THEN 282300000000
          WHEN 'long-night-2026' THEN 364800000000
          WHEN 'monsoon-choir-2026' THEN 500000000000
          WHEN 'ember-flight-2026' THEN 500000000000
          WHEN 'tideglass-2026' THEN 430000000000
          ELSE total_units
        END,
        version = 1
    `;
  });

  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await admin?.end({ timeout: 5 });
    await harness?.close();
  });

  async function appForDatabase() {
    const app = await buildApp({
      config: { ...testConfig, databaseUrl: harness.runtimeDatabaseUrl },
      database: runtime,
    });
    apps.push(app);
    return app;
  }

  async function registered(app: Awaited<ReturnType<typeof buildApp>>) {
    const registration = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: {
        appVersion: "0.2.0-dev",
        device: { androidApiLevel: 35, deviceClass: "phone" },
      },
      url: "/v1/installations",
    });
    expect(registration.statusCode).toBe(201);
    const session = registration.json() as Readonly<{
      accessToken: string;
      installationId: string;
    }>;
    const consent = await app.inject({
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "PUT",
      payload: { granted: true, scope: "essential_online", scopeVersion: "1" },
      url: "/v1/consents",
    });
    expect(consent.statusCode).toBe(200);
    return session;
  }

  it("returns private eligibility with percentage-only shared progress and accepts an idempotent contribution", async () => {
    const app = await appForDatabase();
    const session = await registered(app);
    const localDate = new Date().toISOString().slice(0, 10);

    const initial = await app.inject({
      headers: { authorization: `Bearer ${session.accessToken}` },
      method: "GET",
      url: `/v1/exhibits/current?local_date=${localDate}`,
    });
    expect(initial.statusCode).toBe(200);
    const initialState = initial.json();
    expect(initialState).toMatchObject({
      status: "ready",
      featured: {
        id: "returning-light-2027",
        progressBasisPoints: 5_646,
        personalContribution: 0,
        personalMinimum: 900,
      },
      archive: { id: "long-night-2026", progressBasisPoints: 1_824 },
      todayContributedUnits: 0,
      dailyContributionCap: 30,
    });
    expect(JSON.stringify(initialState)).not.toMatch(/targetUnits|totalUnits|participant/i);

    const idempotencyKey = randomUUID();
    const accepted = await app.inject({
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        "idempotency-key": idempotencyKey,
      },
      method: "POST",
      payload: { local_date: localDate, units: 18 },
      url: "/v1/exhibits/returning-light-2027/contributions",
    });
    expect(accepted.statusCode).toBe(201);
    expect(accepted.json()).toMatchObject({
      status: "accepted",
      state: {
        featured: { progressBasisPoints: 5_682, personalContribution: 18 },
        todayContributedUnits: 18,
      },
    });

    const replay = await app.inject({
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        "idempotency-key": idempotencyKey,
      },
      method: "POST",
      payload: { local_date: localDate, units: 18 },
      url: "/v1/exhibits/returning-light-2027/contributions",
    });
    expect(replay.statusCode).toBe(200);
    expect(replay.json()).toMatchObject({
      status: "duplicate",
      state: { todayContributedUnits: 18 },
    });

    const contributions = await admin<
      Readonly<{
        cohort_snapshot_date: string;
        count: string;
        credited_microunits: string;
        effective_user_count: number;
        eligible_user_count: number;
        multiplier_micros: number;
      }>[]
    >`
      SELECT
        count(*)::text AS count,
        min(cohort_snapshot_date)::text AS cohort_snapshot_date,
        min(eligible_user_count)::integer AS eligible_user_count,
        min(effective_user_count)::integer AS effective_user_count,
        min(multiplier_micros)::integer AS multiplier_micros,
        min(credited_microunits)::text AS credited_microunits
      FROM exhibit_contributions
      WHERE installation_id = ${session.installationId}
    `;
    expect(contributions).toEqual([
      {
        cohort_snapshot_date: new Date().toISOString().slice(0, 10),
        count: "1",
        credited_microunits: "1800000000",
        effective_user_count: 100,
        eligible_user_count: 1,
        multiplier_micros: 100_000_000,
      },
    ]);
  });

  it("snapshots only community-enabled profiles opened within 28 days and never rewrites that UTC day", async () => {
    const app = await appForDatabase();
    const session = await registered(app);
    const localDate = new Date().toISOString().slice(0, 10);
    await admin`
      UPDATE users
      SET last_opened = now() - interval '29 days'
      WHERE id = (SELECT user_id FROM installations WHERE id = ${session.installationId})
    `;

    const inactiveState = await app.inject({
      headers: { authorization: ["Bear", "er ", session.accessToken].join("") },
      method: "GET",
      url: `/v1/exhibits/current?local_date=${localDate}`,
    });
    expect(inactiveState.statusCode).toBe(200);
    const [inactiveSnapshot] = await admin<
      Readonly<{ effective_user_count: number; eligible_user_count: number }>[]
    >`
      SELECT eligible_user_count, effective_user_count
      FROM exhibit_cohort_snapshots
      WHERE build_channel = 'dev'::build_channel
        AND snapshot_date = (now() AT TIME ZONE 'UTC')::date
    `;
    expect(inactiveSnapshot).toEqual({ eligible_user_count: 0, effective_user_count: 100 });

    const opened = await app.inject({
      headers: { authorization: ["Bear", "er ", session.accessToken].join("") },
      method: "POST",
      url: "/v1/profile/open",
    });
    expect(opened.statusCode).toBe(204);
    const frozenState = await app.inject({
      headers: { authorization: ["Bear", "er ", session.accessToken].join("") },
      method: "GET",
      url: `/v1/exhibits/current?local_date=${localDate}`,
    });
    expect(frozenState.statusCode).toBe(200);
    const [frozenSnapshot] = await admin<Readonly<{ eligible_user_count: number }>[]>`
      SELECT eligible_user_count
      FROM exhibit_cohort_snapshots
      WHERE build_channel = 'dev'::build_channel
        AND snapshot_date = (now() AT TIME ZONE 'UTC')::date
    `;
    expect(frozenSnapshot?.eligible_user_count).toBe(0);

    await admin`DELETE FROM exhibit_cohort_snapshots WHERE build_channel = 'dev'::build_channel`;
    const refreshedState = await app.inject({
      headers: { authorization: ["Bear", "er ", session.accessToken].join("") },
      method: "GET",
      url: `/v1/exhibits/current?local_date=${localDate}`,
    });
    expect(refreshedState.statusCode).toBe(200);
    const [activeSnapshot] = await admin<Readonly<{ eligible_user_count: number }>[]>`
      SELECT eligible_user_count
      FROM exhibit_cohort_snapshots
      WHERE build_channel = 'dev'::build_channel
        AND snapshot_date = (now() AT TIME ZONE 'UTC')::date
    `;
    expect(activeSnapshot?.eligible_user_count).toBe(1);
  });

  it("enforces one shared daily cap across featured and archive contributions", async () => {
    const app = await appForDatabase();
    const session = await registered(app);
    const localDate = new Date().toISOString().slice(0, 10);

    const first = await app.inject({
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: { local_date: localDate, units: 18 },
      url: "/v1/exhibits/returning-light-2027/contributions",
    });
    expect(first.statusCode).toBe(201);

    const overflow = await app.inject({
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: { local_date: localDate, units: 13 },
      url: "/v1/exhibits/long-night-2026/contributions",
    });
    expect(overflow.statusCode).toBe(409);
    expect(overflow.json()).toEqual({ error: "daily_cap_exceeded" });
  });

  it("rejects historical local dates before they can bypass the daily cap", async () => {
    const app = await appForDatabase();
    const session = await registered(app);
    const response = await app.inject({
      headers: {
        authorization: ["Bear", "er ", session.accessToken].join(""),
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: { local_date: "2020-01-01", units: 18 },
      url: "/v1/exhibits/returning-light-2027/contributions",
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toEqual({ error: "request_invalid" });
  });

  it("serializes concurrent cross-destination contributions against the shared cap", async () => {
    const app = await appForDatabase();
    const session = await registered(app);
    const localDate = new Date().toISOString().slice(0, 10);
    const headers = () => ({
      authorization: ["Bear", "er ", session.accessToken].join(""),
      "idempotency-key": randomUUID(),
    });

    const responses = await Promise.all([
      app.inject({
        headers: headers(),
        method: "POST",
        payload: { local_date: localDate, units: 18 },
        url: "/v1/exhibits/returning-light-2027/contributions",
      }),
      app.inject({
        headers: headers(),
        method: "POST",
        payload: { local_date: localDate, units: 18 },
        url: "/v1/exhibits/long-night-2026/contributions",
      }),
    ]);
    expect(responses.map((response) => response.statusCode).sort()).toEqual([201, 409]);

    const [total] = await admin<Readonly<{ total: number }>[]>`
      SELECT COALESCE(sum(units), 0)::integer AS total
      FROM exhibit_contributions
      WHERE installation_id = ${session.installationId}
        AND local_date = ${localDate}::date
    `;
    expect(total?.total).toBe(18);
  });
});
