import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";

const config = loadPostgresTestConfig();
const describePostgres = config ? describe : describe.skip;

const requiredTables = [
  "analytics_consent_coverage",
  "analytics_daily_global",
  "analytics_daily_installation",
  "analytics_events",
  "analytics_notification_volume",
  "analytics_release_health",
  "auth_refresh_tokens",
  "consent_records",
  "daily_notification_aggregates",
  "exhibit_cohort_snapshots",
  "exhibit_contributions",
  "exhibit_unlocks",
  "exhibits",
  "idempotency_records",
  "installations",
  "job_runs",
  "outbox_jobs",
  "schema_metadata",
  "users",
] as const;

const userOwnedTables = [
  "analytics_daily_installation",
  "analytics_events",
  "auth_refresh_tokens",
  "consent_records",
  "daily_notification_aggregates",
  "exhibit_contributions",
  "exhibit_unlocks",
  "idempotency_records",
  "installations",
  "users",
] as const;

const ids = {
  installationA: "00000000-0000-4000-8000-000000000011",
  installationB: "00000000-0000-4000-8000-000000000012",
  installationC: "00000000-0000-4000-8000-000000000013",
  userA: "00000000-0000-4000-8000-000000000001",
  userB: "00000000-0000-4000-8000-000000000002",
} as const;

type ColumnRow = Readonly<{
  column_name: string;
  data_type: string;
  is_nullable: "NO" | "YES";
  table_name: string;
}>;

function columnsByTable(rows: readonly ColumnRow[]): ReadonlyMap<string, readonly ColumnRow[]> {
  const columns = new Map<string, ColumnRow[]>();
  for (const row of rows) {
    columns.set(row.table_name, [...(columns.get(row.table_name) ?? []), row]);
  }
  return columns;
}

function columnNames(columns: readonly ColumnRow[] | undefined): readonly string[] {
  return (columns ?? []).map((column) => column.column_name);
}

