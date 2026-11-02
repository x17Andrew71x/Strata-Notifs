import type { FastifyInstance } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAdminKey } from "../../security/admin-auth.js";
import { adminCredentialRateLimitKey } from "../../security/rate-limit.js";

const headers = z.object({ "x-admin-key": z.string().min(1).max(256) });
const params = z.object({ installationId: z.string().uuid() });
const body = z.object({ source: z.literal("owner_admin") }).strict();
const success = z.object({
  installationId: z.string().uuid(),
  trustStatus: z.literal("trusted"),
});
const error = z.object({ error: z.enum(["not_found", "unauthorized"]) });

export async function registerInstallationTrustRoutes(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  app.post(
    "/v1/admin/installations/:installationId/trust",
    {
      config: {
        rateLimit: { keyGenerator: adminCredentialRateLimitKey, max: 5, timeWindow: "1 minute" },
      },
      schema: {
        body,
        headers,
        params,
        response: { 200: success, 401: error, 404: error },
      },
    },
    async (request, reply) => {
      const parsedHeaders = headers.parse(request.headers);
      if (!verifyAdminKey(parsedHeaders["x-admin-key"], config.adminApiKeyHash)) {
        return reply.code(401).send({ error: "unauthorized" });
      }
      const parsedParams = params.parse(request.params);
      const parsedBody = body.parse(request.body);
      const [result] = await database<Readonly<{ granted: boolean }>[]>`
        SELECT public.afterchime_grant_installation_trust(
          ${parsedParams.installationId},
          ${parsedBody.source}
        ) AS granted
      `;
      if (result?.granted !== true) {
        return reply.code(404).send({ error: "not_found" });
      }
      return reply.code(200).send({
        installationId: parsedParams.installationId,
        trustStatus: "trusted",
      });
    },
  );
}
