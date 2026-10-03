import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import Fastify, { type FastifyInstance, type FastifyServerOptions } from "fastify";
import {
  serializerCompiler,
  validatorCompiler,
  type ZodTypeProvider,
} from "fastify-type-provider-zod";
import type { ServerConfig } from "./config.js";
import { createSqlClient, type DatabaseClient } from "./db/client.js";
import { attestRuntimeRole, type RuntimeDatabaseAttestation } from "./db/runtime-attestation.js";
import { registerAnalyticsRoutes } from "./modules/analytics/routes.js";
import { registerConsentRoutes } from "./modules/consent/routes.js";
import { registerIdentityRoutes } from "./modules/identity/routes.js";
import { registerNotificationAggregateRoutes } from "./modules/notifications/routes.js";
import { registerHealthRoutes } from "./routes/health.js";

export type BuildAppOptions = Readonly<{
  config: ServerConfig;
  database?: DatabaseClient;
  logger?: FastifyServerOptions["logger"];
}>;

export type AttestedApp = Readonly<{
  app: FastifyInstance;
  database: DatabaseClient;
  runtimeAttestation: RuntimeDatabaseAttestation;
}>;

export async function buildApp(options: BuildAppOptions): Promise<FastifyInstance> {
  const app = Fastify({
    logger: options.logger ?? false,
    trustProxy: true,
    bodyLimit: 128 * 1024,
    requestIdHeader: "x-request-id",
  }).withTypeProvider<ZodTypeProvider>();

  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);

  await app.register(helmet, {
    contentSecurityPolicy: false,
  });
  await app.register(rateLimit, {
    max: 120,
    timeWindow: "1 minute",
  });
  await app.register(cors, {
    origin:
      options.config.corsOrigins.length === 0
        ? false
        : (origin, callback) => {
            if (!origin || options.config.corsOrigins.includes(origin)) {
              callback(null, true);
              return;
            }
            callback(new Error("Origin is not allowed"), false);
          },
    credentials: false,
  });

  app.setErrorHandler((error, _request, reply) => {
    const statusCode = isClientError(error) ? error.statusCode : null;
    if (statusCode) {
      return reply.code(statusCode).send({
        error: statusCode === 429 ? "rate_limited" : "request_invalid",
      });
    }
    return reply.code(500).send({ error: "internal_server_error" });
  });

  await registerHealthRoutes(app, options.config);
  if (options.database) {
    await registerIdentityRoutes(app, options.config, options.database);
    await registerConsentRoutes(app, options.config, options.database);
    await registerAnalyticsRoutes(app, options.config, options.database);
    await registerNotificationAggregateRoutes(app, options.config, options.database);
  }
  return app;
}

function isClientError(error: unknown): error is Readonly<{ statusCode: number }> {
  if (
    error === null ||
    typeof error !== "object" ||
    Array.isArray(error) ||
    !("statusCode" in error)
  ) {
    return false;
  }
  const statusCode = (error as Readonly<{ statusCode?: unknown }>).statusCode;
  return typeof statusCode === "number" && statusCode >= 400 && statusCode < 500;
}

export async function buildAttestedApp(options: BuildAppOptions): Promise<AttestedApp> {
  const database = createSqlClient(options.config.databaseUrl);
  let app: FastifyInstance | undefined;

  try {
    const runtimeAttestation = await attestRuntimeRole(database);
    app = await buildApp({ ...options, database });
    return { app, database, runtimeAttestation };
  } catch (error) {
    await app?.close();
    await database.end({ timeout: 5 });
    throw error;
  }
}
