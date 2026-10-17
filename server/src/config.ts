import { z } from "zod";
import type {
  DevFeedbackProject,
  DevFeedbackProjectConfig,
} from "./modules/dev-feedback/project.js";

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
  DEV_FEEDBACK_DECREE_ADMIN_KEY_HASH: z.string().default(""),
  DEV_FEEDBACK_DECREE_CLIENT_KEY_HASH: z.string().default(""),
  DEV_FEEDBACK_DECREE_DEPLOYMENT_URL: z.string().default(""),
  DEV_FEEDBACK_PJS_ADMIN_KEY_HASH: z.string().default(""),
  DEV_FEEDBACK_PJS_CLIENT_KEY_HASH: z.string().default(""),
  DEV_FEEDBACK_PJS_DEPLOYMENT_URL: z.string().default(""),
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
  feedbackProjects: Readonly<Record<DevFeedbackProject, DevFeedbackProjectConfig>>;
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
  const feedbackProjects = {
    afterchime: {
      adminKeyHash: value.ADMIN_API_KEY_HASH,
      clientKeyHash: null,
      deploymentUrl: null,
    },
    decree: externalFeedbackProject(
      "decree",
      value.DEV_FEEDBACK_DECREE_ADMIN_KEY_HASH,
      value.DEV_FEEDBACK_DECREE_CLIENT_KEY_HASH,
      value.DEV_FEEDBACK_DECREE_DEPLOYMENT_URL,
    ),
    "please-just-sleep": externalFeedbackProject(
      "please-just-sleep",
      value.DEV_FEEDBACK_PJS_ADMIN_KEY_HASH,
      value.DEV_FEEDBACK_PJS_CLIENT_KEY_HASH,
      value.DEV_FEEDBACK_PJS_DEPLOYMENT_URL,
    ),
  } satisfies Record<DevFeedbackProject, DevFeedbackProjectConfig>;
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
    feedbackProjects,
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

function externalFeedbackProject(
  project: Exclude<DevFeedbackProject, "afterchime">,
  adminKeyHash: string,
  clientKeyHash: string,
  deploymentUrl: string,
): DevFeedbackProjectConfig {
  const values = [adminKeyHash, clientKeyHash, deploymentUrl];
  if (values.every((entry) => entry === "")) {
    return { adminKeyHash: "", clientKeyHash: null, deploymentUrl: null };
  }
  if (
    !/^[0-9a-f]{64}$/.test(adminKeyHash) ||
    !/^[0-9a-f]{64}$/.test(clientKeyHash) ||
    !exactDevelopmentUrl(deploymentUrl)
  ) {
    throw new Error(`development feedback configuration is incomplete for ${project}`);
  }
  return { adminKeyHash, clientKeyHash, deploymentUrl };
}

function exactDevelopmentUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      url.pathname !== ""
    );
  } catch {
    return false;
  }
}
