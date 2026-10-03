import { sql } from "drizzle-orm";
import {
  check,
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
import { installations } from "./identity.js";

export const outboxJobStatus = pgEnum("outbox_job_status", [
  "pending",
  "leased",
  "succeeded",
  "failed",
]);
export const jobRunStatus = pgEnum("job_run_status", ["running", "succeeded", "failed"]);

export const idempotencyRecords = pgTable(
  "idempotency_records",
  {
    id: uuid("id").primaryKey().notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    operation: varchar("operation", { length: 64 }).notNull(),
    idempotencyKey: uuid("idempotency_key").notNull(),
    requestHash: varchar("request_hash", { length: 64 }).notNull(),
    resultId: uuid("result_id"),
    resultStatus: integer("result_status"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
  },
  (table) => [
    unique("idempotency_records_installation_operation_key").on(
      table.installationId,
      table.operation,
      table.idempotencyKey,
    ),
    index("idempotency_records_expires_at_idx").on(table.expiresAt),
    check("idempotency_records_operation_check", sql`length("operation") > 0`),
    check("idempotency_records_request_hash_check", sql`"request_hash" ~ '^[0-9a-f]{64}$'`),
    check("idempotency_records_expiry_check", sql`"expires_at" > "created_at"`),
  ],
);

export const outboxJobs = pgTable(
  "outbox_jobs",
  {
    id: uuid("id").primaryKey().notNull(),
    jobType: varchar("job_type", { length: 64 }).notNull(),
    dedupeKey: varchar("dedupe_key", { length: 128 }).notNull(),
    status: outboxJobStatus("status").notNull().default("pending"),
    payload: jsonb("payload").notNull(),
    attemptCount: integer("attempt_count").notNull().default(0),
    availableAt: timestamp("available_at", { withTimezone: true }).notNull().defaultNow(),
    leaseExpiresAt: timestamp("lease_expires_at", { withTimezone: true }),
    completedAt: timestamp("completed_at", { withTimezone: true }),
    failureCode: varchar("failure_code", { length: 64 }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("outbox_jobs_type_dedupe_key").on(table.jobType, table.dedupeKey),
    index("outbox_jobs_claim_idx").on(table.status, table.availableAt),
    check("outbox_jobs_type_check", sql`length("job_type") > 0`),
    check("outbox_jobs_dedupe_key_check", sql`length("dedupe_key") > 0`),
    check("outbox_jobs_attempt_count_check", sql`"attempt_count" >= 0`),
    check("outbox_jobs_payload_object_check", sql`jsonb_typeof("payload") = 'object'`),
  ],
);

export const jobRuns = pgTable(
  "job_runs",
  {
    id: uuid("id").primaryKey().notNull(),
    jobName: varchar("job_name", { length: 64 }).notNull(),
    status: jobRunStatus("status").notNull(),
    startedAt: timestamp("started_at", { withTimezone: true }).notNull().defaultNow(),
    finishedAt: timestamp("finished_at", { withTimezone: true }),
    checkpoint: varchar("checkpoint", { length: 128 }),
    failureCode: varchar("failure_code", { length: 64 }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    index("job_runs_job_name_started_idx").on(table.jobName, table.startedAt),
    check("job_runs_name_check", sql`length("job_name") > 0`),
    check(
      "job_runs_finished_status_check",
      sql`("status" = 'running'::job_run_status AND "finished_at" IS NULL) OR ("status" <> 'running'::job_run_status AND "finished_at" IS NOT NULL)`,
    ),
  ],
);

export const schemaMetadata = pgTable(
  "schema_metadata",
  {
    id: uuid("id").primaryKey().notNull(),
    metadataKey: varchar("metadata_key", { length: 64 }).notNull(),
    metadataValue: text("metadata_value").notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("schema_metadata_key_unique").on(table.metadataKey),
    check("schema_metadata_key_check", sql`length("metadata_key") > 0`),
  ],
);
