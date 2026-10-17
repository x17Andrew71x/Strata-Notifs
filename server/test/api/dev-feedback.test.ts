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

    const inProgressAmendment = await app.inject({
      headers: { authorization: `Bearer ${first.accessToken}`, "idempotency-key": randomUUID() },
      method: "POST",
      payload: feedbackPayload("Must wait for the active run"),
      url: `/v1/dev-feedback/items/${firstClaim.item.id}/amendments`,
    });
    expect(inProgressAmendment.statusCode).toBe(409);

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

  function feedbackPayload(title: string) {
    const screenshot = Buffer.from([1, 2, 3, 4]);
    return {
      title,
      description: "A bounded targeted development change.",
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
    operation: "claim" | "heartbeat" | "progress" | "block" | "ready",
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
