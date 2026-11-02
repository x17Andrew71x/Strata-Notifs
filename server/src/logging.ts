import type { ServerConfig } from "./config.js";

export const loggerRedaction: { paths: string[]; censor: string } = {
  paths: [
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
  ],
  censor: "[REDACTED]",
};

export function serverLogger(config: ServerConfig) {
  return {
    level: config.logLevel,
    redact: loggerRedaction,
  };
}
