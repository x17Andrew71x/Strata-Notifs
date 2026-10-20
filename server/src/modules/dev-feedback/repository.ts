import { randomUUID } from "node:crypto";
import type postgres from "postgres";
import type { DatabaseClient } from "../../db/client.js";
import type { DevFeedbackProject } from "./project.js";

export type DevFeedbackStatus =
  | "queued"
  | "in_progress"
  | "blocked"
  | "ready_for_qa"
  | "accepted"
  | "closed";

export type DevFeedbackIdentity = Readonly<{
  installationId: string;
  projectId: DevFeedbackProject;
  userId: string;
}>;

export type DevFeedbackRevisionInput = Readonly<{
  context: postgres.JSONValue;
  description: string;
  idempotencyKey: string;
  projectId: DevFeedbackProject;
  requestHash: string;
  screenshot: Buffer;
  screenshotHeight: number;
  screenshotMediaType: "image/webp";
  screenshotSha256: string;
  screenshotWidth: number;
  title: string;
}>;

export type DevFeedbackItem = Readonly<{
  id: string;
  queuePosition: number;
  revision: number;
  title: string;
  description: string;
  status: DevFeedbackStatus;
  note: string;
  createdAt: string;
  updatedAt: string;
}>;

export type DevFeedbackState = Readonly<{
  item: DevFeedbackItem | null;
  items: readonly DevFeedbackItem[];
}>;

export type DevFeedbackAdminItem = Readonly<{
  item: DevFeedbackItem;
  installationId: string;
  leaseId: string | null;
  leaseExpiresAt: string | null;
  targetCommit: string | null;
  deployedCommit: string | null;
  context: Record<string, unknown>;
  screenshotBase64: string;
  screenshotMediaType: string;
  screenshotSha256: string;
  screenshotWidth: number;
  screenshotHeight: number;
}>;

type ItemRow = Readonly<{
  id: string;
  queue_position: string | number;
  current_revision: number;
  title: string;
  description: string;
  status: DevFeedbackStatus;
  note: string;
  created_at: Date;
  updated_at: Date;
}>;

type AdminRow = ItemRow &
  Readonly<{
    installation_id: string;
    lease_id: string | null;
    lease_expires_at: Date | null;
    target_commit: string | null;
    deployed_commit: string | null;
    context: Record<string, unknown>;
    screenshot: Buffer;
    screenshot_media_type: string;
    screenshot_sha256: string;
    screenshot_width: number;
    screenshot_height: number;
  }>;

type RevisionDescriptionRow = Readonly<{
  revision: number;
  description: string;
  title: string;
}>;

const MAX_COMPILED_DESCRIPTION_CHARS = 4_000;

export class DevFeedbackConflictError extends Error {
  public constructor(message = "dev_feedback_conflict") {
    super(message);
    this.name = "DevFeedbackConflictError";
  }
}

export class DevFeedbackNotReadyError extends Error {
  public constructor(message = "dev_feedback_not_ready") {
    super(message);
    this.name = "DevFeedbackNotReadyError";
  }
}

