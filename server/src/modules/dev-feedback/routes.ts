import { createHash } from "node:crypto";
import type { FastifyInstance, FastifyReply } from "fastify";
import { z } from "zod";
import type { ServerConfig } from "../../config.js";
import type { DatabaseClient } from "../../db/client.js";
import { verifyAdminKey } from "../../security/admin-auth.js";
import { verifyAccessToken } from "../../security/tokens.js";
import {
  DevFeedbackConflictError,
  type DevFeedbackIdentity,
  DevFeedbackNotReadyError,
  DevFeedbackRepository,
  type DevFeedbackRevisionInput,
} from "./repository.js";

const uuid = z.string().uuid();
const commit = z.string().regex(/^[0-9a-f]{40}$/);
const digest = z.string().regex(/^[0-9a-f]{64}$/);
const hasControlCharacter = (value: string) =>
  Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || code === 127;
  });
const boundedText = (minimum: number, maximum: number) =>
  z
    .string()
    .trim()
    .min(minimum)
    .max(maximum)
    .refine((value) => !hasControlCharacter(value));

const selectionRect = z
  .object({
    x: z.number().int().min(0).max(16_384),
    y: z.number().int().min(0).max(16_384),
    width: z.number().int().min(1).max(16_384),
    height: z.number().int().min(1).max(16_384),
  })
  .strict();
const selection = z
  .object({
    kind: z.enum(["element", "region"]),
    rect: selectionRect,
    viewportWidth: z.number().int().min(1).max(16_384),
    viewportHeight: z.number().int().min(1).max(16_384),
    devId: z.string().max(96),
    tag: z.string().max(32),
    role: z.string().max(64),
    accessibleLabel: z.string().max(160),
    visibleLabel: z.string().max(160),
    domPath: z.string().max(512),
  })
  .strict()
  .superRefine((value, context) => {
    const text = [
      value.devId,
      value.tag,
      value.role,
      value.accessibleLabel,
      value.visibleLabel,
      value.domPath,
    ];
    if (text.some(hasControlCharacter)) {
      context.addIssue({ code: "custom", message: "selection text is invalid" });
    }
    if (value.kind === "region" && text.some(Boolean)) {
      context.addIssue({ code: "custom", message: "region selection must not carry element text" });
    }
    if (value.kind === "element" && (!value.tag || !value.domPath)) {
      context.addIssue({ code: "custom", message: "element selection needs tag and path" });
    }
  });
const feedbackBody = z
  .object({
    title: boundedText(1, 120),
    description: boundedText(1, 4_000),
    context: z
      .object({
        route: boundedText(1, 160),
        shellRevision: digest,
        appVersion: boundedText(1, 64),
        selection,
      })
      .strict(),
    screenshot: z
      .object({
        mediaType: z.literal("image/webp"),
        contentBase64: z
          .string()
          .min(4)
          .max(1_048_576)
          .regex(/^[A-Za-z0-9+/]+={0,2}$/),
        sha256: digest,
        width: z.number().int().min(1).max(16_384),
        height: z.number().int().min(1).max(16_384),
      })
      .strict(),
  })
  .strict();
