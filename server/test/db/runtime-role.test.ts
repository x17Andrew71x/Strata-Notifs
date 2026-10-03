import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { buildAttestedApp } from "../../src/app.js";
import { createSqlClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import {
  attestRuntimeRole,
  type RuntimeAttestationError,
} from "../../src/db/runtime-attestation.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";
import { testConfig } from "../test-config.js";

const config = loadPostgresTestConfig();
const describePostgres = config ? describe : describe.skip;

function databaseConfig(databaseUrl: string) {
  return { ...testConfig, databaseUrl };
}

describePostgres("runtime database-role attestation", () => {
  let harness: PostgresTestHarness;

  beforeAll(async () => {
    if (!config) {
      throw new Error("PostgreSQL test configuration is required for this suite");
    }
    harness = await PostgresTestHarness.create(config);
  }, 30_000);

  afterAll(async () => {
    await harness?.close();
  });

  it("accepts the dedicated non-owner, non-superuser, non-BYPASSRLS runtime role", async () => {
    const runtime = createSqlClient(harness.runtimeDatabaseUrl);

    await expect(attestRuntimeRole(runtime)).resolves.toMatchObject({
      database: "afterchime_test",
      migrationHead: null,
      role: "afterchime_runtime",
    });

    await runtime.end({ timeout: 5 });
  });

  it("records the PostgreSQL version and applied migration head through the migrator", async () => {
    await expect(migrateDatabase(harness.migrationDatabaseUrl)).resolves.toMatchObject({
      database: "afterchime_test",
      migrationHead: expect.stringMatching(/^[a-f0-9]{64}$/),
      postgresVersion: expect.stringMatching(/^\d+\./),
    });
  });

  it("attests the runtime connection before building the application", async () => {
    const started = await buildAttestedApp({
      config: databaseConfig(harness.runtimeDatabaseUrl),
    });

    expect(started.runtimeAttestation.postgresVersion).toMatch(/^\d+\./);
    await started.app.close();
    await started.database.end({ timeout: 5 });
  });

  it("rejects a superuser connection", async () => {
    const admin = createSqlClient(harness.adminDatabaseUrl);

    await expect(attestRuntimeRole(admin)).rejects.toMatchObject({
      code: "runtime_role_superuser",
    } satisfies Partial<RuntimeAttestationError>);

    await admin.end({ timeout: 5 });
  });

  it("rejects a privileged session that has SET ROLE to the runtime role", async () => {
    const admin = createSqlClient(harness.adminDatabaseUrl);
    await admin.unsafe('SET ROLE "afterchime_runtime"');

    try {
      await expect(attestRuntimeRole(admin)).rejects.toMatchObject({
        code: "runtime_role_session_mismatch",
      } satisfies Partial<RuntimeAttestationError>);
    } finally {
      await admin.unsafe("RESET ROLE");
      await admin.end({ timeout: 5 });
    }
  });

  it("rejects the database owner even when it is not a superuser", async () => {
    const migration = createSqlClient(harness.migrationDatabaseUrl);

    await expect(attestRuntimeRole(migration)).rejects.toMatchObject({
      code: "runtime_role_database_owner",
    } satisfies Partial<RuntimeAttestationError>);
    await expect(
      buildAttestedApp({ config: databaseConfig(harness.migrationDatabaseUrl) }),
    ).rejects.toMatchObject({
      code: "runtime_role_database_owner",
    } satisfies Partial<RuntimeAttestationError>);

    await migration.end({ timeout: 5 });
  });

  it("rejects BYPASSRLS and administrative runtime roles", async () => {
    const cases = [
      { code: "runtime_role_bypassrls", name: "afterchime_unsafe_bypass", bypassRls: true },
      {
        code: "runtime_role_administrative",
        name: "afterchime_unsafe_createrole",
        createRole: true,
      },
      {
        code: "runtime_role_administrative",
        name: "afterchime_unsafe_createdb",
        createDatabase: true,
      },
      {
        code: "runtime_role_administrative",
        name: "afterchime_unsafe_replication",
        replication: true,
      },
    ] as const;

    for (const candidate of cases) {
      const unsafe = await harness.createUnsafeRole(candidate);
      const connection = createSqlClient(unsafe.databaseUrl);
      await expect(attestRuntimeRole(connection)).rejects.toMatchObject({
        code: candidate.code,
      } satisfies Partial<RuntimeAttestationError>);
      await connection.end({ timeout: 5 });
    }
  });

  it("rejects ownership of user schemas, relations, and SECURITY DEFINER functions", async () => {
    const cases = [
      {
        code: "runtime_role_schema_owner",
        name: "afterchime_unsafe_schema",
        protectedObject: "schema",
      },
      {
        code: "runtime_role_relation_owner",
        name: "afterchime_unsafe_relation",
        protectedObject: "relation",
      },
      {
        code: "runtime_role_security_definer_owner",
        name: "afterchime_unsafe_function",
        protectedObject: "security-definer",
      },
    ] as const;

    for (const candidate of cases) {
      const unsafe = await harness.createUnsafeRole(candidate);
      const connection = createSqlClient(unsafe.databaseUrl);
      await expect(attestRuntimeRole(connection)).rejects.toMatchObject({
        code: candidate.code,
      } satisfies Partial<RuntimeAttestationError>);
      await connection.end({ timeout: 5 });
    }
  });

  it("rejects a runtime role that can assume a privileged role", async () => {
    const unsafe = await harness.createUnsafeRole({
      name: "afterchime_unsafe_assumable",
      bypassRls: true,
    });
    const member = await harness.createUnsafeRole({
      name: "afterchime_unsafe_member",
      memberOf: unsafe.name,
    });
    const connection = createSqlClient(member.databaseUrl);

    await expect(attestRuntimeRole(connection)).rejects.toMatchObject({
      code: "runtime_role_privilege_path",
    } satisfies Partial<RuntimeAttestationError>);

    await connection.end({ timeout: 5 });
  });

  it("does not reject a PostgreSQL 17 membership with SET FALSE", async () => {
    if (!harness.supportsSetOption) {
      return;
    }

    const unsafe = await harness.createUnsafeRole({
      name: "afterchime_unsafe_not_assumable",
      bypassRls: true,
    });
    const member = await harness.createUnsafeRole({
      name: "afterchime_unsafe_no_set",
      memberOf: unsafe.name,
      setRole: false,
    });
    const connection = createSqlClient(member.databaseUrl);

    await expect(attestRuntimeRole(connection)).resolves.toMatchObject({
      role: member.name,
    });

    await connection.end({ timeout: 5 });
  });
});
