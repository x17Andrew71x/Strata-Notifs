import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAccessToken } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";
import { analyticsBatch } from "./registry.js";
import { AnalyticsRepository } from "./repository.js";
import { AnalyticsService } from "./service.js";

const result = z.object({
  eventId: z.string().uuid(),
  status: z.enum(["accepted", "duplicate", "rejected"]),
});

const batchResponse = z.object({
  results: z.array(result),
});

const errorResponse = z.object({
  error: z.enum(["consent_inactive", "unauthorized"]),
});

export async function registerAnalyticsRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const service = new AnalyticsService(new AnalyticsRepository(database));

  app.post(
    "/v1/analytics/events:batch",
    {
      schema: {
        body: analyticsBatch,
        response: {
          202: batchResponse,
          401: errorResponse,
          403: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const claims = authenticatedClaims(request.headers.authorization, config);
      if (!claims) {
        return reply.code(401).send({ error: "unauthorized" });
      }

      const body = analyticsBatch.parse(request.body);
      try {
        const results = await service.ingestBatch({
          ...claims,
          buildChannel: config.buildChannel,
          events: body.events,
        });
        return reply.code(202).send({ results });
      } catch (error) {
        if (error instanceof ConsentInactiveError) {
          return reply.code(403).send({ error: "consent_inactive" });
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