const idempotencyHeaders = z.object({ "idempotency-key": uuid });
const authorizationError = z.object({ error: z.enum(["unauthorized", "not_ready", "conflict"]) });
const itemResponse = z.object({
  id: uuid,
  queuePosition: z.number().int().positive(),
  revision: z.number().int().positive(),
  title: z.string(),
  description: z.string(),
  status: z.enum(["queued", "in_progress", "blocked", "ready_for_qa", "accepted"]),
  note: z.string(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});
const stateResponse = z.object({
  item: itemResponse.nullable(),
  items: z.array(itemResponse).max(200),
});
const adminHeaders = z.object({ "x-afterchime-admin-key": z.string().min(1).max(256) });
const adminItemResponse = z.object({
  item: itemResponse,
  installationId: uuid,
  leaseId: uuid.nullable(),
  leaseExpiresAt: z.string().datetime().nullable(),
  targetCommit: commit.nullable(),
  deployedCommit: commit.nullable(),
  context: z.record(z.string(), z.unknown()),
  screenshotBase64: z.string(),
  screenshotMediaType: z.literal("image/webp"),
  screenshotSha256: digest,
  screenshotWidth: z.number().int().positive(),
  screenshotHeight: z.number().int().positive(),
});
const adminStateResponse = z.object({ item: adminItemResponse.nullable() });
const leaseSeconds = z.number().int().min(60).max(900);
const claimBody = z.object({ leaseSeconds: leaseSeconds.optional() }).strict();
const leaseBody = z.object({ leaseId: uuid, revision: z.number().int().positive() }).strict();
const heartbeatBody = leaseBody.extend({ leaseSeconds: leaseSeconds.optional() }).strict();
const progressBody = leaseBody
  .extend({ note: boundedText(1, 600), targetCommit: commit.nullable().optional() })
  .strict();
const readyBody = leaseBody
  .extend({ note: boundedText(1, 600), targetCommit: commit, deployedCommit: commit })
  .strict();

export async function registerDevelopmentModule(
  app: FastifyInstance,
  config: ServerConfig,
  database: DatabaseClient,
): Promise<void> {
  if (!config.developmentModuleEnabled || config.buildChannel !== "dev") return;
  const repository = new DevFeedbackRepository(database);

  app.get(
    "/v1/dev-feedback/items",
    { schema: { response: { 200: stateResponse, 401: authorizationError } } },
    async (request, reply) => {
      const identity = authenticatedIdentity(request.headers.authorization, config);
      if (!identity) return reply.code(401).send({ error: "unauthorized" });
      return reply.send(await repository.list(identity));
    },
  );

  app.post(
    "/v1/dev-feedback/items",
    {
      bodyLimit: 1_100 * 1_024,
      schema: {
        body: feedbackBody,
        headers: idempotencyHeaders,
        response: {
          200: stateResponse,
          201: stateResponse,
          401: authorizationError,
          409: authorizationError,
        },
      },
    },
    async (request, reply) => {
      const identity = authenticatedIdentity(request.headers.authorization, config);
      if (!identity) return reply.code(401).send({ error: "unauthorized" });
      const headers = idempotencyHeaders.parse(request.headers);
      try {
        const state = await repository.create(
          identity,
          revisionInput(feedbackBody.parse(request.body), headers["idempotency-key"], config),
        );
        return reply.code(201).send(state);
      } catch (error) {
        if (error instanceof DevFeedbackConflictError)
          return reply.code(409).send({ error: "conflict" });
        throw error;
      }
    },
  );

  app.post<{ Params: { itemId: string } }>(
    "/v1/dev-feedback/items/:itemId/amendments",
    {
      bodyLimit: 1_100 * 1_024,
      schema: {
        params: z.object({ itemId: uuid }),
        body: feedbackBody,
        headers: idempotencyHeaders,
        response: {
          200: stateResponse,
          201: stateResponse,
          401: authorizationError,
          409: authorizationError,
        },
      },
    },
    async (request, reply) => {
      const identity = authenticatedIdentity(request.headers.authorization, config);
      if (!identity) return reply.code(401).send({ error: "unauthorized" });
      const headers = idempotencyHeaders.parse(request.headers);
      try {
        const state = await repository.amend(
          identity,
          request.params.itemId,
          revisionInput(feedbackBody.parse(request.body), headers["idempotency-key"], config),
        );
        return reply.code(201).send(state);
      } catch (error) {
        if (error instanceof DevFeedbackConflictError)
          return reply.code(409).send({ error: "conflict" });
        if (error instanceof DevFeedbackNotReadyError)
          return reply.code(409).send({ error: "not_ready" });
        throw error;
      }
    },
  );

  app.post<{ Params: { itemId: string } }>(
    "/v1/dev-feedback/items/:itemId/accept",
    {
      schema: {
        params: z.object({ itemId: uuid }),
        body: z.object({}).strict(),
        response: { 200: stateResponse, 401: authorizationError, 409: authorizationError },
      },
    },
    async (request, reply) => {
      const identity = authenticatedIdentity(request.headers.authorization, config);
      if (!identity) return reply.code(401).send({ error: "unauthorized" });
      try {
        return reply.send(await repository.accept(identity, request.params.itemId));
      } catch (error) {
        if (error instanceof DevFeedbackNotReadyError)
          return reply.code(409).send({ error: "not_ready" });
        throw error;
      }
    },
  );

  app.post(
    "/v1/dev-feedback/admin/claim",
    {
      schema: {
        headers: adminHeaders,
        body: claimBody,
        response: { 200: adminStateResponse, 401: authorizationError },
      },
    },
    async (request, reply) => {
      if (!adminAuthorized(request.headers, config))
        return reply.code(401).send({ error: "unauthorized" });
      const body = claimBody.parse(request.body);
      return reply.send({ item: await repository.claim(body.leaseSeconds) });
    },
  );

  app.get<{ Params: { itemId: string } }>(
    "/v1/dev-feedback/admin/items/:itemId",
    {
      schema: {
        params: z.object({ itemId: uuid }),
        headers: adminHeaders,
        response: { 200: adminStateResponse, 401: authorizationError },
      },
    },
    async (request, reply) => {
      if (!adminAuthorized(request.headers, config))
        return reply.code(401).send({ error: "unauthorized" });
      return reply.send({ item: await repository.adminGet(request.params.itemId) });
    },
  );

  const adminMutation = async (
    request: { headers: Record<string, unknown>; params: { itemId: string }; body: unknown },
    reply: FastifyReply,
    operation: "heartbeat" | "progress" | "block" | "ready",
  ) => {
    if (!adminAuthorized(request.headers, config))
      return reply.code(401).send({ error: "unauthorized" });
    try {
      if (operation === "heartbeat") {
        const body = heartbeatBody.parse(request.body);
        return reply.send({
          item: await repository.heartbeat(
            request.params.itemId,
            body.leaseId,
            body.revision,
            body.leaseSeconds,
          ),
        });
      }
      if (operation === "progress") {
        const body = progressBody.parse(request.body);
        return reply.send({
          item: await repository.progress(
            request.params.itemId,
            body.leaseId,
            body.revision,
            body.note,
            body.targetCommit ?? null,
          ),
        });
      }
      if (operation === "block") {
        const body = progressBody.omit({ targetCommit: true }).parse(request.body);
        return reply.send({
          item: await repository.block(
            request.params.itemId,
            body.leaseId,
            body.revision,
            body.note,
          ),
        });
      }
      const body = readyBody.parse(request.body);
      return reply.send({
        item: await repository.ready(
          request.params.itemId,
          body.leaseId,
          body.revision,
          body.note,
          body.targetCommit,
          body.deployedCommit,
          config.deployedRevision,
        ),
      });
    } catch (error) {
      if (error instanceof DevFeedbackConflictError)
        return reply.code(409).send({ error: "conflict" });
      throw error;
    }
  };

  for (const operation of ["heartbeat", "progress", "block", "ready"] as const) {
    app.post<{ Params: { itemId: string } }>(
      `/v1/dev-feedback/admin/items/:itemId/${operation}`,
      {
        schema: {
          params: z.object({ itemId: uuid }),
          headers: adminHeaders,
          body:
            operation === "heartbeat"
              ? heartbeatBody
              : operation === "ready"
                ? readyBody
                : operation === "block"
                  ? progressBody.omit({ targetCommit: true })
                  : progressBody,
          response: { 200: adminStateResponse, 401: authorizationError, 409: authorizationError },
        },
      },
      async (request, reply) => adminMutation(request, reply, operation),
    );
  }
}

function revisionInput(
  body: z.infer<typeof feedbackBody>,
  idempotencyKey: string,
  config: ServerConfig,
): DevFeedbackRevisionInput {
  const screenshot = Buffer.from(body.screenshot.contentBase64, "base64");
  if (
    screenshot.length < 1 ||
    screenshot.length > 786_432 ||
    screenshot.toString("base64") !== body.screenshot.contentBase64 ||
    createHash("sha256").update(screenshot).digest("hex") !== body.screenshot.sha256
  ) {
    throw new DevFeedbackConflictError("invalid_screenshot");
  }
  const context = {
    ...body.context,
    projectId: "afterchime",
    submittedDeploymentRevision: config.deployedRevision,
  };
  const requestHash = createHash("sha256")
    .update(
      JSON.stringify({
        title: body.title,
        description: body.description,
        context,
        screenshotSha256: body.screenshot.sha256,
        screenshotWidth: body.screenshot.width,
        screenshotHeight: body.screenshot.height,
      }),
    )
    .digest("hex");
  return {
    context,
    description: body.description,
    idempotencyKey,
    requestHash,
    screenshot,
    screenshotHeight: body.screenshot.height,
    screenshotMediaType: body.screenshot.mediaType,
    screenshotSha256: body.screenshot.sha256,
    screenshotWidth: body.screenshot.width,
    title: body.title,
  };
}

function authenticatedIdentity(
  authorization: string | string[] | undefined,
  config: ServerConfig,
): DevFeedbackIdentity | null {
  if (typeof authorization !== "string" || !authorization.startsWith("Bearer ")) return null;
  try {
    return verifyAccessToken({
      audience: config.tokenAudience,
      secret: config.accessTokenSecret,
      token: authorization.slice("Bearer ".length),
    });
  } catch {
    return null;
  }
}

function adminAuthorized(headers: Record<string, unknown>, config: ServerConfig): boolean {
  const presented = headers["x-afterchime-admin-key"];
  return verifyAdminKey(
    typeof presented === "string" ? presented : undefined,
    config.adminApiKeyHash,
  );
}
