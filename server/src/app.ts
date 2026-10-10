import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import cors from "@fastify/cors";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import Fastify, {
  type FastifyInstance,
  type FastifyReply,
  type FastifyServerOptions,
} from "fastify";
import {
  serializerCompiler,
  validatorCompiler,
  type ZodTypeProvider,
} from "fastify-type-provider-zod";
import type { ServerConfig } from "./config.js";
import { createSqlClient, type DatabaseClient } from "./db/client.js";
import { attestRuntimeRole, type RuntimeDatabaseAttestation } from "./db/runtime-attestation.js";
import { registerAccountRoutes } from "./modules/account/routes.js";
import { registerAnalyticsRoutes } from "./modules/analytics/routes.js";
import { registerConsentRoutes } from "./modules/consent/routes.js";
import { registerIdentityRoutes } from "./modules/identity/routes.js";
import { registerNotificationAggregateRoutes } from "./modules/notifications/routes.js";
import { registerHealthRoutes } from "./routes/health.js";

export type BuildAppOptions = Readonly<{
  config: ServerConfig;
  database?: DatabaseClient;
  logger?: FastifyServerOptions["logger"];
  webRoot?: string;
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

  await registerShellRoutes(
    app,
    options.webRoot ??
      process.env["AFTERCHIME_WEB_ROOT"] ??
      path.resolve(process.cwd(), "../web/dist"),
  );
  await registerHealthRoutes(app, options.config);
  if (options.database) {
    await registerIdentityRoutes(app, options.config, options.database);
    await registerAccountRoutes(app, options.config, options.database);
    await registerConsentRoutes(app, options.config, options.database);
    await registerAnalyticsRoutes(app, options.config, options.database);
    await registerNotificationAggregateRoutes(app, options.config, options.database);
  }
  return app;
}

function setShellHeaders(reply: FastifyReply, cache: string): void {
  reply.header(
    "Content-Security-Policy",
    "default-src 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
  );
  reply.header("X-Content-Type-Options", "nosniff");
  reply.header("Referrer-Policy", "no-referrer");
  reply.header("Cache-Control", cache);
}

async function registerShellRoutes(app: FastifyInstance, webRoot: string): Promise<void> {
  app.get("/shell/health", async (_request, reply) => {
    setShellHeaders(reply, "no-store");
    return reply.send({ status: "ok", shell: true });
  });
  app.get("/shell/metadata", async (_request, reply) => {
    setShellHeaders(reply, "no-cache, must-revalidate");
    try {
      const entry = await readFile(path.join(webRoot, "index.html"));
      const revision = createHash("sha256").update(entry).digest("hex");
      return reply.send({ shellVersion: 2, bridgeVersion: 2, revision, entry: "/" });
    } catch {
      return reply.code(503).send({ error: "shell_unavailable" });
    }
  });
  app.get("/service-worker.js", async (_request, reply) => {
    setShellHeaders(reply, "no-cache, must-revalidate");
    try {
      const script = await readFile(path.join(webRoot, "service-worker.js"));
      return reply.type("text/javascript; charset=utf-8").send(script);
    } catch {
      return reply.code(404).type("text/plain; charset=utf-8").send("Not found");
    }
  });
  app.get("/", async (_request, reply) => {
    setShellHeaders(reply, "no-cache, must-revalidate");
    try {
      const html = await readFile(path.join(webRoot, "index.html"));
      return reply.type("text/html; charset=utf-8").send(html);
    } catch {
      return reply
        .code(503)
        .type("text/plain; charset=utf-8")
        .send("Afterchime web shell is not built. Use the bundled app shell or build web assets.");
    }
  });
  app.get<{ Params: { asset: string } }>("/assets/:asset", async (request, reply) => {
    const filename = request.params.asset;
    if (!/^[A-Za-z0-9_-]{1,96}-[A-Za-z0-9_-]{8,64}\.(?:js|css)$/.test(filename)) {
      setShellHeaders(reply, "no-store");
      return reply.code(404).send({ error: "not_found" });
    }
    try {
      const data = await readFile(path.join(webRoot, "assets", filename));
      const contentType = filename.endsWith(".js")
        ? "text/javascript; charset=utf-8"
        : "text/css; charset=utf-8";
      setShellHeaders(reply, "public, max-age=31536000, immutable");
      return reply.type(contentType).send(data);
    } catch {
      setShellHeaders(reply, "no-store");
      return reply.code(404).send({ error: "not_found" });
    }
  });
  app.get<{ Params: { asset: string } }>("/worlds/:asset", async (request, reply) => {
    const filename = request.params.asset;
    if (!/^[a-z0-9-]{1,128}-[a-f0-9]{12}\.(?:jpg|webp)$/.test(filename)) {
      setShellHeaders(reply, "no-store");
      return reply.code(404).send({ error: "not_found" });
    }
    try {
      const data = await readFile(path.join(webRoot, "worlds", filename));
      const contentType = filename.endsWith(".webp") ? "image/webp" : "image/jpeg";
      setShellHeaders(reply, "public, max-age=31536000, immutable");
      return reply.type(contentType).send(data);
    } catch {
      setShellHeaders(reply, "no-store");
      return reply.code(404).send({ error: "not_found" });
    }
  });
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