export class DevFeedbackRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async list(identity: DevFeedbackIdentity): Promise<DevFeedbackState> {
    return this.database.begin(async (transaction) => {
      await setIdentity(transaction, identity);
      return {
        item: null,
        items: await listOpen(transaction, identity.installationId, identity.projectId),
      };
    });
  }

  public async create(
    identity: DevFeedbackIdentity,
    input: DevFeedbackRevisionInput,
  ): Promise<DevFeedbackState> {
    if (input.projectId !== identity.projectId) throw new DevFeedbackConflictError();
    return this.database.begin(async (transaction) => {
      await setIdentity(transaction, identity);
      const replay = await findRevisionByIdempotency(
        transaction,
        identity.installationId,
        input.idempotencyKey,
        input.requestHash,
      );
      if (replay) {
        return stateFor(transaction, identity.installationId, identity.projectId, replay.item_id);
      }

      const itemId = randomUUID();
      const revisionId = randomUUID();
      const [receipt] = await transaction<Readonly<{ result_id: string; request_hash: string }>[]>`
        INSERT INTO idempotency_records (
          id, installation_id, operation, idempotency_key, request_hash,
          result_id, result_status, expires_at
        ) VALUES (
          ${randomUUID()}, ${identity.installationId}, 'dev_feedback_create',
          ${input.idempotencyKey}, ${input.requestHash}, ${itemId}, 201, now() + interval '30 days'
        )
        ON CONFLICT (installation_id, operation, idempotency_key) DO NOTHING
        RETURNING result_id, request_hash
      `;
      if (!receipt) {
        const [existing] = await transaction<
          Readonly<{ result_id: string | null; request_hash: string }>[]
        >`
          SELECT result_id, request_hash
          FROM idempotency_records
          WHERE installation_id = ${identity.installationId}
            AND operation = 'dev_feedback_create'
            AND idempotency_key = ${input.idempotencyKey}
        `;
        if (!existing?.result_id || existing.request_hash !== input.requestHash) {
          throw new DevFeedbackConflictError();
        }
        return stateFor(
          transaction,
          identity.installationId,
          identity.projectId,
          existing.result_id,
        );
      }

      await transaction`
        INSERT INTO dev_feedback_items (
          id, installation_id, project_id, current_revision, title, description
        ) VALUES (
          ${itemId}, ${identity.installationId}, ${input.projectId}, 1, ${input.title}, ${input.description}
        )
      `;
      await insertRevision(transaction, identity.installationId, itemId, revisionId, 1, input);
      return stateFor(transaction, identity.installationId, identity.projectId, itemId);
    });
  }

  public async amend(
    identity: DevFeedbackIdentity,
    itemId: string,
    input: DevFeedbackRevisionInput,
  ): Promise<DevFeedbackState> {
    if (input.projectId !== identity.projectId) throw new DevFeedbackConflictError();
    return this.database.begin(async (transaction) => {
      await setIdentity(transaction, identity);
      const replay = await findRevisionByIdempotency(
        transaction,
        identity.installationId,
        input.idempotencyKey,
        input.requestHash,
      );
      if (replay) {
        if (replay.item_id !== itemId) throw new DevFeedbackConflictError();
        return stateFor(transaction, identity.installationId, identity.projectId, replay.item_id);
      }

      const [item] = await transaction<
        Readonly<{ current_revision: number; status: DevFeedbackStatus }>[]
      >`
        SELECT current_revision, status
        FROM dev_feedback_items
        WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
        FOR UPDATE
      `;
      if (!item || item.status === "accepted" || item.status === "closed") {
        throw new DevFeedbackNotReadyError();
      }
      const revision = item.current_revision + 1;
      const revisionId = randomUUID();
      const priorRevisions = await transaction<RevisionDescriptionRow[]>`
        SELECT revision, description, title
        FROM dev_feedback_revisions
        WHERE item_id = ${itemId} AND installation_id = ${identity.installationId}
        ORDER BY revision
      `;
      if (
        priorRevisions.length !== item.current_revision ||
        priorRevisions.some((entry, index) => entry.revision !== index + 1) ||
        !priorRevisions[0]
      ) {
        throw new DevFeedbackConflictError("dev_feedback_history_invalid");
      }
      const originalTitle = priorRevisions[0].title;
      const compiledDescription = formatFeedbackHistory([
        ...priorRevisions,
        { revision, description: input.description, title: originalTitle },
      ]);
      if (compiledDescription.length > MAX_COMPILED_DESCRIPTION_CHARS) {
        throw new DevFeedbackConflictError("dev_feedback_history_too_long");
      }
      const amendmentInput = { ...input, title: originalTitle };
      const operation = `dev_feedback_amend:${itemId}`;
      const [receipt] = await transaction<Readonly<{ result_id: string }>[]>`
        INSERT INTO idempotency_records (
          id, installation_id, operation, idempotency_key, request_hash,
          result_id, result_status, expires_at
        ) VALUES (
          ${randomUUID()}, ${identity.installationId}, ${operation},
          ${input.idempotencyKey}, ${input.requestHash}, ${revisionId}, 201, now() + interval '30 days'
        )
        ON CONFLICT (installation_id, operation, idempotency_key) DO NOTHING
        RETURNING result_id
      `;
      if (!receipt) {
        const replayAfterConflict = await findRevisionByIdempotency(
          transaction,
          identity.installationId,
          input.idempotencyKey,
          input.requestHash,
        );
        if (!replayAfterConflict || replayAfterConflict.item_id !== itemId) {
          throw new DevFeedbackConflictError();
        }
        return stateFor(transaction, identity.installationId, identity.projectId, itemId);
      }
      await insertRevision(
        transaction,
        identity.installationId,
        itemId,
        revisionId,
        revision,
        amendmentInput,
      );
      await transaction`
        UPDATE dev_feedback_items
        SET current_revision = ${revision},
            title = ${originalTitle},
            description = ${compiledDescription},
            status = 'queued'::dev_feedback_status,
            note = 'Follow-up queued in original order.',
            lease_id = NULL,
            lease_revision = NULL,
            lease_expires_at = NULL,
            target_commit = NULL,
            deployed_commit = NULL,
            updated_at = now()
        WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
      `;
      return stateFor(transaction, identity.installationId, identity.projectId, itemId);
    });
  }

  public async accept(identity: DevFeedbackIdentity, itemId: string): Promise<DevFeedbackState> {
    return this.database.begin(async (transaction) => {
      await setIdentity(transaction, identity);
      const [item] = await transaction<Readonly<{ status: DevFeedbackStatus }>[]>`
        SELECT status
        FROM dev_feedback_items
        WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
        FOR UPDATE
      `;
      if (!item) throw new DevFeedbackNotReadyError();
      if (item.status !== "accepted") {
        if (item.status !== "ready_for_qa") throw new DevFeedbackNotReadyError();
        await transaction`
          UPDATE dev_feedback_items
          SET status = 'accepted'::dev_feedback_status,
              note = 'Accepted by Andrew.',
              accepted_at = now(),
              updated_at = now()
          WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
        `;
      }
      const [accepted] = await itemRows(
        transaction,
        identity.installationId,
        identity.projectId,
        itemId,
      );
      return {
        item: accepted ? mapItem(accepted) : null,
        items: await listOpen(transaction, identity.installationId, identity.projectId),
      };
    });
  }

  public async close(identity: DevFeedbackIdentity, itemId: string): Promise<DevFeedbackState> {
    return this.database.begin(async (transaction) => {
      await setIdentity(transaction, identity);
      const [item] = await transaction<Readonly<{ status: DevFeedbackStatus }>[]>`
        SELECT status
        FROM dev_feedback_items
        WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
        FOR UPDATE
      `;
      if (!item) throw new DevFeedbackNotReadyError();
      if (item.status !== "closed") {
        if (item.status === "in_progress" || item.status === "accepted") {
          throw new DevFeedbackNotReadyError();
        }
        await transaction`
          UPDATE dev_feedback_items
          SET status = 'closed'::dev_feedback_status,
              note = 'Closed as unneeded by Andrew.',
              lease_id = NULL,
              lease_revision = NULL,
              lease_expires_at = NULL,
              updated_at = now()
          WHERE id = ${itemId} AND installation_id = ${identity.installationId}
          AND project_id = ${identity.projectId}
        `;
      }
      return stateFor(transaction, identity.installationId, identity.projectId, itemId);
    });
  }

  public async claim(
    projectId: DevFeedbackProject,
    leaseSeconds = 300,
  ): Promise<DevFeedbackAdminItem | null> {
    const leaseId = randomUUID();
    const [row] = await this.database<AdminRow[]>`
      SELECT * FROM public.afterchime_claim_dev_feedback(${projectId}, ${leaseId}, ${leaseSeconds})
    `;
    return row ? mapAdminItem(row) : null;
  }

  public async adminGet(
    projectId: DevFeedbackProject,
    itemId: string,
  ): Promise<DevFeedbackAdminItem | null> {
    const [row] = await this.database<AdminRow[]>`
      SELECT * FROM public.afterchime_get_dev_feedback(${projectId}, ${itemId})
    `;
    return row ? mapAdminItem(row) : null;
  }

  public async heartbeat(
    projectId: DevFeedbackProject,
    itemId: string,
    leaseId: string,
    revision: number,
    leaseSeconds = 300,
  ): Promise<DevFeedbackAdminItem> {
    const [row] = await this.database<AdminRow[]>`
      SELECT * FROM public.afterchime_heartbeat_dev_feedback(${projectId}, ${itemId}, ${leaseId}, ${revision}, ${leaseSeconds})
    `;
    if (!row) throw new DevFeedbackConflictError("stale_dev_feedback_lease");
    return mapAdminItem(row);
  }

  public async progress(
    projectId: DevFeedbackProject,
    itemId: string,
    leaseId: string,
    revision: number,
    note: string,
    targetCommit: string | null,
  ): Promise<DevFeedbackAdminItem> {
    const [row] = await this.database<AdminRow[]>`
      SELECT * FROM public.afterchime_progress_dev_feedback(
        ${projectId}, ${itemId}, ${leaseId}, ${revision}, ${note}, ${targetCommit}
      )
    `;
    if (!row) throw new DevFeedbackConflictError("stale_dev_feedback_lease");
    return mapAdminItem(row);
  }

  public async block(
    projectId: DevFeedbackProject,
    itemId: string,
    leaseId: string,
    revision: number,
    note: string,
  ): Promise<DevFeedbackAdminItem> {
    const [row] = await this.database<AdminRow[]>`
      SELECT * FROM public.afterchime_block_dev_feedback(${projectId}, ${itemId}, ${leaseId}, ${revision}, ${note})
    `;
    if (!row) throw new DevFeedbackConflictError("stale_dev_feedback_lease");
    return mapAdminItem(row);
  }

  public async ready(
    projectId: DevFeedbackProject,
    itemId: string,
    leaseId: string,
    revision: number,
    note: string,
    targetCommit: string,
    deployedCommit: string,
    liveDeployedRevision: string,
  ): Promise<DevFeedbackAdminItem> {
    let row: AdminRow | undefined;
    try {
      [row] = await this.database<AdminRow[]>`
        SELECT * FROM public.afterchime_ready_dev_feedback(
          ${projectId}, ${itemId}, ${leaseId}, ${revision}, ${note},
          ${targetCommit}, ${deployedCommit}, ${liveDeployedRevision}
        )
      `;
    } catch (error) {
      if (hasDatabaseCode(error, "22023")) {
        throw new DevFeedbackConflictError("development_not_deployed");
      }
      throw error;
    }
    if (!row) throw new DevFeedbackConflictError("stale_or_undeployed_dev_feedback");
    return mapAdminItem(row);
  }
}

