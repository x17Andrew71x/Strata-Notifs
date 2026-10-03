import postgres from "postgres";

export type DatabaseClient = ReturnType<typeof postgres>;

export function createSqlClient(databaseUrl: string): DatabaseClient {
  return postgres(databaseUrl, {
    connect_timeout: 5,
    idle_timeout: 5,
    max: 1,
  });
}
