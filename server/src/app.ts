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
import { registerHealthRoutes } from "./routes/health.js";

export type BuildAppOptions = Readonly<{
  config: ServerConfig;
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
    bodyLimit: 256 * 1024,
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

  await registerHealthRoutes(app, options.config);
  return app;
}

export async function buildAttestedApp(options: BuildAppOptions): Promise<AttestedApp> {
  const database = createSqlClient(options.config.databaseUrl);
  let app: FastifyInstance | undefined;

  try {
    const runtimeAttestation = await attestRuntimeRole(database);
    app = await buildApp(options);
    return { app, database, runtimeAttestation };
  } catch (error) {
    await app?.close();
    await database.end({ timeout: 5 });
    throw error;
  }
}
