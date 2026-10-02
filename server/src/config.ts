import { z } from "zod";

const secret = z.string().min(32);

const configSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  HOST: z.string().default("0.0.0.0"),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"]).default("info"),
  DATABASE_URL: z.string().startsWith("postgresql://"),
  ACCESS_TOKEN_SECRET: secret,
  REFRESH_TOKEN_PEPPER: secret,
  ADMIN_API_KEY_HASH: z.string().default(""),
  CORS_ORIGINS: z.string().default(""),
  ALLOW_DEV_AUTH: z.enum(["true", "false"]).default("false"),
  BILLING_PROVIDER: z.enum(["fake", "google-play"]).default("fake"),
  ANALYTICS_RAW_RETENTION_DAYS: z.coerce.number().int().min(30).max(3650).default(730),
});

export type ServerConfig = Readonly<{
  nodeEnv: "development" | "test" | "production";
  host: string;
  port: number;
  logLevel: "fatal" | "error" | "warn" | "info" | "debug" | "trace" | "silent";
  databaseUrl: string;
  accessTokenSecret: string;
  refreshTokenPepper: string;
  adminApiKeyHash: string;
  corsOrigins: readonly string[];
  allowDevAuth: boolean;
  billingProvider: "fake" | "google-play";
  analyticsRawRetentionDays: number;
}>;

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): ServerConfig {
  const value = configSchema.parse(environment);
  return {
    nodeEnv: value.NODE_ENV,
    host: value.HOST,
    port: value.PORT,
    logLevel: value.LOG_LEVEL,
    databaseUrl: value.DATABASE_URL,
    accessTokenSecret: value.ACCESS_TOKEN_SECRET,
    refreshTokenPepper: value.REFRESH_TOKEN_PEPPER,
    adminApiKeyHash: value.ADMIN_API_KEY_HASH,
    corsOrigins: value.CORS_ORIGINS.split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
    allowDevAuth: value.ALLOW_DEV_AUTH === "true",
    billingProvider: value.BILLING_PROVIDER,
    analyticsRawRetentionDays: value.ANALYTICS_RAW_RETENTION_DAYS,
  };
}
