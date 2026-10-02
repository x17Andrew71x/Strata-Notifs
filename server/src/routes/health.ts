import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../config.js";

const responseSchema = z.object({
  status: z.literal("ok"),
  service: z.literal("stratawake-api"),
  version: z.string(),
  environment: z.enum(["development", "test", "production"]),
});

export async function registerHealthRoutes(
  app: FastifyInstance,
  config: ServerConfig,
): Promise<void> {
  app.get(
    "/health/live",
    {
      schema: {
        response: {
          200: responseSchema,
        },
      },
    },
    async () => ({
      status: "ok" as const,
      service: "stratawake-api" as const,
      version: "0.1.0",
      environment: config.nodeEnv,
    }),
  );
}
