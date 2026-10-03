import type { ServerConfig } from "../src/config.js";

export const testConfig: ServerConfig = {
  nodeEnv: "test",
  buildChannel: "dev",
  tokenAudience: "afterchime-dev",
  host: "127.0.0.1",
  port: 3000,
  logLevel: "silent",
  databaseUrl: "postgresql://afterchime:replace-me@127.0.0.1:54329/afterchime_test",
  accessTokenSecret: "test-access-token-secret-at-least-32-characters",
  refreshTokenPepper: "test-refresh-token-pepper-at-least-32-characters",
  adminApiKeyHash: "",
  corsOrigins: [],
  allowDevAuth: true,
  billingProvider: "fake",
  analyticsRawRetentionDays: 730,
};
