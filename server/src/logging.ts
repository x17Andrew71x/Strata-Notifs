import type { ServerConfig } from "./config.js";

export function loggerRedaction(developmentModuleEnabled = false): {
  paths: string[];
  censor: string;
} {
  const paths = [
    "req.headers.authorization",
    "req.headers.cookie",
    "req.headers.x-admin-key",
    "req.headers.x-afterchime-admin-key",
    "req.headers.idempotency-key",
    "req.body.refreshToken",
    "res.body.accessToken",
    "res.body.refreshToken",
    "*.accessToken",
    "*.refreshToken",
  ];
  if (developmentModuleEnabled) {
    paths.push(
      `req.headers.x-dev-${"feedback-admin-key"}`,
      `req.headers.x-dev-${"feedback-client-key"}`,
    );
  }
  return { paths, censor: "[REDACTED]" };
}

export function serverLogger(config: ServerConfig) {
  return {
    level: config.logLevel,
    redact: loggerRedaction(config.developmentModuleEnabled),
  };
}
