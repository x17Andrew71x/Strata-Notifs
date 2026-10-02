import { afterEach, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { testConfig } from "./test-config.js";

const apps: Awaited<ReturnType<typeof buildApp>>[] = [];

afterEach(async () => {
  await Promise.all(apps.splice(0).map((app) => app.close()));
});

describe("GET /health/live", () => {
  it("returns a bounded live response and security headers without probing dependencies", async () => {
    const app = await buildApp({ config: testConfig });
    apps.push(app);

    const response = await app.inject({ method: "GET", url: "/health/live" });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: "ok",
      service: "stratawake-api",
      version: "0.1.0",
      environment: "test",
    });
    expect(response.headers["x-content-type-options"]).toBe("nosniff");
    expect(response.headers).not.toHaveProperty("x-powered-by");
  });
});
