import { createHash } from "node:crypto";
import type { FastifyRequest } from "fastify";

function digest(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function adminCredentialRateLimitKey(request: FastifyRequest): string {
  const key = request.headers["x-admin-key"];
  return typeof key === "string" && key.length <= 256 ? `admin:${digest(key)}` : `ip:${request.ip}`;
}

export function registrationRateLimitKey(_request: FastifyRequest): string {
  return "registration:global";
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
