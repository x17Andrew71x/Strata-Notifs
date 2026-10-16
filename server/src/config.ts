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
  DEV_FEEDBACK_ENABLED: z.enum(["true", "false"]).default("false"),
  DEPLOYED_REVISION: z
    .string()
    .regex(/^[0-9a-f]{40}$/)
    .optional(),
  RAILWAY_GIT_COMMIT_SHA: z
    .string()
    .regex(/^[0-9a-f]{40}$/)
    .optional(),
  CORS_ORIGINS: z.string().default(""),
  ALLOW_DEV_AUTH: z.enum(["true", "false"]).default("false"),
  BILLING_PROVIDER: z.enum(["fake", "google-play"]).default("fake"),
  ANALYTICS_RAW_RETENTION_DAYS: z.coerce.number().int().min(30).max(3650).default(730),
});

export type ServerConfig = Readonly<{
  nodeEnv: "development" | "test" | "production";
  buildChannel: "dev" | "prod";
  tokenAudience: "afterchime-dev" | "afterchime-prod";
  host: string;
  port: number;
  logLevel: "fatal" | "error" | "warn" | "info" | "debug" | "trace" | "silent";
  databaseUrl: string;
  accessTokenSecret: string;
  refreshTokenPepper: string;
  adminApiKeyHash: string;
  developmentModuleEnabled: boolean;
  deployedRevision: string;
  corsOrigins: readonly string[];
  allowDevAuth: boolean;
  billingProvider: "fake" | "google-play";
  analyticsRawRetentionDays: number;
}>;

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): ServerConfig {
  const value = configSchema.parse(environment);
  if (value.DEV_FEEDBACK_ENABLED === "true" && !/^[0-9a-f]{64}$/.test(value.ADMIN_API_KEY_HASH)) {
    throw new Error("development feedback requires an admin key digest");
  }
  if (
    value.DEPLOYED_REVISION &&
    value.RAILWAY_GIT_COMMIT_SHA &&
    value.DEPLOYED_REVISION !== value.RAILWAY_GIT_COMMIT_SHA
  ) {
    throw new Error("deployed revision conflicts with Railway source revision");
  }
  const deployedRevision =
    value.RAILWAY_GIT_COMMIT_SHA ??
    value.DEPLOYED_REVISION ??
    "0000000000000000000000000000000000000000";
  if (value.DEV_FEEDBACK_ENABLED === "true" && !value.RAILWAY_GIT_COMMIT_SHA) {
    throw new Error("development feedback requires Railway source revision proof");
  }
  if (
    value.DEV_FEEDBACK_ENABLED === "true" &&
    deployedRevision === "0000000000000000000000000000000000000000"
  ) {
    throw new Error("development feedback requires a deployed revision");
  }
  const buildChannel = value.NODE_ENV === "production" ? "prod" : "dev";
  return {
    nodeEnv: value.NODE_ENV,
    buildChannel,
    tokenAudience: buildChannel === "prod" ? "afterchime-prod" : "afterchime-dev",
    host: value.HOST,
    port: value.PORT,
    logLevel: value.LOG_LEVEL,
    databaseUrl: value.DATABASE_URL,
    accessTokenSecret: value.ACCESS_TOKEN_SECRET,
    refreshTokenPepper: value.REFRESH_TOKEN_PEPPER,
    adminApiKeyHash: value.ADMIN_API_KEY_HASH,
    developmentModuleEnabled: value.DEV_FEEDBACK_ENABLED === "true",
    deployedRevision,
    corsOrigins: value.CORS_ORIGINS.split(",")
      .map((origin) => origin.trim())
      .filter(Boolean),
    allowDevAuth: value.ALLOW_DEV_AUTH === "true",
    billingProvider: value.BILLING_PROVIDER,
    analyticsRawRetentionDays: value.ANALYTICS_RAW_RETENTION_DAYS,
  };
}
