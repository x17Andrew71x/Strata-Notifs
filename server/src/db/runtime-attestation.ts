import type { DatabaseClient } from "./client.js";
import { type DatabaseReadiness, readDatabaseReadiness } from "./migrate.js";

export type RuntimeAttestationErrorCode =
  | "runtime_role_administrative"
  | "runtime_role_bypassrls"
  | "runtime_role_database_owner"
  | "runtime_role_missing"
  | "runtime_role_privilege_path"
  | "runtime_role_relation_owner"
  | "runtime_role_schema_owner"
  | "runtime_role_security_definer_owner"
  | "runtime_role_session_mismatch"
  | "runtime_role_superuser";

export class RuntimeAttestationError extends Error {
  public readonly code: RuntimeAttestationErrorCode;

  public constructor(code: RuntimeAttestationErrorCode) {
    super(code);
    this.code = code;
    this.name = "RuntimeAttestationError";
  }
}

type RoleState = Readonly<{
  administrative: boolean;
  bypass_rls: boolean;
  database_owner: boolean;
  owns_protected_relation: boolean;
  owns_protected_schema: boolean;
  owns_security_definer_function: boolean;
  role_name: string;
  superuser: boolean;
}>;

const roleStateFields = `
  r.rolname AS role_name,
  r.rolsuper AS superuser,
  r.rolbypassrls AS bypass_rls,
  (r.rolcreaterole OR r.rolcreatedb OR r.rolreplication) AS administrative,
  EXISTS (
    SELECT 1
    FROM pg_database AS database
    WHERE database.datname = current_database()
      AND database.datdba = r.oid
  ) AS database_owner,
  EXISTS (
    SELECT 1
    FROM pg_namespace AS namespace
    WHERE namespace.nspowner = r.oid
      AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
      AND namespace.nspname NOT LIKE 'pg_toast%'
      AND namespace.nspname NOT LIKE 'pg_temp%'
  ) AS owns_protected_schema,
  EXISTS (
    SELECT 1
    FROM pg_class AS relation
    INNER JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE relation.relowner = r.oid
      AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
      AND namespace.nspname NOT LIKE 'pg_toast%'
      AND namespace.nspname NOT LIKE 'pg_temp%'
  ) AS owns_protected_relation,
  EXISTS (
    SELECT 1
    FROM pg_proc AS procedure
    INNER JOIN pg_namespace AS namespace ON namespace.oid = procedure.pronamespace
    WHERE procedure.proowner = r.oid
      AND procedure.prosecdef
      AND namespace.nspname NOT IN ('pg_catalog', 'information_schema')
      AND namespace.nspname NOT LIKE 'pg_toast%'
      AND namespace.nspname NOT LIKE 'pg_temp%'
  ) AS owns_security_definer_function
`;

async function assertDirectSession(sql: DatabaseClient): Promise<void> {
  const [identity] = await sql<
    Readonly<{ direct: boolean }>[]
  >`SELECT current_user = session_user AS direct`;

  if (!identity) {
    throw new RuntimeAttestationError("runtime_role_missing");
  }
  if (!identity.direct) {
    throw new RuntimeAttestationError("runtime_role_session_mismatch");
  }
}

async function currentRoleState(sql: DatabaseClient): Promise<RoleState> {
  const [role] = await sql.unsafe<RoleState[]>(`
    SELECT ${roleStateFields}
    FROM pg_roles AS r
    WHERE r.rolname = current_user
  `);

  if (!role) {
    throw new RuntimeAttestationError("runtime_role_missing");
  }

  return role;
}

async function supportsRoleSetOption(sql: DatabaseClient): Promise<boolean> {
  const [capability] = await sql<Readonly<{ supported: boolean }>[]>`SELECT EXISTS (
      SELECT 1
      FROM pg_attribute AS attribute
      INNER JOIN pg_class AS relation ON relation.oid = attribute.attrelid
      INNER JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
      WHERE namespace.nspname = 'pg_catalog'
        AND relation.relname = 'pg_auth_members'
        AND attribute.attname = 'set_option'
        AND NOT attribute.attisdropped
    ) AS supported`;
  return capability?.supported ?? false;
}

async function assumedRoleStates(sql: DatabaseClient): Promise<readonly RoleState[]> {
  const membershipPrivilege = (await supportsRoleSetOption(sql)) ? "SET" : "MEMBER";
  return sql.unsafe<RoleState[]>(`
    SELECT ${roleStateFields}
    FROM pg_roles AS r
    WHERE r.rolname <> current_user
      AND pg_has_role(current_user, r.oid, '${membershipPrivilege}')
  `);
}

function assertSafeRole(role: RoleState): void {
  if (role.superuser) {
    throw new RuntimeAttestationError("runtime_role_superuser");
  }
  if (role.bypass_rls) {
    throw new RuntimeAttestationError("runtime_role_bypassrls");
  }
  if (role.administrative) {
    throw new RuntimeAttestationError("runtime_role_administrative");
  }
  if (role.database_owner) {
    throw new RuntimeAttestationError("runtime_role_database_owner");
  }
  if (role.owns_protected_schema) {
    throw new RuntimeAttestationError("runtime_role_schema_owner");
  }
  if (role.owns_protected_relation) {
    throw new RuntimeAttestationError("runtime_role_relation_owner");
  }
  if (role.owns_security_definer_function) {
    throw new RuntimeAttestationError("runtime_role_security_definer_owner");
  }
}

export type RuntimeDatabaseAttestation = DatabaseReadiness &
  Readonly<{
    role: string;
  }>;

export async function attestRuntimeRole(sql: DatabaseClient): Promise<RuntimeDatabaseAttestation> {
  await assertDirectSession(sql);

  const currentRole = await currentRoleState(sql);
  assertSafeRole(currentRole);

  const assumedRoles = await assumedRoleStates(sql);
  for (const role of assumedRoles) {
    try {
      assertSafeRole(role);
    } catch (error) {
      if (error instanceof RuntimeAttestationError) {
        throw new RuntimeAttestationError("runtime_role_privilege_path");
      }
      throw error;
    }
  }

  const readiness = await readDatabaseReadiness(sql);
  return {
    ...readiness,
    role: currentRole.role_name,
  };
}
