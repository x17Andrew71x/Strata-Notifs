import { loadConfig } from "../config.js";
import { createSqlClient } from "../db/client.js";
import { attestRuntimeRole } from "../db/runtime-attestation.js";
import { runAnalyticsDaily } from "./analytics-daily.js";
import { runRetention } from "./retention.js";

async function main(): Promise<void> {
  const config = loadConfig();
  const database = createSqlClient(config.databaseUrl);

  try {
    await attestRuntimeRole(database);
    const analyticsDaily = await runAnalyticsDaily(database);
    if (analyticsDaily.status !== "succeeded") {
      process.stdout.write(`${JSON.stringify({ analyticsDaily, retention: null })}\n`);
      process.exitCode = 1;
      return;
    }

    const retention = await runRetention(database);
    process.stdout.write(`${JSON.stringify({ analyticsDaily, retention })}\n`);
    if (retention.status !== "succeeded") {
      process.exitCode = 1;
    }
  } finally {
    await database.end({ timeout: 5 });
  }
}

await main();
