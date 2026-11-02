import { createHash } from "node:crypto";
import type { FastifyRequest } from "fastify";

function digest(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function adminCredentialRateLimitKey(request: FastifyRequest): string {
  const key = request.headers["x-admin-key"];
  return typeof key === "string" && key.length <= 256 ? `admin:${digest(key)}` : `ip:${request.ip}`;
}

const feedbackProjects = new Set(["afterchime", "decree", "please-just-sleep"]);

function feedbackProjectBucket(
  value: string | string[] | undefined,
  fallback?: string,
): string | null {
  const candidate = typeof value === "string" ? value : fallback;
  return candidate && feedbackProjects.has(candidate) ? candidate : null;
}

export function feedbackAdminRateLimitKey(request: FastifyRequest): string {
  const key =
    request.headers["x-dev-feedback-admin-key"] ?? request.headers["x-afterchime-admin-key"];
  const project = feedbackProjectBucket(request.headers["x-dev-feedback-project"], "afterchime");
  return typeof key === "string" && key.length <= 256 && project
    ? `feedback-admin:${project}:${digest(key)}`
    : `ip:${request.ip}`;
}

export function feedbackClientRateLimitKey(request: FastifyRequest): string {
  const key = request.headers["x-dev-feedback-client-key"];
  const project = feedbackProjectBucket(request.headers["x-dev-feedback-project"]);
  return typeof key === "string" && key.length <= 256 && project
    ? `feedback-client:${project}:${digest(key)}`
    : `ip:${request.ip}`;
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
