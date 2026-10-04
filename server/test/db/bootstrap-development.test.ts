import { describe, expect, it } from "vitest";
import { resolveDevelopmentBootstrap } from "../../src/db/bootstrap-development.js";

const validEnvironment = {
  NODE_ENV: "development",
  DATABASE_ADMIN_URL: "postgresql://postgres:admin@postgres.internal:5432/railway",
  AFTERCHIME_BOOTSTRAP_MIGRATOR_PASSWORD: "a".repeat(32),
  AFTERCHIME_BOOTSTRAP_RUNTIME_PASSWORD: "b".repeat(32),
};

describe("development database bootstrap configuration", () => {
  it("does nothing after the development-only admin reference has been removed", () => {
    expect(resolveDevelopmentBootstrap({ NODE_ENV: "development" })).toBeUndefined();
  });

  it("accepts the isolated development bootstrap configuration", () => {
    expect(resolveDevelopmentBootstrap(validEnvironment)).toEqual({
      adminDatabaseUrl: validEnvironment.DATABASE_ADMIN_URL,
      migratorPassword: validEnvironment.AFTERCHIME_BOOTSTRAP_MIGRATOR_PASSWORD,
      runtimePassword: validEnvironment.AFTERCHIME_BOOTSTRAP_RUNTIME_PASSWORD,
    });
  });

  it("fails closed outside development", () => {
    expect(() =>
      resolveDevelopmentBootstrap({ ...validEnvironment, NODE_ENV: "production" }),
    ).toThrow();
  });

  it("rejects an incomplete bootstrap credential set", () => {
    const { AFTERCHIME_BOOTSTRAP_RUNTIME_PASSWORD: _runtimePassword, ...missingRuntime } =
      validEnvironment;
    expect(() => resolveDevelopmentBootstrap(missingRuntime)).toThrow();
  });
});
