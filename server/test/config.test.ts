import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";

const validEnvironment = {
  NODE_ENV: "development",
  DATABASE_URL: "postgresql://afterchime:replace-me@127.0.0.1:54329/afterchime",
  ACCESS_TOKEN_SECRET: "an-access-secret-that-is-definitely-long-enough",
  REFRESH_TOKEN_PEPPER: "a-refresh-pepper-that-is-definitely-long-enough",
};

describe("loadConfig", () => {
  it("parses safe defaults and origin lists", () => {
    const config = loadConfig({
      ...validEnvironment,
      CORS_ORIGINS: "https://one.example, https://two.example",
    });

    expect(config.port).toBe(3000);
    expect(config.buildChannel).toBe("dev");
    expect(config.tokenAudience).toBe("afterchime-dev");
    expect(config.allowDevAuth).toBe(false);
    expect(config.developmentModuleEnabled).toBe(false);
    expect(config.deployedRevision).toBe("0".repeat(40));
    expect(config.corsOrigins).toEqual(["https://one.example", "https://two.example"]);
  });

  it("derives the token audience from server environment rather than client input", () => {
    const config = loadConfig({ ...validEnvironment, NODE_ENV: "production" });

    expect(config.buildChannel).toBe("prod");
    expect(config.tokenAudience).toBe("afterchime-prod");
  });

  it("rejects weak secrets", () => {
    expect(() =>
      loadConfig({
        ...validEnvironment,
        ACCESS_TOKEN_SECRET: "short",
      }),
    ).toThrow();
  });

  it("accepts only an explicit development feedback flag and lowercase deployed revision", () => {
    const config = loadConfig({
      ...validEnvironment,
      ADMIN_API_KEY_HASH: "d".repeat(64),
      DEV_FEEDBACK_ENABLED: "true",
      RAILWAY_GIT_COMMIT_SHA: "a".repeat(40),
    });
    expect(config.developmentModuleEnabled).toBe(true);
    expect(config.deployedRevision).toBe("a".repeat(40));
    expect(() => loadConfig({ ...validEnvironment, DEPLOYED_REVISION: "A".repeat(40) })).toThrow();
    expect(() => loadConfig({ ...validEnvironment, DEV_FEEDBACK_ENABLED: "yes" })).toThrow();
    expect(() => loadConfig({ ...validEnvironment, DEV_FEEDBACK_ENABLED: "true" })).toThrow(
      "development feedback requires an admin key digest",
    );
    expect(() =>
      loadConfig({
        ...validEnvironment,
        ADMIN_API_KEY_HASH: "d".repeat(64),
        DEV_FEEDBACK_ENABLED: "true",
      }),
    ).toThrow("development feedback requires Railway source revision proof");
  });

  it("validates external development feedback projects as complete secret-backed sets", () => {
    const config = loadConfig({
      ...validEnvironment,
      DEV_FEEDBACK_DECREE_ADMIN_KEY_HASH: "a".repeat(64),
      DEV_FEEDBACK_DECREE_CLIENT_KEY_HASH: "b".repeat(64),
      DEV_FEEDBACK_DECREE_DEPLOYMENT_URL: "https://decree.example/version.json",
    });
    expect(config.feedbackProjects.decree).toEqual({
      adminKeyHash: "a".repeat(64),
      clientKeyHash: "b".repeat(64),
      deploymentUrl: "https://decree.example/version.json",
    });
    expect(config.feedbackProjects["please-just-sleep"].clientKeyHash).toBeNull();
    expect(() =>
      loadConfig({
        ...validEnvironment,
        DEV_FEEDBACK_DECREE_ADMIN_KEY_HASH: "a".repeat(64),
      }),
    ).toThrow("development feedback configuration is incomplete for decree");
    expect(() =>
      loadConfig({
        ...validEnvironment,
        DEV_FEEDBACK_PJS_ADMIN_KEY_HASH: "a".repeat(64),
        DEV_FEEDBACK_PJS_CLIENT_KEY_HASH: "b".repeat(64),
        DEV_FEEDBACK_PJS_DEPLOYMENT_URL: "http://pjs.example/version.json",
      }),
    ).toThrow("development feedback configuration is incomplete for please-just-sleep");
  });

  it("uses Railway's exact source revision when no explicit override is supplied", () => {
    const config = loadConfig({
      ...validEnvironment,
      RAILWAY_GIT_COMMIT_SHA: "b".repeat(40),
    });
    expect(config.deployedRevision).toBe("b".repeat(40));
    expect(() =>
      loadConfig({
        ...validEnvironment,
        DEPLOYED_REVISION: "c".repeat(40),
        RAILWAY_GIT_COMMIT_SHA: "b".repeat(40),
      }),
    ).toThrow("deployed revision conflicts with Railway source revision");
  });
});