async function setIdentity(
  database: postgres.TransactionSql,
  identity: DevFeedbackIdentity,
): Promise<void> {
  await database`SELECT set_config('afterchime.user_id', ${identity.userId}, true)`;
  await database`SELECT set_config('afterchime.installation_id', ${identity.installationId}, true)`;
}

async function insertRevision(
  database: postgres.TransactionSql,
  installationId: string,
  itemId: string,
  revisionId: string,
  revision: number,
  input: DevFeedbackRevisionInput,
): Promise<void> {
  const context = database.json(input.context);
  await database`
    INSERT INTO dev_feedback_revisions (
      id, item_id, installation_id, revision, idempotency_key, request_hash,
      title, description, context, screenshot, screenshot_media_type,
      screenshot_sha256, screenshot_width, screenshot_height
    ) VALUES (
      ${revisionId}, ${itemId}, ${installationId}, ${revision}, ${input.idempotencyKey},
      ${input.requestHash}, ${input.title}, ${input.description}, ${context}::jsonb,
      ${input.screenshot}, ${input.screenshotMediaType}, ${input.screenshotSha256},
      ${input.screenshotWidth}, ${input.screenshotHeight}
    )
  `;
}

async function findRevisionByIdempotency(
  database: postgres.TransactionSql,
  installationId: string,
  idempotencyKey: string,
  requestHash: string,
): Promise<Readonly<{ item_id: string }> | null> {
  const [row] = await database<Readonly<{ item_id: string; request_hash: string }>[]>`
    SELECT item_id, request_hash
    FROM dev_feedback_revisions
    WHERE installation_id = ${installationId} AND idempotency_key = ${idempotencyKey}
  `;
  if (!row) return null;
  if (row.request_hash !== requestHash) throw new DevFeedbackConflictError();
  return row;
}

