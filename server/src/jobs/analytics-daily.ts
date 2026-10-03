import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "../db/client.js";

export type AnalyticsDailyRunResult = Readonly<{
  attemptCount: number;
  checkpoint: string | null;
  failureCode: string | null;
  status: "failed" | "succeeded";
}>;

type AnalyticsDailyRunRow = Readonly<{
  checkpoint: string | null;
  failure_code: string | null;
  status: string;
}>;

export type AnalyticsDailyRunOptions = Readonly<{
  leaseSeconds?: number;
  maxAttempts?: number;
  workerId?: string;
}>;

const defaultLeaseSeconds = 120;
const defaultMaxAttempts = 3;
const workerIdPattern = /^[a-z0-9][a-z0-9_-]{2,63}$/;

function validateOptions(options: AnalyticsDailyRunOptions): Required<AnalyticsDailyRunOptions> {
  const leaseSeconds = options.leaseSeconds ?? defaultLeaseSeconds;
  const maxAttempts = options.maxAttempts ?? defaultMaxAttempts;
  const workerId = options.workerId ?? `analytics-daily-${process.pid}`;

  if (
    !Number.isInteger(leaseSeconds) ||
    leaseSeconds < 1 ||
    leaseSeconds > 300 ||
    !Number.isInteger(maxAttempts) ||
    maxAttempts < 1 ||
    maxAttempts > defaultMaxAttempts ||
    !workerIdPattern.test(workerId)
  ) {
    throw new Error("analytics daily runner configuration is invalid");
  }

  return { leaseSeconds, maxAttempts, workerId };
}

export async function runAnalyticsDaily(
  database: DatabaseClient,
  options: AnalyticsDailyRunOptions = {},
): Promise<AnalyticsDailyRunResult> {
  const { leaseSeconds, maxAttempts, workerId } = validateOptions(options);

  for (let attemptCount = 1; attemptCount <= maxAttempts; attemptCount += 1) {
    try {
      const [row] = await database<AnalyticsDailyRunRow[]>`
        SELECT status, checkpoint, failure_code
        FROM afterchime_run_analytics_daily(
          ${randomUUID()}::uuid,
          ${workerId}::varchar,
          ${leaseSeconds}::integer
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
    failureCode: "analytics_daily_unavailable",
    status: "failed",
  };
}
