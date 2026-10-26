import { createHash, randomUUID } from "node:crypto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildApp } from "../../src/app.js";
import { createSqlClient, type DatabaseClient } from "../../src/db/client.js";
import { migrateDatabase } from "../../src/db/migrate.js";
import { loadPostgresTestConfig, PostgresTestHarness } from "../support/postgres.js";
import { testConfig } from "../test-config.js";

const postgresConfig = loadPostgresTestConfig();
const describePostgres = postgresConfig ? describe : describe.skip;
const adminKey = "development-controller-key-with-sufficient-entropy";
const decreeAdminKey = "decree-controller-key-with-sufficient-entropy";
const decreeClientKey = "decree-shell-key-with-sufficient-client-entropy";
const pjsAdminKey = "pjs-controller-key-with-sufficient-entropy";
const pjsClientKey = "pjs-shell-key-with-sufficient-client-entropy";
const deployedRevision = "a".repeat(40);
const registration = {
  appVersion: "0.1.10-dev-debug",
  device: { androidApiLevel: 35, deviceClass: "phone" },
};

describePostgres("development feedback API", () => {
  const apps: Awaited<ReturnType<typeof buildApp>>[] = [];
  let adminDatabase: DatabaseClient;
  let harness: PostgresTestHarness;
  let runtime: DatabaseClient;

  beforeAll(async () => {
    if (!postgresConfig) throw new Error("PostgreSQL test configuration is required");
    harness = await PostgresTestHarness.create(postgresConfig);
    await migrateDatabase(harness.migrationDatabaseUrl);
    const adminUrl = new URL(harness.adminDatabaseUrl);
    adminUrl.pathname = "/afterchime_test";
    adminDatabase = createSqlClient(adminUrl.toString());
    runtime = createSqlClient(harness.runtimeDatabaseUrl);
  }, 30_000);

  afterEach(async () => Promise.all(apps.splice(0).map((app) => app.close())));
  beforeEach(async () => {
    await adminDatabase`TRUNCATE TABLE users RESTART IDENTITY CASCADE`;
  });
  afterAll(async () => {
    await runtime?.end({ timeout: 5 });
    await adminDatabase?.end({ timeout: 5 });
    await harness?.close();
  });

  async function appForDatabase() {
    const app = await buildApp({
      config: {
        ...testConfig,
        adminApiKeyHash: createHash("sha256").update(adminKey).digest("hex"),
        feedbackProjects: {
          ...testConfig.feedbackProjects,
          afterchime: {
            adminKeyHash: createHash("sha256").update(adminKey).digest("hex"),
            clientKeyHash: null,
            deploymentUrl: null,
          },
          decree: {
            adminKeyHash: createHash("sha256").update(decreeAdminKey).digest("hex"),
            clientKeyHash: createHash("sha256").update(decreeClientKey).digest("hex"),
            deploymentUrl: "https://decree.example/version.json",
          },
          "please-just-sleep": {
            adminKeyHash: createHash("sha256").update(pjsAdminKey).digest("hex"),
            clientKeyHash: createHash("sha256").update(pjsClientKey).digest("hex"),
            deploymentUrl: "https://pjs.example/version.json",
          },
        },
        databaseUrl: harness.runtimeDatabaseUrl,
        deployedRevision,
        developmentModuleEnabled: true,
      },
      database: runtime,
    });
    apps.push(app);
    return app;
  }

  async function install(app: Awaited<ReturnType<typeof buildApp>>, address: string) {
    const response = await app.inject({
      headers: { "idempotency-key": randomUUID() },
      method: "POST",
      payload: registration,
      remoteAddress: address,
      url: "/v1/installations",
    });
    expect(response.statusCode).toBe(201);
    return response.json() as { accessToken: string; installationId: string };
  }

  async function seedTrustedIdentities() {
    await adminDatabase`
      INSERT INTO users (id, build_channel, is_synthetic)
      VALUES
        ('10000000-0000-4000-8000-000000000001', 'dev', true),
        ('20000000-0000-4000-8000-000000000001', 'dev', true)
      ON CONFLICT (id) DO NOTHING
    `;
    await adminDatabase`
      INSERT INTO installations (id, user_id, app_version, build_channel, is_synthetic)
      VALUES
        ('10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'dev-feedback-proxy', 'dev', true),
        ('20000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'dev-feedback-proxy', 'dev', true)
      ON CONFLICT (id) DO NOTHING
    `;
  }

  it("stores one immutable screenshot revision idempotently and isolates installations", async () => {
    const app = await appForDatabase();
    const first = await install(app, "127.0.2.1");
    const second = await install(app, "127.0.2.2");
    const key = randomUUID();
    const payload = feedbackPayload("First item");

    const created = await submit(app, first.accessToken, key, payload);
    const replay = await submit(app, first.accessToken, key, payload);
    const firstList = await list(app, first.accessToken);
    const secondList = await list(app, second.accessToken);

    expect(created.statusCode).toBe(201);
    expect(replay.statusCode).toBe(201);
    expect(created.json().item).toMatchObject({ queuePosition: 1, revision: 1, status: "queued" });
    expect(replay.json().item.id).toBe(created.json().item.id);
    expect(firstList.json().items).toHaveLength(1);
    expect(secondList.json().items).toEqual([]);
    const collision = await submit(app, first.accessToken, key, feedbackPayload("Changed title"));
    expect(collision.statusCode).toBe(409);

    const secondItem = await submit(
      app,
      first.accessToken,
      randomUUID(),
      feedbackPayload("Second item"),
    );
    const crossItemReplay = await app.inject({
      headers: { authorization: `Bearer ${first.accessToken}`, "idempotency-key": key },
      method: "POST",
      payload,
      url: `/v1/dev-feedback/items/${secondItem.json().item.id}/amendments`,
    });
    expect(crossItemReplay.statusCode).toBe(409);
  });

  it("keeps trusted Decree and PJS queues isolated under separate credentials", async () => {
    await seedTrustedIdentities();
    const app = await appForDatabase();
    const submitTrusted = (project: "decree" | "please-just-sleep", key: string, title: string) =>
      app.inject({
        method: "POST",
        url: "/v1/dev-feedback/trusted/items",
        headers: {
          "idempotency-key": randomUUID(),
          "x-dev-feedback-client-key": key,
          "x-dev-feedback-project": project,
        },
        payload: feedbackPayload(title),
      });
    const claimTrusted = (project: "decree" | "please-just-sleep", key: string) =>
      app.inject({
        method: "POST",
        url: "/v1/dev-feedback/admin/claim",
        headers: {
          "x-dev-feedback-admin-key": key,
          "x-dev-feedback-project": project,
        },
        payload: {},
      });

    expect((await submitTrusted("decree", decreeClientKey, "Decree item")).statusCode).toBe(201);
    expect((await submitTrusted("please-just-sleep", pjsClientKey, "PJS item")).statusCode).toBe(
      201,
    );
    const decreeClaim = (await claimTrusted("decree", decreeAdminKey)).json().item;
    const pjsClaim = (await claimTrusted("please-just-sleep", pjsAdminKey)).json().item;
    expect(decreeClaim.item.title).toBe("Decree item");
    expect(pjsClaim.item.title).toBe("PJS item");
    expect(decreeClaim.installationId).not.toBe(pjsClaim.installationId);
  });

  it("claims strictly in order, fences stale leases, and gates QA on the live Railway revision", async () => {
    const app = await appForDatabase();
    const first = await install(app, "127.0.3.1");
    const second = await install(app, "127.0.3.2");
    await submit(app, first.accessToken, randomUUID(), feedbackPayload("First item"));
    await submit(app, second.accessToken, randomUUID(), feedbackPayload("Second item"));

    const concurrentClaims = await Promise.all([
      admin(app, "claim", null, { leaseSeconds: 300 }),
      admin(app, "claim", null, { leaseSeconds: 300 }),
    ]);
    expect(concurrentClaims.every((response) => response.statusCode === 200)).toBe(true);
    const claimedItems = concurrentClaims.map((response) => response.json().item);
    expect(claimedItems.filter(Boolean)).toHaveLength(1);
    const firstClaim = claimedItems.find(Boolean);
    if (!firstClaim) throw new Error("one concurrent claim must succeed");
    expect(firstClaim.item.title).toBe("First item");
    expect((await admin(app, "claim", null, {})).json().item).toBeNull();

    const progressed = await admin(app, "progress", firstClaim.item.id, {
      leaseId: firstClaim.leaseId,
      revision: firstClaim.item.revision,
      note: "Commit pushed; deployment verification pending.",
      targetCommit: deployedRevision,
    });
    expect(progressed.statusCode).toBe(200);

    const wrongReady = await admin(app, "ready", firstClaim.item.id, {
      leaseId: firstClaim.leaseId,
      revision: firstClaim.item.revision,
      note: "Not actually deployed.",
      targetCommit: deployedRevision,
      deployedCommit: "b".repeat(40),
    });
    expect(wrongReady.statusCode).toBe(409);

    const ready = await admin(app, "ready", firstClaim.item.id, {
      leaseId: firstClaim.leaseId,
      revision: firstClaim.item.revision,
      note: "Deployed to Railway development and ready to check.",
      targetCommit: deployedRevision,
      deployedCommit: deployedRevision,
    });
    expect(ready.statusCode).toBe(200);
    expect(ready.json().item.item.status).toBe("ready_for_qa");

    const secondClaim = (await admin(app, "claim", null, {})).json().item;
    expect(secondClaim.item.title).toBe("Second item");
    const blocked = await admin(app, "block", secondClaim.item.id, {
      leaseId: secondClaim.leaseId,
      revision: secondClaim.item.revision,
      note: "Andrew must choose one of two product outcomes.",
    });
    expect(blocked.statusCode).toBe(200);

    await submit(app, first.accessToken, randomUUID(), feedbackPayload("Third item"));
    expect((await admin(app, "claim", null, {})).json().item).toBeNull();

    const amendment = await app.inject({
      headers: { authorization: `Bearer ${second.accessToken}`, "idempotency-key": randomUUID() },
      method: "POST",
      payload: feedbackPayload("Second item amended"),
      url: `/v1/dev-feedback/items/${secondClaim.item.id}/amendments`,
    });
    expect(amendment.statusCode).toBe(201);
    expect(amendment.json().item).toMatchObject({
      queuePosition: secondClaim.item.queuePosition,
      revision: 2,
      status: "queued",
    });
    const reclaimed = (await admin(app, "claim", null, {})).json().item;
    expect(reclaimed.item.id).toBe(secondClaim.item.id);
    const stale = await admin(app, "progress", secondClaim.item.id, {
      leaseId: secondClaim.leaseId,
      revision: secondClaim.item.revision,
      note: "Stale worker must not land.",
      targetCommit: null,
    });
    expect(stale.statusCode).toBe(409);
  });

  it("lease-fences terminal failures without blocking later queued work", async () => {
    const app = await appForDatabase();
    const owner = await install(app, "127.0.3.7");
    const failedItem = await submit(
      app,
      owner.accessToken,
      randomUUID(),
      feedbackPayload("Automation will fail"),
    );
    const laterItem = await submit(
      app,
      owner.accessToken,
      randomUUID(),
      feedbackPayload("Continue after failure"),
    );
    const claimed = (await admin(app, "claim", null, {})).json().item;
    expect(claimed.item.id).toBe(failedItem.json().item.id);

    const staleLease = await admin(app, "fail", claimed.item.id, {
      leaseId: randomUUID(),
      revision: claimed.item.revision,
      note: "A stale lease must not fail this item.",
    });
    expect(staleLease.statusCode).toBe(409);
    const staleRevision = await admin(app, "fail", claimed.item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision + 1,
      note: "A stale revision must not fail this item.",
    });
    expect(staleRevision.statusCode).toBe(409);
    const unboundedNote = await admin(app, "fail", claimed.item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "x".repeat(601),
    });
    expect(unboundedNote.statusCode).toBe(400);

    const failed = await admin(app, "fail", claimed.item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "Provider retries were exhausted.",
    });
    expect(failed.statusCode).toBe(200);
    expect(failed.json().item).toMatchObject({
      item: {
        id: claimed.item.id,
        status: "failed",
        note: "Provider retries were exhausted.",
      },
      leaseId: null,
      leaseExpiresAt: null,
    });

    const visible = await list(app, owner.accessToken);
    expect(visible.json().items).toEqual([
      expect.objectContaining({ id: claimed.item.id, status: "failed" }),
      expect.objectContaining({ id: laterItem.json().item.id, status: "queued" }),
    ]);
    const amendment = await app.inject({
      headers: {
        authorization: `Bearer ${owner.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: feedbackPayload("Failed item amendment"),
      url: `/v1/dev-feedback/items/${claimed.item.id}/amendments`,
    });
    expect(amendment.statusCode).toBe(409);
    expect((await accept(app, owner.accessToken, claimed.item.id)).statusCode).toBe(409);

    const continued = (await admin(app, "claim", null, {})).json().item;
    expect(continued.item.id).toBe(laterItem.json().item.id);
    const closed = await close(app, owner.accessToken, claimed.item.id);
    expect(closed.statusCode).toBe(200);
    expect(closed.json().item.status).toBe("closed");
    expect((await close(app, owner.accessToken, claimed.item.id)).statusCode).toBe(200);
  });

  it("replays concurrent identical amendments instead of returning a conflict", async () => {
    const app = await appForDatabase();
    const installation = await install(app, "127.0.3.3");
    const created = await submit(
      app,
      installation.accessToken,
      randomUUID(),
      feedbackPayload("Concurrent amendment"),
    );
    const itemId = created.json().item.id;
    const key = randomUUID();
    const payload = feedbackPayload("Concurrent amendment replayed");
    const amend = () =>
      app.inject({
        headers: { authorization: `Bearer ${installation.accessToken}`, "idempotency-key": key },
        method: "POST",
        payload,
        url: `/v1/dev-feedback/items/${itemId}/amendments`,
      });

    const responses = await Promise.all([amend(), amend()]);
    expect(responses.map((response) => response.statusCode)).toEqual([201, 201]);
    expect(responses.map((response) => response.json().item.id)).toEqual([itemId, itemId]);
    expect(responses.map((response) => response.json().item.revision)).toEqual([2, 2]);
  });

  it("queues follow-ups during active work, preserves immutable history, and locks the title", async () => {
    const app = await appForDatabase();
    const installation = await install(app, "127.0.3.6");
    const original = await submit(
      app,
      installation.accessToken,
      randomUUID(),
      feedbackPayload("Original title", "Original request detail."),
    );
    const itemId = original.json().item.id;
    const active = (await admin(app, "claim", null, {})).json().item;

    const firstFollowUp = await app.inject({
      headers: {
        authorization: `Bearer ${installation.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: feedbackPayload("Attempted rename", "First follow-up detail."),
      url: `/v1/dev-feedback/items/${itemId}/amendments`,
    });

    expect(firstFollowUp.statusCode).toBe(201);
    expect(firstFollowUp.json().item).toMatchObject({
      title: "Original title",
      description:
        "Original request: Original request detail. | Follow-up 1: First follow-up detail.",
      revision: 2,
      status: "queued",
    });
    expect(
      (
        await admin(app, "progress", itemId, {
          leaseId: active.leaseId,
          revision: active.item.revision,
          note: "A stale worker must be fenced.",
          targetCommit: null,
        })
      ).statusCode,
    ).toBe(409);

    await adminDatabase`
      UPDATE dev_feedback_items
      SET title = 'Legacy mutable title'
      WHERE id = ${itemId}
    `;
    const secondFollowUp = await app.inject({
      headers: {
        authorization: `Bearer ${installation.accessToken}`,
        "idempotency-key": randomUUID(),
      },
      method: "POST",
      payload: feedbackPayload("Another attempted rename", "Second follow-up detail."),
      url: `/v1/dev-feedback/items/${itemId}/amendments`,
    });
    expect(secondFollowUp.statusCode).toBe(201);
    expect(secondFollowUp.json().item).toMatchObject({
      title: "Original title",
      description:
        "Original request: Original request detail. | Follow-up 1: First follow-up detail. | Follow-up 2: Second follow-up detail.",
      revision: 3,
      status: "queued",
    });

    const revisions = await adminDatabase<
      Readonly<{ revision: number; title: string; description: string }>[]
    >`
      SELECT revision, title, description
      FROM dev_feedback_revisions
      WHERE item_id = ${itemId}
      ORDER BY revision
    `;
    expect(revisions).toEqual([
      { revision: 1, title: "Original title", description: "Original request detail." },
      { revision: 2, title: "Original title", description: "First follow-up detail." },
      { revision: 3, title: "Original title", description: "Second follow-up detail." },
    ]);
    const claimed = (await admin(app, "claim", null, {})).json().item;
    expect(claimed.item).toMatchObject({
      id: itemId,
      title: "Original title",
      description:
        "Original request: Original request detail. | Follow-up 1: First follow-up detail. | Follow-up 2: Second follow-up detail.",
      revision: 3,
    });
  });

  it("closes an unneeded open item idempotently while retaining its evidence", async () => {
    const app = await appForDatabase();
    const owner = await install(app, "127.0.3.4");
    const other = await install(app, "127.0.3.5");
    const created = await submit(app, owner.accessToken, randomUUID(), feedbackPayload("Close me"));
    const itemId = created.json().item.id;

    expect((await close(app, other.accessToken, itemId)).statusCode).toBe(409);
    const closed = await close(app, owner.accessToken, itemId);
    expect(closed.statusCode).toBe(200);
    expect(closed.json().item).toMatchObject({
      id: itemId,
      status: "closed",
      note: "Closed as unneeded by Andrew.",
    });
    expect(closed.json().items).toEqual([]);
    expect((await close(app, owner.accessToken, itemId)).statusCode).toBe(200);

    const [retained] = await adminDatabase<
      Readonly<{ status: string; revisions: string | number }>[]
    >`
      SELECT item.status::text AS status, count(revision.id) AS revisions
      FROM dev_feedback_items AS item
      JOIN dev_feedback_revisions AS revision ON revision.item_id = item.id
      WHERE item.id = ${itemId}
      GROUP BY item.status
    `;
    expect(retained).toEqual({ status: "closed", revisions: "1" });

    const active = await submit(
      app,
      owner.accessToken,
      randomUUID(),
      feedbackPayload("Working now"),
    );
    expect((await admin(app, "claim", null, {})).json().item.item.id).toBe(active.json().item.id);
    expect((await close(app, owner.accessToken, active.json().item.id)).statusCode).toBe(409);
  });

  it("allows acceptance only after exact development deployment proof", async () => {
    const app = await appForDatabase();
    const installation = await install(app, "127.0.4.1");
    const created = await submit(
      app,
      installation.accessToken,
      randomUUID(),
      feedbackPayload("Accept me"),
    );
    const itemId = created.json().item.id;
    expect((await accept(app, installation.accessToken, itemId)).statusCode).toBe(409);

    const claimed = (await admin(app, "claim", null, {})).json().item;
    const progressed = await admin(app, "progress", itemId, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "Commit pushed; deployment verification pending.",
      targetCommit: deployedRevision,
    });
    expect(progressed.statusCode).toBe(200);
    await admin(app, "ready", itemId, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "Exact development deployment verified.",
      targetCommit: deployedRevision,
      deployedCommit: deployedRevision,
    });
    const accepted = await accept(app, installation.accessToken, itemId);
    expect(accepted.statusCode).toBe(200);
    expect(accepted.json().item.status).toBe("accepted");
    expect(accepted.json().items).toEqual([]);
  });

  it("cannot replace the assigned target commit while marking an item ready", async () => {
    const app = await appForDatabase();
    const installation = await install(app, "127.0.4.2");
    const created = await submit(
      app,
      installation.accessToken,
      randomUUID(),
      feedbackPayload("Keep the target"),
    );
    const claimed = (await admin(app, "claim", null, {})).json().item;
    await admin(app, "progress", created.json().item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "A different commit was assigned.",
      targetCommit: "b".repeat(40),
    });
    const replaced = await admin(app, "ready", created.json().item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "Attempted replacement.",
      targetCommit: deployedRevision,
      deployedCommit: deployedRevision,
    });
    expect(replaced.statusCode).toBe(409);
  });

  it("recovers an expired lease without discarding a pushed target commit", async () => {
    const app = await appForDatabase();
    const installation = await install(app, "127.0.5.1");
    await submit(app, installation.accessToken, randomUUID(), feedbackPayload("Recover me"));
    const claimed = (await admin(app, "claim", null, {})).json().item;
    const targetCommit = "d".repeat(40);
    const progressed = await admin(app, "progress", claimed.item.id, {
      leaseId: claimed.leaseId,
      revision: claimed.item.revision,
      note: "Commit pushed; deployment verification pending.",
      targetCommit,
    });
    expect(progressed.statusCode).toBe(200);
    await adminDatabase`
      UPDATE dev_feedback_items
      SET lease_expires_at = now() - interval '1 second'
      WHERE id = ${claimed.item.id}
    `;

    const reclaimed = (await admin(app, "claim", null, {})).json().item;
    expect(reclaimed.item.id).toBe(claimed.item.id);
    expect(reclaimed.targetCommit).toBe(targetCommit);
  });

  function feedbackPayload(title: string, description = "A bounded targeted development change.") {
    const screenshot = Buffer.from([1, 2, 3, 4]);
    return {
      title,
      description,
      context: {
        route: "today",
        shellRevision: "c".repeat(64),
        appVersion: "0.1.10-dev-debug",
        selection: {
          kind: "region",
          rect: { x: 1, y: 2, width: 20, height: 30 },
          viewportWidth: 360,
          viewportHeight: 800,
          devId: "",
          tag: "",
          role: "",
          accessibleLabel: "",
          visibleLabel: "",
          domPath: "",
        },
      },
      screenshot: {
        mediaType: "image/webp",
        contentBase64: screenshot.toString("base64"),
        sha256: createHash("sha256").update(screenshot).digest("hex"),
        width: 360,
        height: 800,
      },
    };
  }

  function submit(
    app: Awaited<ReturnType<typeof buildApp>>,
    token: string,
    key: string,
    payload: ReturnType<typeof feedbackPayload>,
  ) {
    return app.inject({
      headers: { authorization: `Bearer ${token}`, "idempotency-key": key },
      method: "POST",
      payload,
      url: "/v1/dev-feedback/items",
    });
  }

  function list(app: Awaited<ReturnType<typeof buildApp>>, token: string) {
    return app.inject({
      headers: { authorization: `Bearer ${token}` },
      method: "GET",
      url: "/v1/dev-feedback/items",
    });
  }

  function accept(app: Awaited<ReturnType<typeof buildApp>>, token: string, itemId: string) {
    return app.inject({
      headers: { authorization: `Bearer ${token}` },
      method: "POST",
      payload: {},
      url: `/v1/dev-feedback/items/${itemId}/accept`,
    });
  }

  function close(app: Awaited<ReturnType<typeof buildApp>>, token: string, itemId: string) {
    return app.inject({
      headers: { authorization: `Bearer ${token}` },
      method: "POST",
      payload: {},
      url: `/v1/dev-feedback/items/${itemId}/close`,
    });
  }

  function admin(
    app: Awaited<ReturnType<typeof buildApp>>,
    operation: "claim" | "heartbeat" | "progress" | "block" | "fail" | "ready",
    itemId: string | null,
    payload: Record<string, unknown>,
  ) {
    return app.inject({
      headers: { "x-afterchime-admin-key": adminKey },
      method: "POST",
      payload,
      url:
        operation === "claim"
          ? "/v1/dev-feedback/admin/claim"
          : `/v1/dev-feedback/admin/items/${itemId}/${operation}`,
    });
  }
});
