import { Writable } from "node:stream";
import pino from "pino";
import { describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { loggerRedaction } from "../../src/logging.js";
import { isPlausibleAggregateDate } from "../../src/modules/notifications/routes.js";
import {
  authorizationRateLimitKey,
  layeredRateLimitHandlers,
  registrationRateLimitKey,
  routeRateLimitHandlers,
} from "../../src/security/rate-limit.js";
import { testConfig } from "../test-config.js";

describe("API security hardening", () => {
  it("trusts only the configured nearest proxy hop", async () => {
    const app = await buildApp({ config: { ...testConfig, trustedProxyHops: 1 } });
    app.get("/test/client-ip", async (request) => ({ ip: request.ip, ips: request.ips }));

    const response = await app.inject({
      headers: { "x-forwarded-for": "198.51.100.77, 203.0.113.8" },
      method: "GET",
      remoteAddress: "10.0.0.5",
      url: "/test/client-ip",
    });

    expect(response.json()).toEqual({ ip: "203.0.113.8", ips: ["10.0.0.5", "203.0.113.8"] });
    await app.close();
  });

  it("keys authenticated throttles to the credential rather than attacker-selected forwarding data", async () => {
    const app = await buildApp({ config: { ...testConfig, trustedProxyHops: 1 } });
    app.get(
      "/test/limited",
      {
        config: {
          rateLimit: {
            keyGenerator: authorizationRateLimitKey,
            max: 1,
            timeWindow: "1 minute",
          },
        },
      },
      async () => ({ ok: true }),
    );
    const authorization = "opaque-session-key";
    const first = await app.inject({
      headers: { authorization, "x-forwarded-for": "198.51.100.1, 203.0.113.8" },
      method: "GET",
      remoteAddress: "10.0.0.5",
      url: "/test/limited",
    });
    const second = await app.inject({
      headers: { authorization, "x-forwarded-for": "198.51.100.99, 203.0.113.8" },
      method: "GET",
      remoteAddress: "10.0.0.5",
      url: "/test/limited",
    });

    expect(first.statusCode).toBe(200);
    expect(second.statusCode).toBe(429);
    await app.close();
  });

  it("limits rotating invalid credentials at the pre-authentication client boundary", async () => {
    const app = await buildApp({ config: testConfig });
    app.get(
      "/test/layered-auth",
      {
        preHandler: layeredRateLimitHandlers(app, {
          circuitMax: 10,
          clientMax: 1,
          credentialKey: authorizationRateLimitKey,
          credentialMax: 10,
          group: "test-layered-auth",
        }),
      },
      async () => ({ ok: true }),
    );

    const first = await app.inject({
      headers: { authorization: "Bearer attacker-a" },
      method: "GET",
      remoteAddress: "127.0.0.40",
      url: "/test/layered-auth",
    });
    const rotated = await app.inject({
      headers: { authorization: "Bearer attacker-b" },
      method: "GET",
      remoteAddress: "127.0.0.40",
      url: "/test/layered-auth",
    });

    expect(first.statusCode).toBe(200);
    expect(rotated.statusCode).toBe(429);
    await app.close();
  });

  it("bounds rotating invalid credentials across changing proxy peers with an aggregate circuit", async () => {
    const app = await buildApp({ config: testConfig });
    app.get(
      "/test/aggregate-auth-circuit",
      {
        preHandler: routeRateLimitHandlers(
          app,
          "test-aggregate-auth-circuit",
          authorizationRateLimitKey,
          5,
        ),
      },
      async () => ({ ok: true }),
    );

    const statuses: number[] = [];
    for (let index = 0; index < 61; index += 1) {
      const response = await app.inject({
        headers: { authorization: `Bearer attacker-${index}` },
        method: "GET",
        remoteAddress: `127.1.${Math.floor(index / 250)}.${(index % 250) + 1}`,
        url: "/test/aggregate-auth-circuit",
      });
      statuses.push(response.statusCode);
    }

    expect(statuses.slice(0, 60)).toEqual(Array(60).fill(200));
    expect(statuses[60]).toBe(429);
    await app.close();
  });

  it("keeps a distinct registration client available after one idempotency key is exhausted", async () => {
    const app = await buildApp({ config: testConfig });
    app.get(
      "/test/layered-registration",
      {
        preHandler: layeredRateLimitHandlers(app, {
          circuitMax: 100,
          clientMax: 10,
          credentialKey: registrationRateLimitKey,
          credentialMax: 2,
          group: "test-layered-registration",
        }),
      },
      async () => ({ ok: true }),
    );
    const attackerKey = "11111111-1111-4111-8111-111111111111";
    const victimKey = "22222222-2222-4222-8222-222222222222";
    const request = (idempotencyKey: string, remoteAddress: string) =>
      app.inject({
        headers: { "idempotency-key": idempotencyKey },
        method: "GET",
        remoteAddress,
        url: "/test/layered-registration",
      });

    expect((await request(attackerKey, "127.0.0.41")).statusCode).toBe(200);
    expect((await request(attackerKey, "127.0.0.42")).statusCode).toBe(200);
    expect((await request(attackerKey, "127.0.0.43")).statusCode).toBe(429);
    expect((await request(victimKey, "127.0.0.44")).statusCode).toBe(200);
    await app.close();
  });

  it("marks every API response private and non-cacheable", async () => {
    const app = await buildApp({ config: testConfig });
    const response = await app.inject({ method: "GET", url: "/v1/not-a-route" });

    expect(response.headers["cache-control"]).toBe("private, no-store");
    expect(response.headers.pragma).toBe("no-cache");
    await app.close();
  });

  it("redacts every credential-bearing request and response field in serialized logs", () => {
    const redaction = loggerRedaction(true);
    expect(redaction.paths).toEqual(
      expect.arrayContaining([
        "req.headers.authorization",
        "req.headers.cookie",
        "req.headers.x-admin-key",
        "req.headers.x-afterchime-admin-key",
        "req.headers.x-dev-feedback-admin-key",
        "req.headers.x-dev-feedback-client-key",
        "req.headers.idempotency-key",
        "req.body.refreshToken",
        "res.body.accessToken",
        "res.body.refreshToken",
        "*.accessToken",
        "*.refreshToken",
      ]),
    );

    let output = "";
    const destination = new Writable({
      write(chunk, _encoding, callback) {
        output += chunk.toString();
        callback();
      },
    });
    const logger = pino({ redact: redaction }, destination);
    const secret = "must-not-appear";
    logger.info({
      payload: { accessToken: secret, refreshToken: secret },
      req: {
        body: { refreshToken: secret },
        headers: {
          authorization: secret,
          cookie: secret,
          "idempotency-key": secret,
          "x-admin-key": secret,
          "x-afterchime-admin-key": secret,
          "x-dev-feedback-admin-key": secret,
          "x-dev-feedback-client-key": secret,
        },
      },
      res: { body: { accessToken: secret, refreshToken: secret } },
    });

    expect(output).not.toContain(secret);
    expect(output.match(/\[REDACTED\]/g)).toHaveLength(12);
  });

  it("accepts aggregate dates only inside the bounded local-day window", () => {
    const now = new Date("2026-11-02T12:00:00.000Z");
    expect(isPlausibleAggregateDate("2026-11-01", now)).toBe(true);
    expect(isPlausibleAggregateDate("2026-11-02", now)).toBe(true);
    expect(isPlausibleAggregateDate("2026-11-03", now)).toBe(true);
    expect(isPlausibleAggregateDate("2026-10-31", now)).toBe(false);
    expect(isPlausibleAggregateDate("2027-01-01", now)).toBe(false);
  });
});
