import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";

const config = loadConfig();
const app = await buildApp({
  config,
  logger: {
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
  },
});

async function stop(signal: NodeJS.Signals): Promise<void> {
  app.log.info({ signal }, "stopping server");
  await app.close();
  process.exit(0);
}

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.once(signal, () => {
    void stop(signal);
  });
}

try {
  await app.listen({ host: config.host, port: config.port });
} catch (error) {
  app.log.fatal(error, "server failed to start");
  process.exit(1);
}
