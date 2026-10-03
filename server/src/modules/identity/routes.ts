import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { IdempotencyConflictError, IdentityRepository } from "./repository.js";
import { IdentityService } from "./service.js";

const idempotencyHeaders = z.object({
  "idempotency-key": z.string().uuid(),
});

const registrationBody = z
  .object({
    appVersion: z
      .string()
      .trim()
      .min(1)
      .max(48)
      .regex(/^[A-Za-z0-9.+_-]+$/),
    device: z
      .object({
        androidApiLevel: z.number().int().min(26).max(100),
        deviceClass: z.enum(["phone", "tablet", "foldable"]),
      })
      .strict(),
  })
  .strict();

const refreshBody = z
  .object({
    refreshToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  })
  .strict();

const sessionResponse = z.object({
  accessToken: z.string(),
  expiresInSeconds: z.literal(900),
  installationId: z.string().uuid(),
  refreshToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  tokenType: z.literal("Bearer"),
});

const errorResponse = z.object({
  error: z.enum(["idempotency_conflict", "invalid_refresh_token"]),
});

export async function registerIdentityRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const service = new IdentityService(config, new IdentityRepository(database));

  app.post(
    "/v1/installations",
    {
      config: {
        rateLimit: {
          max: 3,
          timeWindow: "1 minute",
        },
      },
      schema: {
        body: registrationBody,
        headers: idempotencyHeaders,
        response: {
          200: sessionResponse,
          201: sessionResponse,
          409: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const body = registrationBody.parse(request.body);
      const headers = idempotencyHeaders.parse(request.headers);
      try {
        const registration = await service.registerInstallation({
          ...body,
          idempotencyKey: headers["idempotency-key"],
        });
        return reply.code(registration.statusCode).send(registration.result);
      } catch (error) {
        if (error instanceof IdempotencyConflictError) {
          return reply.code(409).send({ error: "idempotency_conflict" });
        }
        throw error;
      }
    },
  );

  app.post(
    "/v1/sessions/refresh",
    {
      schema: {
        body: refreshBody,
        response: {
          200: sessionResponse,
          401: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const body = refreshBody.parse(request.body);
      const session = await service.rotateRefreshToken(body.refreshToken);
      if (!session) {
        return reply.code(401).send({ error: "invalid_refresh_token" });
      }
      return reply.code(200).send(session);
    },
  );

  app.delete(
    "/v1/sessions",
    {
      schema: {
        body: refreshBody,
      },
    },
    async (request, reply) => {
      const body = refreshBody.parse(request.body);
      await service.logout(body.refreshToken);
      return reply.code(204).send();
    },
  );
}
