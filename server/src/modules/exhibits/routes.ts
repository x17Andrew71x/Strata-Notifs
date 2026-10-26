import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAccessToken } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";
import {
  ExhibitsConflictError,
  ExhibitsInputError,
  ExhibitsRepository,
  ExhibitsRequestError,
  type ExhibitsState,
} from "./repository.js";

const localDateQuery = z.object({ local_date: z.iso.date() }).strict();
const contributionHeaders = z.object({
  authorization: z.string(),
  "idempotency-key": z.string().uuid(),
});
const contributionParams = z.object({ exhibitId: z.string().regex(/^[a-z0-9-]{1,80}$/) });
const contributionBody = z
  .object({
    local_date: z.iso.date(),
    units: z.number().int().min(1).max(100_000),
  })
  .strict();

const exhibit = z.object({
  closed: z.boolean(),
  communityComplete: z.boolean(),
  daysRemaining: z.number().int().min(0).max(3_650).nullable(),
  id: z.string(),
  imagePath: z.string(),
  personalContribution: z.number().int().nonnegative(),
  personalMinimum: z.number().int().positive(),
  progressBasisPoints: z.number().int().min(0).max(10_000),
  qualified: z.boolean(),
  seasonLabel: z.string(),
  title: z.string(),
  unlocked: z.boolean(),
});
const pastExhibit = z.object({
  id: z.string(),
  imagePath: z.string(),
  lockedReason: z.string().nullable(),
  seasonLabel: z.string(),
  title: z.string(),
  unlocked: z.boolean(),
});
const stateResponse = z.object({
  archive: exhibit.nullable(),
  dailyContributionCap: z.number().int().nonnegative(),
  featured: exhibit.nullable(),
  past: z.array(pastExhibit).max(20),
  status: z.literal("ready"),
  todayContributedUnits: z.number().int().nonnegative(),
  version: z.number().int().nonnegative(),
});
const contributionResponse = z.object({
  state: stateResponse,
  status: z.enum(["accepted", "duplicate"]),
});
const errorResponse = z.object({
  error: z.enum([
    "consent_inactive",
    "daily_cap_exceeded",
    "exhibit_closed",
    "exhibit_unavailable",
    "request_invalid",
    "unauthorized",
  ]),
});

export async function registerExhibitsRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const repository = new ExhibitsRepository(database);

  app.get(
    "/v1/exhibits/current",
    {
      schema: {
        querystring: localDateQuery,
        response: {
          200: stateResponse,
          400: errorResponse,
          401: errorResponse,
          403: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const claims = authenticatedClaims(request.headers.authorization, config);
      if (!claims) return reply.code(401).send({ error: "unauthorized" });
      const query = localDateQuery.parse(request.query);
      try {
        const state = await repository.state({
          ...claims,
          buildChannel: config.buildChannel,
          localDate: query.local_date,
          now: new Date(),
        });
        return reply.code(200).send(state satisfies ExhibitsState);
      } catch (error) {
        if (error instanceof ConsentInactiveError) {
          return reply.code(403).send({ error: "consent_inactive" });
        }
        if (error instanceof ExhibitsInputError) {
          return reply.code(400).send({ error: "request_invalid" });
        }
        if (error instanceof ExhibitsRequestError) {
          return reply.code(401).send({ error: "unauthorized" });
        }
        throw error;
      }
    },
  );

  app.post(
    "/v1/exhibits/:exhibitId/contributions",
    {
      config: { rateLimit: { max: 12, timeWindow: "1 minute" } },
      schema: {
        body: contributionBody,
        headers: contributionHeaders,
        params: contributionParams,
        response: {
          200: contributionResponse,
          201: contributionResponse,
          400: errorResponse,
          401: errorResponse,
          403: errorResponse,
          409: errorResponse,
        },
      },
    },
    async (request, reply) => {
      const headers = contributionHeaders.parse(request.headers);
      const claims = authenticatedClaims(headers.authorization, config);
      if (!claims) return reply.code(401).send({ error: "unauthorized" });
      const body = contributionBody.parse(request.body);
      const params = contributionParams.parse(request.params);
      try {
        const result = await repository.contribute({
          ...claims,
          buildChannel: config.buildChannel,
          exhibitId: params.exhibitId,
          idempotencyKey: headers["idempotency-key"],
          localDate: body.local_date,
          now: new Date(),
          units: body.units,
        });
        return reply.code(result.status === "accepted" ? 201 : 200).send(result);
      } catch (error) {
        if (error instanceof ConsentInactiveError) {
          return reply.code(403).send({ error: "consent_inactive" });
        }
        if (error instanceof ExhibitsConflictError) {
          return reply.code(409).send({ error: error.code });
        }
        if (error instanceof ExhibitsInputError) {
          return reply.code(400).send({ error: "request_invalid" });
        }
        if (error instanceof ExhibitsRequestError) {
          return reply.code(401).send({ error: "unauthorized" });
        }
        throw error;
      }
    },
  );
}

function authenticatedClaims(authorization: string | string[] | undefined, config: ServerConfig) {
  if (typeof authorization !== "string" || !authorization.startsWith("Bearer ")) return null;
  const token = authorization.slice("Bearer ".length);
  if (!token) return null;
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
