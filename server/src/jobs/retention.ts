import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "../db/client.js";

export type RetentionRunResult = Readonly<{
  attemptCount: number;
  checkpoint: string | null;
  failureCode: string | null;
  status: "failed" | "succeeded";
}>;

type RetentionRunRow = Readonly<{
  checkpoint: string | null;
  failure_code: string | null;
  status: string;
}>;

export type RetentionRunOptions = Readonly<{
  batchSize?: number;
  leaseSeconds?: number;
  maxAttempts?: number;
  workerId?: string;
}>;

const defaultBatchSize = 500;
const defaultLeaseSeconds = 120;
const defaultMaxAttempts = 3;
const workerIdPattern = /^[a-z0-9][a-z0-9_-]{2,63}$/;

function validateOptions(options: RetentionRunOptions): Required<RetentionRunOptions> {
  const batchSize = options.batchSize ?? defaultBatchSize;
  const leaseSeconds = options.leaseSeconds ?? defaultLeaseSeconds;
  const maxAttempts = options.maxAttempts ?? defaultMaxAttempts;
  const workerId = options.workerId ?? `retention-${process.pid}`;

  if (
    !Number.isInteger(batchSize) ||
    batchSize < 1 ||
    batchSize > 1_000 ||
    !Number.isInteger(leaseSeconds) ||
    leaseSeconds < 1 ||
    leaseSeconds > 300 ||
    !Number.isInteger(maxAttempts) ||
    maxAttempts < 1 ||
    maxAttempts > defaultMaxAttempts ||
    !workerIdPattern.test(workerId)
  ) {
    throw new Error("retention runner configuration is invalid");
  }

  return { batchSize, leaseSeconds, maxAttempts, workerId };
}

export async function runRetention(
  database: DatabaseClient,
  options: RetentionRunOptions = {},
): Promise<RetentionRunResult> {
  const { batchSize, leaseSeconds, maxAttempts, workerId } = validateOptions(options);

  for (let attemptCount = 1; attemptCount <= maxAttempts; attemptCount += 1) {
    try {
      const [row] = await database<RetentionRunRow[]>`
        SELECT status, checkpoint, failure_code
        FROM public.afterchime_run_retention(
          ${randomUUID()}::uuid,
          ${workerId}::varchar,
          ${leaseSeconds}::integer,
          ${batchSize}::integer
        )
      `;
      if (row?.status === "succeeded" || row?.status === "failed") {
        return {
          attemptCount,
          checkpoint: row.checkpoint,
          failureCode: row.failure_code,
          status: row.status,
        };
      }
    } catch {
      // The database function records deterministic failures itself. Only an unavailable
      // connection reaches this bounded retry path, where no durable run record is possible.
    }
  }

  return {
    attemptCount: maxAttempts,
    checkpoint: null,
    failureCode: "retention_unavailable",
    status: "failed",
  };
}
