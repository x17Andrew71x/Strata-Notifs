import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAccessToken } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";
import {
  NotificationAggregateRepository,
  NotificationAggregateRevisionConflictError,
} from "./repository.js";
import { NotificationAggregateService } from "./service.js";

const count = z.number().int().min(0).max(100_000);
const categoryCounts = z
  .object({
    alarm: count,
    call: count,
    email: count,
    event: count,
    message: count,
    navigation: count,
    other: count,
    progress: count,
    reminder: count,
    social: count,
    transport: count,
    workout: count,
  })
  .partial()
  .strict();

const hourlyBucket = z
  .object({
    count,
    hour: z.number().int().min(0).max(23),
  })
  .strict();

const dailyNotificationAggregate = z
  .object({
    category_counts: categoryCounts,
    consent_scope_version: z.string().trim().min(1).max(32),
    eligible_count: count,
    game_pressure: z.number().int().min(0).max(100),
    hourly_buckets: z.array(hourlyBucket).max(24),
    local_date: z.iso.date(),
    observation_completeness: z.number().int().min(0).max(100),
    revision: z.number().int().min(1).max(2_147_483_647),
    rules_version: z.number().int().min(1).max(2_147_483_647),
    timezone_offset_minutes: z.number().int().min(-840).max(840),
  })
  .strict()
  .superRefine((aggregate, context) => {
    const categoryTotal = Object.values(aggregate.category_counts).reduce<number>(
      (total, value) => total + (value ?? 0),
      0,
    );
    if (categoryTotal !== aggregate.eligible_count) {
      context.addIssue({
        code: "custom",
        message: "category counts must equal eligible count",
        path: ["category_counts"],
      });
    }

    const hours = new Set<number>();
    const hourlyTotal = aggregate.hourly_buckets.reduce((total, bucket) => {
      if (hours.has(bucket.hour)) {
        context.addIssue({
          code: "custom",
          message: "hourly buckets must not repeat an hour",
          path: ["hourly_buckets"],
        });
      }
      hours.add(bucket.hour);
      return total + bucket.count;
    }, 0);
    if (hourlyTotal !== aggregate.eligible_count) {
      context.addIssue({
        code: "custom",
        message: "hourly bucket counts must equal eligible count",
        path: ["hourly_buckets"],
      });
    }
  });

const result = z.object({
  localDate: z.iso.date(),
  revision: z.number().int().positive(),
  status: z.enum(["accepted", "duplicate"]),
});

const successResponse = z.object({ result });
const conflictResponse = z.object({ error: z.literal("aggregate_revision_conflict") });
const authorizationResponse = z.object({ error: z.enum(["consent_inactive", "unauthorized"]) });

export async function registerNotificationAggregateRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  const service = new NotificationAggregateService(new NotificationAggregateRepository(database));

  app.put(
    "/v1/notification-aggregates/daily",
    {
      schema: {
        body: dailyNotificationAggregate,
        response: {
          202: successResponse,
          401: authorizationResponse,
          403: authorizationResponse,
          409: conflictResponse,
        },
      },
    },
    async (request, reply) => {
      const claims = authenticatedClaims(request.headers.authorization, config);
      if (!claims) {
        return reply.code(401).send({ error: "unauthorized" });
      }

      const aggregate = dailyNotificationAggregate.parse(request.body);
      try {
        const result = await service.ingestDaily({
          ...claims,
          aggregate: {
            categoryCounts: aggregate.category_counts,
            consentScopeVersion: aggregate.consent_scope_version,
            eligibleCount: aggregate.eligible_count,
            gamePressure: aggregate.game_pressure,
            hourlyBuckets: aggregate.hourly_buckets,
            localDate: aggregate.local_date,
            observationCompleteness: aggregate.observation_completeness,
            revision: aggregate.revision,
            rulesVersion: aggregate.rules_version,
            timezoneOffsetMinutes: aggregate.timezone_offset_minutes,
          },
          buildChannel: config.buildChannel,
        });
        return reply.code(202).send({ result });
      } catch (error) {
        if (error instanceof ConsentInactiveError) {
          return reply.code(403).send({ error: "consent_inactive" });
        }
        if (error instanceof NotificationAggregateRevisionConflictError) {
          return reply.code(409).send({ error: "aggregate_revision_conflict" });
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
