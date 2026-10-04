import { buildAttestedApp } from "./app.js";
import { loadConfig } from "./config.js";
import { startupFailureCode } from "./startup-failure.js";

const config = loadConfig();
const logger = {
  level: config.logLevel,
  redact: {
    paths: [
      "req.headers.authorization",
      "req.headers.cookie",
      "req.headers.x-admin-key",
      "refreshToken",
      "accessToken",
    ],
    censor: "[REDACTED]",
  },
};

let started: Awaited<ReturnType<typeof buildAttestedApp>> | undefined;

async function stop(signal: NodeJS.Signals): Promise<void> {
  if (!started) {
    process.exit(0);
  }

  started.app.log.info({ signal }, "stopping server");
  await started.app.close();
  await started.database.end({ timeout: 5 });
  process.exit(0);
}

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.once(signal, () => {
    void stop(signal);
  });
}

try {
  started = await buildAttestedApp({ config, logger });
  started.app.log.info(
    {
      migrationHead: started.runtimeAttestation.migrationHead,
      postgresVersion: started.runtimeAttestation.postgresVersion,
      role: started.runtimeAttestation.role,
    },
    "database runtime role attested",
  );
  await started.app.listen({ host: config.host, port: config.port });
} catch (error) {
  if (started) {
    await started.app.close();
    await started.database.end({ timeout: 5 });
  }
  process.stderr.write(`server failed to start: ${startupFailureCode(error)}\n`);
  process.exit(1);
}
