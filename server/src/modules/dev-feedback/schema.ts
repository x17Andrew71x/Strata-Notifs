import { sql } from "drizzle-orm";
import {
  bigint,
  check,
  customType,
  index,
  integer,
  jsonb,
  pgEnum,
  pgTable,
  text,
  timestamp,
  unique,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";
import { installations } from "../../db/schema/identity.js";

export const devFeedbackStatus = pgEnum("dev_feedback_status", [
  "queued",
  "in_progress",
  "blocked",
  "failed",
  "ready_for_qa",
  "accepted",
  "closed",
]);

const bytea = customType<{ data: Buffer; driverData: Buffer }>({
  dataType: () => "bytea",
});

export const devFeedbackItems = pgTable(
  "dev_feedback_items",
  {
    id: uuid("id").primaryKey().notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    projectId: varchar("project_id", { length: 32 }).notNull().default("afterchime"),
    queuePosition: bigint("queue_position", { mode: "number" })
      .notNull()
      .generatedAlwaysAsIdentity(),
    currentRevision: integer("current_revision").notNull().default(1),
    title: varchar("title", { length: 120 }).notNull(),
    description: text("description").notNull(),
    status: devFeedbackStatus("status").notNull().default("queued"),
    note: varchar("note", { length: 600 }).notNull().default("Queued in submission order."),
    leaseId: uuid("lease_id"),
    leaseRevision: integer("lease_revision"),
    leaseExpiresAt: timestamp("lease_expires_at", { withTimezone: true }),
    targetCommit: varchar("target_commit", { length: 40 }),
    deployedCommit: varchar("deployed_commit", { length: 40 }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
    acceptedAt: timestamp("accepted_at", { withTimezone: true }),
  },
  (table) => [
    unique("dev_feedback_items_queue_position_unique").on(table.queuePosition),
    index("dev_feedback_items_status_queue_idx").on(table.status, table.queuePosition),
    index("dev_feedback_items_project_status_queue_idx").on(
      table.projectId,
      table.status,
      table.queuePosition,
    ),
    index("dev_feedback_items_installation_status_queue_idx").on(
      table.installationId,
      table.status,
      table.queuePosition,
    ),
    check("dev_feedback_items_revision_check", sql`"current_revision" >= 1`),
    check("dev_feedback_items_title_check", sql`length("title") BETWEEN 1 AND 120`),
    check("dev_feedback_items_description_check", sql`length("description") BETWEEN 1 AND 4000`),
    check("dev_feedback_items_note_check", sql`length("note") <= 600`),
    check(
      "dev_feedback_items_project_check",
      sql`"project_id" IN ('afterchime', 'decree', 'please-just-sleep')`,
    ),
    check(
      "dev_feedback_items_lease_check",
      sql`(
        "status" = 'in_progress'::dev_feedback_status
        AND "lease_id" IS NOT NULL
        AND "lease_revision" = "current_revision"
        AND "lease_expires_at" IS NOT NULL
      ) OR (
        "status" <> 'in_progress'::dev_feedback_status
        AND "lease_id" IS NULL
        AND "lease_revision" IS NULL
        AND "lease_expires_at" IS NULL
      )`,
    ),
    check(
      "dev_feedback_items_target_commit_check",
      sql`"target_commit" IS NULL OR "target_commit" ~ '^[0-9a-f]{40}$'`,
    ),
    check(
      "dev_feedback_items_deployed_commit_check",
      sql`"deployed_commit" IS NULL OR "deployed_commit" ~ '^[0-9a-f]{40}$'`,
    ),
    check(
      "dev_feedback_items_accepted_check",
      sql`("status" = 'accepted'::dev_feedback_status) = ("accepted_at" IS NOT NULL)`,
    ),
  ],
);

export const devFeedbackRevisions = pgTable(
  "dev_feedback_revisions",
  {
    id: uuid("id").primaryKey().notNull(),
    itemId: uuid("item_id")
      .notNull()
      .references(() => devFeedbackItems.id, { onDelete: "cascade" }),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    revision: integer("revision").notNull(),
    idempotencyKey: uuid("idempotency_key").notNull(),
    requestHash: varchar("request_hash", { length: 64 }).notNull(),
    title: varchar("title", { length: 120 }).notNull(),
    description: text("description").notNull(),
    context: jsonb("context").notNull(),
    screenshot: bytea("screenshot").notNull(),
    screenshotMediaType: varchar("screenshot_media_type", { length: 32 }).notNull(),
    screenshotSha256: varchar("screenshot_sha256", { length: 64 }).notNull(),
    screenshotWidth: integer("screenshot_width").notNull(),
    screenshotHeight: integer("screenshot_height").notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("dev_feedback_revisions_item_revision_unique").on(table.itemId, table.revision),
    unique("dev_feedback_revisions_installation_idempotency_unique").on(
      table.installationId,
      table.idempotencyKey,
    ),
    index("dev_feedback_revisions_item_created_idx").on(table.itemId, table.createdAt),
    check("dev_feedback_revisions_revision_check", sql`"revision" >= 1`),
    check("dev_feedback_revisions_title_check", sql`length("title") BETWEEN 1 AND 120`),
    check(
      "dev_feedback_revisions_description_check",
      sql`length("description") BETWEEN 1 AND 4000`,
    ),
    check("dev_feedback_revisions_context_check", sql`jsonb_typeof("context") = 'object'`),
    check("dev_feedback_revisions_media_type_check", sql`"screenshot_media_type" = 'image/webp'`),
    check("dev_feedback_revisions_hash_check", sql`"screenshot_sha256" ~ '^[0-9a-f]{64}$'`),
    check("dev_feedback_revisions_request_hash_check", sql`"request_hash" ~ '^[0-9a-f]{64}$'`),
    check(
      "dev_feedback_revisions_screenshot_size_check",
      sql`octet_length("screenshot") BETWEEN 1 AND 786432`,
    ),
    check(
      "dev_feedback_revisions_dimensions_check",
      sql`"screenshot_width" BETWEEN 1 AND 16384 AND "screenshot_height" BETWEEN 1 AND 16384`,
    ),
  ],
);
