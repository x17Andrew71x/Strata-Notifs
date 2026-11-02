import { createHash } from "node:crypto";
import type { FastifyRequest } from "fastify";

const feedbackProjects = new Set(["afterchime", "decree", "please-just-sleep"]);

function digest(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

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
