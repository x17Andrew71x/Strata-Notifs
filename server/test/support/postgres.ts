import { randomBytes } from "node:crypto";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";

const ADMIN_URL_KEY = "AFTERCHIME_TEST_ADMIN_DATABASE_URL";
const OWNED_CLUSTER_KEY = "AFTERCHIME_TEST_CLUSTER_OWNED";
const TEST_DATABASE = "afterchime_test";
const MIGRATION_ROLE = "afterchime_migrator";
const RUNTIME_ROLE = "afterchime_runtime";

export type PostgresTestConfig = Readonly<{
  adminDatabaseUrl: string;
}>;

export type UnsafeRoleOptions = Readonly<{
  bypassRls?: boolean;
  createDatabase?: boolean;
  createRole?: boolean;
  memberOf?: string;
  name: string;
  protectedObject?: "schema" | "relation" | "security-definer";
  replication?: boolean;
  setRole?: boolean;
}>;

export type CreatedRole = Readonly<{
  databaseUrl: string;
  name: string;
}>;

function isAllowedTestUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      url.protocol === "postgresql:" &&
      ["127.0.0.1", "localhost", "::1"].includes(url.hostname) &&
      url.port.length > 0 &&
      url.pathname === "/postgres"
    );
  } catch {
    return false;
  }
}

export function loadPostgresTestConfig(
  environment: NodeJS.ProcessEnv = process.env,
): PostgresTestConfig | null {
  const adminDatabaseUrl = environment[ADMIN_URL_KEY];
  if (!adminDatabaseUrl) {
    return null;
  }
  if (environment[OWNED_CLUSTER_KEY] !== "true") {
    throw new Error(`${OWNED_CLUSTER_KEY}=true is required for destructive PostgreSQL tests`);
  }
  if (!isAllowedTestUrl(adminDatabaseUrl)) {
    throw new Error(`${ADMIN_URL_KEY} must target a loopback PostgreSQL database named postgres`);
  }

  return { adminDatabaseUrl };
}

function quoteIdentifier(identifier: string): string {
  if (!/^afterchime_[a-z_]+$/.test(identifier)) {
    throw new Error("test role name must use the afterchime_ prefix");
  }
  return `"${identifier}"`;
}

function quoteLiteral(value: string): string {
  return `'${value.replaceAll("'", "''")}'`;
}

function roleDatabaseUrl(adminDatabaseUrl: string, role: string, password: string): string {
  const url = new URL(adminDatabaseUrl);
  url.username = role;
  url.password = password;
  url.pathname = `/${TEST_DATABASE}`;
  return url.toString();
}

function testDatabaseAdminUrl(adminDatabaseUrl: string): string {
  const url = new URL(adminDatabaseUrl);
  url.pathname = `/${TEST_DATABASE}`;
  return url.toString();
}

export class PostgresTestHarness {
  public readonly adminDatabaseUrl: string;
  public readonly migrationDatabaseUrl: string;
  public readonly runtimeDatabaseUrl: string;
  public readonly supportsSetOption: boolean;
  private readonly admin: DatabaseClient;
  private readonly createdRoles = new Set<string>([MIGRATION_ROLE, RUNTIME_ROLE]);
  private readonly migrationPassword = randomBytes(32).toString("base64url");
  private readonly runtimePassword = randomBytes(32).toString("base64url");
  private readonly unsafePassword = randomBytes(32).toString("base64url");

  private constructor(
    config: PostgresTestConfig,
    admin: DatabaseClient,
    supportsSetOption: boolean,
  ) {
    this.adminDatabaseUrl = config.adminDatabaseUrl;
    this.migrationDatabaseUrl = roleDatabaseUrl(
      config.adminDatabaseUrl,
      MIGRATION_ROLE,
      this.migrationPassword,
    );
    this.runtimeDatabaseUrl = roleDatabaseUrl(
      config.adminDatabaseUrl,
      RUNTIME_ROLE,
      this.runtimePassword,
    );
    this.admin = admin;
    this.supportsSetOption = supportsSetOption;
  }

  public static async create(config: PostgresTestConfig): Promise<PostgresTestHarness> {
    const admin = createSqlClient(config.adminDatabaseUrl);
    const [capability] = await admin<Readonly<{ supported: boolean }>[]>`SELECT EXISTS (
        SELECT 1
        FROM pg_attribute AS attribute
        INNER JOIN pg_class AS relation ON relation.oid = attribute.attrelid
        INNER JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = 'pg_catalog'
          AND relation.relname = 'pg_auth_members'
          AND attribute.attname = 'set_option'
          AND NOT attribute.attisdropped
      ) AS supported`;
    const harness = new PostgresTestHarness(config, admin, capability?.supported ?? false);
    await harness.reset();
    return harness;
  }

