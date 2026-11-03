import { createHash } from "node:crypto";
import type { FastifyInstance, FastifyRequest } from "fastify";

type RateLimitKey = (request: FastifyRequest) => string;

export type LayeredRateLimitOptions = Readonly<{
  circuitMax: number;
  clientMax: number;
  credentialKey: RateLimitKey;
  credentialMax: number;
  group: string;
}>;

class LayeredRateLimitExceededError extends Error {
  public readonly statusCode = 429;

  public constructor() {
    super("rate_limited");
    this.name = "LayeredRateLimitExceededError";
  }
}

function digest(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function adminCredentialRateLimitKey(request: FastifyRequest): string {
  const key = request.headers["x-admin-key"];
  return typeof key === "string" && key.length <= 256 ? `admin:${digest(key)}` : `ip:${request.ip}`;
}

export function registrationRateLimitKey(_request: FastifyRequest): string {
  const key = _request.headers["idempotency-key"];
  return typeof key === "string" && key.length <= 128
    ? `registration:${digest(key)}`
    : `ip:${_request.ip}`;
}

export function authorizationRateLimitKey(request: FastifyRequest): string {
  const authorization = request.headers.authorization;
  return typeof authorization === "string" && authorization.length <= 4_096
    ? `auth:${digest(authorization)}`
    : `ip:${request.ip}`;
}

export function refreshCredentialRateLimitKey(request: FastifyRequest): string {
  const body = request.body;
  if (body && typeof body === "object" && !Array.isArray(body) && "refreshToken" in body) {
    const refreshToken = (body as Readonly<{ refreshToken?: unknown }>).refreshToken;
    if (typeof refreshToken === "string" && refreshToken.length <= 256) {
      return `refresh:${digest(refreshToken)}`;
    }
  }
  return `ip:${request.ip}`;
}

export function layeredRateLimitHandlers(app: FastifyInstance, options: LayeredRateLimitOptions) {
  const limiters = [
    app.createRateLimit({
      keyGenerator: () => `circuit:${options.group}`,
      max: options.circuitMax,
      timeWindow: "1 minute",
    }),
    app.createRateLimit({
      keyGenerator: (request) => `client:${options.group}:${request.ip}`,
      max: options.clientMax,
      timeWindow: "1 minute",
    }),
    app.createRateLimit({
      keyGenerator: options.credentialKey,
      max: options.credentialMax,
      timeWindow: "1 minute",
    }),
  ];
  return async (request: FastifyRequest): Promise<void> => {
    for (const limit of limiters) {
      const result = await limit(request);
      if (!result.isAllowed && result.isExceeded) {
        throw new LayeredRateLimitExceededError();
      }
    }
  };
}

export function routeRateLimitHandlers(
  app: FastifyInstance,
  group: string,
  credentialKey: RateLimitKey,
  credentialMax: number,
) {
  return layeredRateLimitHandlers(app, {
    circuitMax: Math.max(60, credentialMax * 10),
    clientMax: Math.max(30, credentialMax * 4),
    credentialKey,
    credentialMax,
    group,
  });
}
