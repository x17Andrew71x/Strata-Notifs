import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAccessToken } from "../../security/tokens.js";
import { AccountDeletionRepository, AccountDeletionService } from "./deletion.js";

const authorizationResponse = z.object({ error: z.literal("unauthorized") });

export async function registerAccountRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const service = new AccountDeletionService(new AccountDeletionRepository(database));

  app.delete(
    "/v1/account",
    {
      schema: {
        response: { 204: z.void(), 401: authorizationResponse },
      },
    },
    async (request, reply) => {
      const claims = authenticatedClaims(request.headers.authorization, config);
      if (!claims) {
        return reply.code(401).send({ error: "unauthorized" });
      }

      await service.deleteAccount(claims);
      return reply.code(204).send();
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
