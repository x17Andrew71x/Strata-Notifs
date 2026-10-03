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
});
