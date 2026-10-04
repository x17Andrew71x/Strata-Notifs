import { z } from "zod";
import { createSqlClient, type DatabaseClient } from "./client.js";

const secret = z.string().min(32);

const bootstrapEnvironmentSchema = z.object({
  NODE_ENV: z.literal("development"),
  DATABASE_ADMIN_URL: z.string().startsWith("postgresql://"),
  AFTERCHIME_BOOTSTRAP_MIGRATOR_PASSWORD: secret,
  AFTERCHIME_BOOTSTRAP_RUNTIME_PASSWORD: secret,
});

export type DevelopmentBootstrap = Readonly<{
  adminDatabaseUrl: string;
  migratorPassword: string;
  runtimePassword: string;
}>;

export function resolveDevelopmentBootstrap(
  environment: NodeJS.ProcessEnv,
): DevelopmentBootstrap | undefined {
  // biome-ignore lint/complexity/useLiteralKeys: Node's environment index signature requires bracket access.
  if (!environment["DATABASE_ADMIN_URL"]) return undefined;

  const value = bootstrapEnvironmentSchema.parse(environment);
  return {
    adminDatabaseUrl: value.DATABASE_ADMIN_URL,
    migratorPassword: value.AFTERCHIME_BOOTSTRAP_MIGRATOR_PASSWORD,
    runtimePassword: value.AFTERCHIME_BOOTSTRAP_RUNTIME_PASSWORD,
  };
}

export async function bootstrapDevelopmentDatabase(
  admin: DatabaseClient,
  bootstrap: DevelopmentBootstrap,
): Promise<void> {
  await admin`
    SELECT
      set_config('afterchime.bootstrap_migrator_password', ${bootstrap.migratorPassword}, false),
      set_config('afterchime.bootstrap_runtime_password', ${bootstrap.runtimePassword}, false)
  `;
  await admin.unsafe(`
    DO $bootstrap$
    BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'afterchime_migrator') THEN
        CREATE ROLE afterchime_migrator LOGIN;
      END IF;
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'afterchime_runtime') THEN
        CREATE ROLE afterchime_runtime LOGIN;
      END IF;

      EXECUTE format(
        'ALTER ROLE afterchime_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION PASSWORD %L',
        current_setting('afterchime.bootstrap_migrator_password')
      );
      EXECUTE format(
        'ALTER ROLE afterchime_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION PASSWORD %L',
        current_setting('afterchime.bootstrap_runtime_password')
      );
    END
    $bootstrap$;
  `);

  const [database] = await admin<
    Readonly<{ exists: boolean }>[]
  >`SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'afterchime') AS exists`;
  if (!database) throw new Error("database bootstrap state was unavailable");

  if (!database.exists) {
    await admin.unsafe("CREATE DATABASE afterchime OWNER afterchime_migrator");
  } else {
    await admin.unsafe("ALTER DATABASE afterchime OWNER TO afterchime_migrator");
  }
  await admin.unsafe("REVOKE ALL ON DATABASE afterchime FROM PUBLIC");
  await admin.unsafe("GRANT CONNECT ON DATABASE afterchime TO afterchime_runtime");
}

async function main(): Promise<void> {
  const bootstrap = resolveDevelopmentBootstrap(process.env);
  if (!bootstrap) {
    process.stdout.write('{"status":"skipped"}\n');
    return;
  }

  const admin = createSqlClient(bootstrap.adminDatabaseUrl);
  try {
    await bootstrapDevelopmentDatabase(admin, bootstrap);
    process.stdout.write('{"status":"bootstrapped"}\n');
  } finally {
    await admin.end({ timeout: 5 });
  }
}

if (process.argv[1]?.endsWith("bootstrap-development.js")) {
  await main();
}