async function stateFor(
  database: postgres.TransactionSql,
  installationId: string,
  projectId: DevFeedbackProject,
  itemId: string,
): Promise<DevFeedbackState> {
  const [row] = await itemRows(database, installationId, projectId, itemId);
  return {
    item: row ? mapItem(row) : null,
    items: await listOpen(database, installationId, projectId),
  };
}

async function itemRows(
  database: postgres.TransactionSql,
  installationId: string,
  projectId: DevFeedbackProject,
  itemId: string,
): Promise<ItemRow[]> {
  return database<ItemRow[]>`
    SELECT
      item.id,
      item.queue_position,
      item.current_revision,
      min(revision.title) FILTER (WHERE revision.revision = 1) AS title,
      CASE
        WHEN count(revision.id) = 1 THEN btrim(regexp_replace(min(revision.description), '[[:space:]]+', ' ', 'g'))
        ELSE string_agg(
          CASE
            WHEN revision.revision = 1 THEN 'Original request:'
            ELSE 'Follow-up ' || (revision.revision - 1)::text || ':'
          END || ' ' || btrim(regexp_replace(revision.description, '[[:space:]]+', ' ', 'g')),
          ' | ' ORDER BY revision.revision
        )
      END AS description,
      item.status,
      item.note,
      item.created_at,
      item.updated_at
    FROM dev_feedback_items AS item
    JOIN dev_feedback_revisions AS revision ON revision.item_id = item.id
    WHERE item.installation_id = ${installationId}
      AND item.project_id = ${projectId}
      AND item.id = ${itemId}
    GROUP BY item.id
  `;
}

