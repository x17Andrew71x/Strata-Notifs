import { loadConfig } from "../config.js";
import { createSqlClient } from "../db/client.js";
import { attestRuntimeRole } from "../db/runtime-attestation.js";
import { runAnalyticsDaily } from "./analytics-daily.js";

async function main(): Promise<void> {
  const config = loadConfig();
  const database = createSqlClient(config.databaseUrl);

  try {
    await attestRuntimeRole(database);
    const result = await runAnalyticsDaily(database);
    process.stdout.write(`${JSON.stringify(result)}\n`);
    if (result.status !== "succeeded") {
      process.exitCode = 1;
    }
  } finally {
    await database.end({ timeout: 5 });
  }
}

await main();
