import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAccessToken } from "../../security/tokens.js";
import { ConsentIdempotencyConflictError, ConsentRepository } from "./repository.js";
import { ConsentService } from "./service.js";

const idempotencyHeaders = z.object({
  "idempotency-key": z.string().uuid(),
});

const consentScope = z.enum(["essential_online", "product_analytics", "notification_aggregates"]);

const consentBody = z
  .object({
    granted: z.boolean(),
    scope: consentScope,
    scopeVersion: z
      .string()
      .trim()
      .min(1)
      .max(32)
      .regex(/^[A-Za-z0-9._-]+$/),
  })
  .strict();

const consentState = z.object({
  granted: z.boolean(),
  scope: consentScope,
  scopeVersion: z.string(),
});

const currentConsentResponse = z.object({
  consents: z.array(consentState),
});

const errorResponse = z.object({
  error: z.enum(["idempotency_conflict", "unauthorized"]),
});

export async function registerConsentRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const service = new ConsentService(new ConsentRepository(database));

  app.put(
    "/v1/consents",
    {
      schema: {
        body: consentBody,
        headers: idempotencyHeaders,
        response: {
          200: currentConsentResponse,
          401: errorResponse,
          409: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const claims = authenticatedClaims(request.headers.authorization, config);
      if (!claims) {
        return reply.code(401).send({ error: "unauthorized" });
      }

      const body = consentBody.parse(request.body);
      const headers = idempotencyHeaders.parse(request.headers);
      try {
        const consents = await service.updateConsent({
          ...body,
          ...claims,
          idempotencyKey: headers["idempotency-key"],
        });
        return reply.code(200).send({ consents });
      } catch (error) {
        if (error instanceof ConsentIdempotencyConflictError) {
          return reply.code(409).send({ error: "idempotency_conflict" });
        }
        throw error;
      }
    },
  );
}

function authenticatedClaims(authorization: string | string[] | undefined, config: ServerConfig) {
  if (typeof authorization !== "string" || !authorization.startsWith("Bearer ")) {
    return null;
  }
  const token = authorization.slice("Bearer ".length);
  if (!token) {
    return null;
  }
  try {
    return verifyAccessToken({
      audience: config.tokenAudience,
      secret: config.accessTokenSecret,
      token,
    });
  } catch {
    return null;
  }
}