  public async createUnsafeRole(options: UnsafeRoleOptions): Promise<CreatedRole> {
    const role = quoteIdentifier(options.name);
    const membership = options.memberOf ? quoteIdentifier(options.memberOf) : null;
    const attributes = [
      options.bypassRls ? "BYPASSRLS" : "NOBYPASSRLS",
      options.createDatabase ? "CREATEDB" : "NOCREATEDB",
      options.createRole ? "CREATEROLE" : "NOCREATEROLE",
      options.replication ? "REPLICATION" : "NOREPLICATION",
    ].join(" ");

    await this.admin.unsafe(
      `CREATE ROLE ${role} LOGIN NOSUPERUSER NOINHERIT ${attributes} PASSWORD ${quoteLiteral(this.unsafePassword)}`,
    );
    this.createdRoles.add(options.name);
    await this.admin.unsafe(`GRANT CONNECT ON DATABASE "${TEST_DATABASE}" TO ${role}`);

    if (membership) {
      const setRole = this.supportsSetOption
        ? ` WITH SET ${options.setRole === false ? "FALSE" : "TRUE"}`
        : "";
      await this.admin.unsafe(`GRANT ${membership} TO ${role}${setRole}`);
    }

    const databaseUrl = roleDatabaseUrl(this.adminDatabaseUrl, options.name, this.unsafePassword);
    if (options.protectedObject) {
      const testDatabase = createSqlClient(testDatabaseAdminUrl(this.adminDatabaseUrl));
      const connection = createSqlClient(databaseUrl);
      try {
        await testDatabase.unsafe(`GRANT CREATE ON SCHEMA public TO ${role}`);
        if (options.protectedObject === "schema") {
          const schema = quoteIdentifier(`${options.name}_schema`);
          await this.admin.unsafe(`GRANT CREATE ON DATABASE "${TEST_DATABASE}" TO ${role}`);
          await connection.unsafe(`CREATE SCHEMA ${schema} AUTHORIZATION ${role}`);
        } else if (options.protectedObject === "relation") {
          await connection.unsafe(
            `CREATE TABLE public.${quoteIdentifier(`${options.name}_table`)} (id integer)`,
          );
        } else {
          await connection.unsafe(
            `CREATE FUNCTION public.${quoteIdentifier(`${options.name}_function`)}() RETURNS integer LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'`,
          );
        }
      } finally {
        await connection.end({ timeout: 5 });
        await testDatabase.end({ timeout: 5 });
      }
    }

    return { databaseUrl, name: options.name };
  }

  public async close(): Promise<void> {
    try {
      await this.reset();
    } finally {
      await this.admin.end({ timeout: 5 });
    }
  }

  private async reset(): Promise<void> {
    const [existingDatabase] = await this.admin<Readonly<{ exists: boolean }>[]>`
      SELECT EXISTS (
        SELECT 1
        FROM pg_database
        WHERE datname = ${TEST_DATABASE}
      ) AS exists
    `;

    if (existingDatabase?.exists) {
      const testDatabase = createSqlClient(testDatabaseAdminUrl(this.adminDatabaseUrl));
      try {
        for (const role of [...this.createdRoles].reverse()) {
          await testDatabase.unsafe(`DROP OWNED BY ${quoteIdentifier(role)}`);
          await this.admin.unsafe(`DROP OWNED BY ${quoteIdentifier(role)}`);
        }
      } finally {
        await testDatabase.end({ timeout: 5 });
      }
    }

    await this.admin`
      SELECT pg_terminate_backend(pid)
      FROM pg_stat_activity
      WHERE datname = ${TEST_DATABASE}
        AND pid <> pg_backend_pid()
    `;
    await this.admin.unsafe(`DROP DATABASE IF EXISTS "${TEST_DATABASE}"`);

    for (const role of [...this.createdRoles].reverse()) {
      await this.admin.unsafe(`DROP ROLE IF EXISTS ${quoteIdentifier(role)}`);
    }

    await this.admin.unsafe(
      `CREATE ROLE ${quoteIdentifier(MIGRATION_ROLE)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION PASSWORD ${quoteLiteral(this.migrationPassword)}`,
    );
    await this.admin.unsafe(
      `CREATE ROLE ${quoteIdentifier(RUNTIME_ROLE)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION PASSWORD ${quoteLiteral(this.runtimePassword)}`,
    );
    await this.admin.unsafe(
      `CREATE DATABASE "${TEST_DATABASE}" OWNER ${quoteIdentifier(MIGRATION_ROLE)}`,
    );
    await this.admin.unsafe(`REVOKE ALL ON DATABASE "${TEST_DATABASE}" FROM PUBLIC`);
    await this.admin.unsafe(
      `GRANT CONNECT ON DATABASE "${TEST_DATABASE}" TO ${quoteIdentifier(RUNTIME_ROLE)}`,
    );
  }
}