async function listOpen(
  database: postgres.TransactionSql,
  installationId: string,
  projectId: DevFeedbackProject,
): Promise<DevFeedbackItem[]> {
  const rows = await database<ItemRow[]>`
    SELECT
      item.id,
      item.queue_position,
      item.current_revision,
      min(revision.title) FILTER (WHERE revision.revision = 1) AS title,
      CASE
        WHEN count(revision.id) = 1 THEN btrim(regexp_replace(min(revision.description), '[[:space:]]+', ' ', 'g'))
        ELSE string_agg(
          CASE
            WHEN revision.revision = 1 THEN 'Original request:'
            ELSE 'Follow-up ' || (revision.revision - 1)::text || ':'
          END || ' ' || btrim(regexp_replace(revision.description, '[[:space:]]+', ' ', 'g')),
          ' | ' ORDER BY revision.revision
        )
      END AS description,
      item.status,
      item.note,
      item.created_at,
      item.updated_at
    FROM dev_feedback_items AS item
    JOIN dev_feedback_revisions AS revision ON revision.item_id = item.id
    WHERE item.installation_id = ${installationId}
      AND item.project_id = ${projectId}
      AND item.status NOT IN ('accepted'::dev_feedback_status, 'closed'::dev_feedback_status)
    GROUP BY item.id
    ORDER BY item.queue_position
    LIMIT 200
  `;
  return rows.map(mapItem);
}

function formatFeedbackHistory(revisions: readonly RevisionDescriptionRow[]): string {
  if (revisions.length === 1) return normalizeFeedbackText(revisions[0]?.description ?? "");
  return revisions
    .map((entry) => {
      const label = entry.revision === 1 ? "Original request" : `Follow-up ${entry.revision - 1}`;
      return `${label}: ${normalizeFeedbackText(entry.description)}`;
    })
    .join(" | ");
}

function normalizeFeedbackText(value: string): string {
  return value.trim().replace(/\s+/g, " ");
}

function hasDatabaseCode(error: unknown, code: string): boolean {
  return (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    (error as { code?: unknown }).code === code
  );
}

function mapItem(row: ItemRow): DevFeedbackItem {
  const queuePosition = Number(row.queue_position);
  if (!Number.isSafeInteger(queuePosition) || queuePosition < 1)
    throw new Error("invalid queue position");
  return {
    id: row.id,
    queuePosition,
    revision: row.current_revision,
    title: row.title,
    description: row.description,
    status: row.status,
    note: row.note,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
  };
}

function mapAdminItem(row: AdminRow): DevFeedbackAdminItem {
  return {
    item: mapItem(row),
    installationId: row.installation_id,
    leaseId: row.lease_id,
    leaseExpiresAt: row.lease_expires_at?.toISOString() ?? null,
    targetCommit: row.target_commit,
    deployedCommit: row.deployed_commit,
    context: row.context,
    screenshotBase64: row.screenshot.toString("base64"),
    screenshotMediaType: row.screenshot_media_type,
    screenshotSha256: row.screenshot_sha256,
    screenshotWidth: row.screenshot_width,
    screenshotHeight: row.screenshot_height,
  };
}