describePostgres("initial PostgreSQL schema migration", () => {
  let admin: DatabaseClient;
  let harness: PostgresTestHarness;
  let migrator: DatabaseClient;

  beforeAll(async () => {
    if (!config) {
      throw new Error("PostgreSQL test configuration is required for this suite");
    }
    harness = await PostgresTestHarness.create(config);
    const testDatabaseUrl = new URL(harness.adminDatabaseUrl);
    testDatabaseUrl.pathname = "/afterchime_test";
    admin = createSqlClient(testDatabaseUrl.toString());
    migrator = createSqlClient(harness.migrationDatabaseUrl);
  }, 30_000);

  afterAll(async () => {
    await migrator?.end({ timeout: 5 });
    await admin?.end({ timeout: 5 });
    await harness?.close();
  }, 30_000);

  it("fails closed when the packaged migration directory is missing", async () => {
    await expect(
      migrateDatabase(harness.migrationDatabaseUrl, {
        migrationsFolder: path.join(process.cwd(), "migrations-missing-for-test"),
      }),
    ).rejects.toThrow("migration directory is missing");
  });

  it("creates the typed operational tables, immutable identities, and privacy-safe aggregate fields", async () => {
    await migrateDatabase(harness.migrationDatabaseUrl);

    const tables = await migrator<Readonly<{ table_name: string }>[]>`
      SELECT table_name
      FROM information_schema.tables
      WHERE table_schema = 'public'
      ORDER BY table_name
    `;
    expect(tables.map((table) => table.table_name)).toEqual(
      expect.arrayContaining([...requiredTables]),
    );

    const columns = columnsByTable(
      await migrator<ColumnRow[]>`
        SELECT table_name, column_name, data_type, is_nullable
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = ANY(${[...requiredTables]})
        ORDER BY table_name, ordinal_position
      `,
    );

    expect(columnNames(columns.get("users"))).toEqual(
      expect.arrayContaining([
        "id",
        "build_channel",
        "is_synthetic",
        "last_opened",
        "created_at",
        "updated_at",
      ]),
    );
    expect(columnNames(columns.get("installations"))).toEqual(
      expect.arrayContaining([
        "id",
        "user_id",
        "app_version",
        "build_channel",
        "is_synthetic",
        "created_at",
        "last_seen_at",
      ]),
    );
    expect(columnNames(columns.get("auth_refresh_tokens"))).toEqual(
      expect.arrayContaining([
        "id",
        "installation_id",
        "family_id",
        "token_hash",
        "expires_at",
        "issued_at",
      ]),
    );
    expect(columnNames(columns.get("consent_records"))).toEqual(
      expect.arrayContaining([
        "id",
        "installation_id",
        "scope",
        "scope_version",
        "granted",
        "recorded_at",
      ]),
    );
    expect(columnNames(columns.get("analytics_events"))).toEqual(
      expect.arrayContaining([
        "id",
        "installation_id",
        "event_id",
        "event_name",
        "schema_version",
        "properties",
        "occurred_at",
        "received_at",
        "build_channel",
        "is_synthetic",
      ]),
    );
    expect(columnNames(columns.get("daily_notification_aggregates"))).toEqual(
      expect.arrayContaining([
        "id",
        "installation_id",
        "local_date",
        "revision",
        "eligible_count",
        "hourly_buckets",
        "category_counts",
        "game_pressure",
        "observation_completeness",
        "rules_version",
        "build_channel",
        "is_synthetic",
      ]),
    );
    expect(columnNames(columns.get("exhibits"))).toEqual(
      expect.arrayContaining([
        "id",
        "slug",
        "kind",
        "status",
        "target_units",
        "target_credit_units",
        "personal_minimum_units",
        "daily_contribution_cap",
        "total_units",
        "total_credit_microunits",
        "image_path",
        "starts_at",
        "ends_at",
      ]),
    );
    expect(columnNames(columns.get("exhibit_cohort_snapshots"))).toEqual(
      expect.arrayContaining([
        "build_channel",
        "snapshot_date",
        "eligible_user_count",
        "effective_user_count",
        "multiplier_micros",
        "created_at",
      ]),
    );
    expect(columnNames(columns.get("exhibit_contributions"))).toEqual(
      expect.arrayContaining([
        "id",
        "exhibit_id",
        "installation_id",
        "idempotency_key",
        "local_date",
        "units",
        "cohort_snapshot_date",
        "eligible_user_count",
        "effective_user_count",
        "multiplier_micros",
        "credited_microunits",
      ]),
    );
    expect(columnNames(columns.get("analytics_daily_installation"))).toEqual(
      expect.arrayContaining([
        "installation_id",
        "local_date",
        "analytics_event_count",
        "notification_eligible_count",
        "game_pressure_total",
        "build_channel",
        "is_synthetic",
      ]),
    );
    expect(columnNames(columns.get("analytics_daily_global"))).toEqual(
      expect.arrayContaining([
        "local_date",
        "active_installation_count",
        "analytics_event_count",
        "notification_eligible_count",
        "game_pressure_total",
      ]),
    );
    expect(columnNames(columns.get("analytics_consent_coverage"))).toEqual(
      expect.arrayContaining([
        "observed_date",
        "total_installation_count",
        "product_analytics_consent_count",
        "notification_aggregate_consent_count",
      ]),
    );
    expect(columnNames(columns.get("analytics_release_health"))).toEqual(
      expect.arrayContaining([
        "received_date",
        "app_version",
        "version_code",
        "analytics_event_count",
        "failure_event_count",
      ]),
    );
    expect(columnNames(columns.get("analytics_notification_volume"))).toEqual(
      expect.arrayContaining(["utc_date", "eligible_count", "reporting_installation_count"]),
    );
    expect(columnNames(columns.get("idempotency_records"))).toEqual(
      expect.arrayContaining([
        "id",
        "installation_id",
        "operation",
        "idempotency_key",
        "request_hash",
        "created_at",
        "expires_at",
      ]),
    );
    expect(columnNames(columns.get("outbox_jobs"))).toEqual(
      expect.arrayContaining([
        "id",
        "job_type",
        "dedupe_key",
        "status",
        "attempt_count",
        "available_at",
        "created_at",
      ]),
    );
    expect(columnNames(columns.get("job_runs"))).toEqual(
      expect.arrayContaining([
        "id",
        "job_name",
        "status",
        "started_at",
        "finished_at",
        "checkpoint",
        "failure_code",
        "lease_owner",
        "lease_expires_at",
      ]),
    );
    expect(columnNames(columns.get("schema_metadata"))).toEqual(
      expect.arrayContaining(["id", "metadata_key", "metadata_value", "created_at", "updated_at"]),
    );

    const typeByColumn = new Map(
      [...columns.values()]
        .flat()
        .map((column) => [`${column.table_name}.${column.column_name}`, column.data_type]),
    );
    expect(typeByColumn.get("users.id")).toBe("uuid");
    expect(typeByColumn.get("analytics_events.properties")).toBe("jsonb");
    expect(typeByColumn.get("daily_notification_aggregates.local_date")).toBe("date");
    expect(typeByColumn.get("daily_notification_aggregates.category_counts")).toBe("jsonb");
    expect(typeByColumn.get("users.last_opened")).toBe("timestamp with time zone");
    expect(typeByColumn.get("exhibits.total_units")).toBe("integer");
    expect(typeByColumn.get("exhibits.total_credit_microunits")).toBe("bigint");
    expect(typeByColumn.get("exhibit_contributions.credited_microunits")).toBe("bigint");
    expect(typeByColumn.get("auth_refresh_tokens.expires_at")).toBe("timestamp with time zone");
  }, 30_000);

  it("retires seeded placeholder Exhibits before they can reach the public surface", async () => {
    const rows = await migrator<
      Readonly<{
        build_channel: string;
        placeholder_artwork: boolean;
        slug: string;
        status: string;
      }>[]
    >`
      SELECT slug, build_channel::text, placeholder_artwork, status::text
      FROM exhibits
      WHERE placeholder_artwork = true
      ORDER BY slug
    `;

    expect(rows).toHaveLength(5);
    expect(rows.every((row) => row.build_channel === "dev" && row.status === "cancelled")).toBe(
      true,
    );
  });

  it("enforces ownership, bounded idempotency, aggregate revision, and synthetic-build constraints", async () => {
    const constraints = await migrator<
      Readonly<{
        column_name: string | null;
        constraint_name: string;
        constraint_type: string;
        table_name: string;
      }>[]
    >`
      SELECT
        constraints.table_name,
        constraints.constraint_name,
        constraints.constraint_type,
        key_columns.column_name
      FROM information_schema.table_constraints AS constraints
      LEFT JOIN information_schema.key_column_usage AS key_columns
        ON key_columns.constraint_schema = constraints.constraint_schema
        AND key_columns.constraint_name = constraints.constraint_name
        AND key_columns.table_name = constraints.table_name
      WHERE constraints.table_schema = 'public'
        AND constraints.table_name = ANY(${[...requiredTables]})
    `;
    const signatures = new Set(
      constraints.map(
        (constraint) =>
          `${constraint.table_name}:${constraint.constraint_type}:${constraint.column_name ?? ""}`,
      ),
    );

    const tablesWithCompositePrimaryKeys = new Set([
      "analytics_consent_coverage",
      "analytics_daily_global",
      "analytics_daily_installation",
      "analytics_notification_volume",
      "analytics_release_health",
      "exhibit_cohort_snapshots",
    ]);
    for (const table of requiredTables) {
      if (!tablesWithCompositePrimaryKeys.has(table)) {
        expect(signatures).toContain(`${table}:PRIMARY KEY:id`);
      }
    }
    expect(signatures).toContain("analytics_consent_coverage:PRIMARY KEY:observed_date");
    expect(signatures).toContain("analytics_daily_global:PRIMARY KEY:local_date");
    expect(signatures).toContain("analytics_daily_installation:PRIMARY KEY:installation_id");
    expect(signatures).toContain("analytics_notification_volume:PRIMARY KEY:utc_date");
    expect(signatures).toContain("analytics_release_health:PRIMARY KEY:received_date");
    expect(signatures).toContain("exhibit_cohort_snapshots:PRIMARY KEY:build_channel");
    expect(signatures).toContain("exhibit_cohort_snapshots:PRIMARY KEY:snapshot_date");
    expect(signatures).toContain("installations:FOREIGN KEY:user_id");
    expect(signatures).toContain("auth_refresh_tokens:FOREIGN KEY:installation_id");
    expect(signatures).toContain("consent_records:FOREIGN KEY:installation_id");
    expect(signatures).toContain("analytics_events:FOREIGN KEY:installation_id");
    expect(signatures).toContain("daily_notification_aggregates:FOREIGN KEY:installation_id");
    expect(signatures).toContain("idempotency_records:FOREIGN KEY:installation_id");
    expect(signatures).toContain("exhibit_contributions:FOREIGN KEY:installation_id");
    expect(signatures).toContain("exhibit_contributions:FOREIGN KEY:exhibit_id");
    expect(signatures).toContain("exhibit_unlocks:FOREIGN KEY:installation_id");
    expect(signatures).toContain("exhibit_unlocks:FOREIGN KEY:exhibit_id");
    expect(signatures).toContain("analytics_events:UNIQUE:event_id");
    expect(signatures).toContain("daily_notification_aggregates:UNIQUE:installation_id");
    expect(signatures).toContain("daily_notification_aggregates:UNIQUE:local_date");
    expect(signatures).toContain("idempotency_records:UNIQUE:installation_id");
    expect(signatures).toContain("idempotency_records:UNIQUE:operation");
    expect(signatures).toContain("idempotency_records:UNIQUE:idempotency_key");
    expect(signatures).toContain("outbox_jobs:UNIQUE:job_type");
    expect(signatures).toContain("outbox_jobs:UNIQUE:dedupe_key");

    const checks = await migrator<Readonly<{ constraint_name: string; definition: string }>[]>`
      SELECT pg_constraint.conname AS constraint_name, pg_get_constraintdef(pg_constraint.oid) AS definition
      FROM pg_constraint
      INNER JOIN pg_namespace ON pg_namespace.oid = pg_constraint.connamespace
      WHERE pg_namespace.nspname = 'public'
        AND pg_constraint.contype = 'c'
    `;
    expect(checks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ constraint_name: "users_synthetic_build_check" }),
        expect.objectContaining({ constraint_name: "installations_synthetic_build_check" }),
        expect.objectContaining({ constraint_name: "analytics_events_synthetic_build_check" }),
        expect.objectContaining({
          constraint_name: "daily_notification_aggregates_synthetic_build_check",
        }),
        expect.objectContaining({
          constraint_name: "daily_notification_aggregates_revision_check",
        }),
        expect.objectContaining({
          constraint_name: "daily_notification_aggregates_eligible_count_check",
        }),
        expect.objectContaining({ constraint_name: "outbox_jobs_attempt_count_check" }),
        expect.objectContaining({ constraint_name: "exhibits_target_units_check" }),
        expect.objectContaining({ constraint_name: "exhibits_total_units_check" }),
        expect.objectContaining({ constraint_name: "exhibits_target_credit_units_check" }),
        expect.objectContaining({ constraint_name: "exhibits_total_credit_microunits_check" }),
        expect.objectContaining({ constraint_name: "exhibit_cohort_snapshots_population_check" }),
        expect.objectContaining({ constraint_name: "exhibit_cohort_snapshots_multiplier_check" }),
        expect.objectContaining({ constraint_name: "exhibit_contributions_credit_check" }),
      ]),
    );

    await expect(
      admin`
        INSERT INTO users (id, build_channel, is_synthetic)
        VALUES ('00000000-0000-4000-8000-000000000099', 'prod', true)
      `,
    ).rejects.toThrow();
  });

  it("builds the query indexes needed by the planned replay, aggregate, and job boundaries", async () => {
    const indexes = await migrator<Readonly<{ indexname: string; tablename: string }>[]>`
      SELECT tablename, indexname
      FROM pg_indexes
      WHERE schemaname = 'public'
    `;
    const indexNames = new Set(indexes.map((index) => `${index.tablename}:${index.indexname}`));

    expect(indexNames).toContain("auth_refresh_tokens:auth_refresh_tokens_family_id_idx");
    expect(indexNames).toContain("consent_records:consent_records_installation_scope_recorded_idx");
    expect(indexNames).toContain("analytics_events:analytics_events_installation_occurred_idx");
    expect(indexNames).toContain(
      "daily_notification_aggregates:daily_notification_aggregates_local_date_idx",
    );
    expect(indexNames).toContain("outbox_jobs:outbox_jobs_claim_idx");
    expect(indexNames).toContain("job_runs:job_runs_job_name_started_idx");
    expect(indexNames).toContain("users:users_build_last_opened_idx");
    expect(indexNames).toContain("exhibits:exhibits_current_idx");
    expect(indexNames).toContain("exhibits:exhibits_one_open_per_kind_idx");
    expect(indexNames).toContain(
      "exhibit_contributions:exhibit_contributions_installation_date_idx",
    );
  });

  it("installs the bounded compatibility triggers for acceptance-time credit", async () => {
    const triggers = await migrator<Readonly<{ trigger_name: string }>[]>`
      SELECT trigger.tgname AS trigger_name
      FROM pg_trigger AS trigger
      INNER JOIN pg_class AS relation ON relation.oid = trigger.tgrelid
      INNER JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
      WHERE namespace.nspname = 'public'
        AND relation.relname = 'exhibit_contributions'
        AND trigger.tgisinternal = false
      ORDER BY trigger.tgname
    `;
    expect(triggers).toEqual([
      { trigger_name: "exhibit_contributions_apply_credit" },
      { trigger_name: "exhibit_contributions_prepare" },
    ]);
  });

  it("enables and forces row-level security, grants only the runtime role, and rejects cross-installation reads and writes", async () => {
    const rls = await migrator<
      Readonly<{ relforcerowsecurity: boolean; relname: string; relrowsecurity: boolean }>[]
    >`
      SELECT relation.relname, relation.relrowsecurity, relation.relforcerowsecurity
      FROM pg_class AS relation
      INNER JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
      WHERE namespace.nspname = 'public'
        AND relation.relname = ANY(${[...userOwnedTables]})
    `;
    expect(rls).toHaveLength(userOwnedTables.length);
    for (const table of userOwnedTables) {
      expect(rls).toContainEqual({
        relname: table,
        relrowsecurity: true,
        relforcerowsecurity: true,
      });
    }

    const policies = await migrator<
      Readonly<{
        policyname: string;
        qual: string | null;
        roles: string[];
        tablename: string;
        with_check: string | null;
      }>[]
    >`
      SELECT tablename, policyname, roles, qual, with_check
      FROM pg_policies
      WHERE schemaname = 'public'
        AND tablename = ANY(${[...userOwnedTables]})
    `;
    for (const table of userOwnedTables) {
      expect(policies).toContainEqual(
        expect.objectContaining({
          tablename: table,
          policyname: `${table}_runtime_scope`,
          roles: ["afterchime_runtime"],
          qual: expect.stringContaining("afterchime"),
          with_check: expect.stringContaining("afterchime"),
        }),
      );
    }

    for (const table of userOwnedTables) {
      const [privilege] = await migrator<Readonly<{ allowed: boolean }>[]>`
        SELECT has_table_privilege('afterchime_runtime', ${`public.${table}`}, 'SELECT') AS allowed
      `;
      expect(privilege?.allowed).toBe(true);
    }
    const [cohortPrivileges] = await migrator<
      Readonly<{ direct_table_read: boolean; function_execute: boolean }>[]
    >`
      SELECT
        has_table_privilege(
          'afterchime_runtime',
          'public.exhibit_cohort_snapshots',
          'SELECT'
        ) AS direct_table_read,
        has_function_privilege(
          'afterchime_runtime',
          'public.afterchime_exhibit_cohort_snapshot(build_channel)',
          'EXECUTE'
        ) AS function_execute
    `;
    expect(cohortPrivileges).toEqual({ direct_table_read: false, function_execute: true });

    await admin`
      INSERT INTO users (id, build_channel, is_synthetic)
      VALUES
        (${ids.userA}, 'dev', false),
        (${ids.userB}, 'dev', false)
    `;
    await admin`
      INSERT INTO installations (id, user_id, app_version, build_channel, is_synthetic)
      VALUES
        (${ids.installationA}, ${ids.userA}, '0.2.0-dev', 'dev', false),
        (${ids.installationB}, ${ids.userB}, '0.2.0-dev', 'dev', false)
    `;

    const runtime = createSqlClient(harness.runtimeDatabaseUrl);
    try {
      const withoutContext = await runtime<Readonly<{ id: string }>[]>`
        SELECT id FROM installations WHERE id = ${ids.installationA}
      `;
      expect(withoutContext).toEqual([]);
      await expect(
        runtime`SELECT * FROM public.afterchime_exhibit_cohort_snapshot('dev'::build_channel)`,
      ).rejects.toThrow();

      const ownInstallation = await runtime.begin(async (transaction) => {
        await transaction`SELECT set_config('afterchime.user_id', ${ids.userA}, true)`;
        await transaction`SELECT set_config('afterchime.installation_id', ${ids.installationA}, true)`;
        return transaction<Readonly<{ id: string }>[]>`
          SELECT id FROM installations WHERE id = ${ids.installationA}
        `;
      });
      expect(ownInstallation).toEqual([{ id: ids.installationA }]);

      const crossInstallation = await runtime.begin(async (transaction) => {
        await transaction`SELECT set_config('afterchime.user_id', ${ids.userB}, true)`;
        await transaction`SELECT set_config('afterchime.installation_id', ${ids.installationB}, true)`;
        return transaction<Readonly<{ id: string }>[]>`
          SELECT id FROM installations WHERE id = ${ids.installationA}
        `;
      });
      expect(crossInstallation).toEqual([]);

      const mismatchedContext = await runtime.begin(async (transaction) => {
        await transaction`SELECT set_config('afterchime.user_id', ${ids.userA}, true)`;
        await transaction`SELECT set_config('afterchime.installation_id', ${ids.installationB}, true)`;
        return transaction<Readonly<{ id: string }>[]>`
          SELECT id FROM installations WHERE id = ${ids.installationB}
        `;
      });
      expect(mismatchedContext).toEqual([]);

      await expect(
        runtime.begin(async (transaction) => {
          await transaction`SELECT set_config('afterchime.user_id', ${ids.userB}, true)`;
          await transaction`SELECT set_config('afterchime.installation_id', ${ids.installationB}, true)`;
          return transaction`
            INSERT INTO installations (id, user_id, app_version, build_channel, is_synthetic)
            VALUES (${ids.installationC}, ${ids.userA}, '0.2.0-dev', 'dev', false)
          `;
        }),
      ).rejects.toThrow();
      const deniedWrite = await admin<Readonly<{ count: string }>[]>`
        SELECT count(*)::text AS count FROM installations WHERE id = ${ids.installationC}
      `;
      expect(deniedWrite).toEqual([{ count: "0" }]);
    } finally {
      await runtime.end({ timeout: 5 });
    }
  });

  it("reapplies as a no-op with stable migration-history records", async () => {
    const [before] = await migrator<Readonly<{ count: string; hash: string | null }>[]>`
      SELECT
        (SELECT count(*)::text FROM drizzle.__drizzle_migrations) AS count,
        (SELECT hash FROM drizzle.__drizzle_migrations ORDER BY created_at DESC LIMIT 1) AS hash
    `;
    const rerun = await migrateDatabase(harness.migrationDatabaseUrl);
    const [after] = await migrator<Readonly<{ count: string; hash: string | null }>[]>`
      SELECT
        (SELECT count(*)::text FROM drizzle.__drizzle_migrations) AS count,
        (SELECT hash FROM drizzle.__drizzle_migrations ORDER BY created_at DESC LIMIT 1) AS hash
    `;

    expect(before).toEqual({ count: "12", hash: expect.any(String) });
    expect(after).toEqual(before);
    expect(rerun.migrationHead).toBe(before?.hash);
  });
});
