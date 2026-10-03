import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "../../db/client.js";
import type { AccessTokenClaims } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";

export type NotificationCategory =
  | "alarm"
  | "call"
  | "email"
  | "event"
  | "message"
  | "navigation"
  | "other"
  | "progress"
  | "reminder"
  | "social"
  | "transport"
  | "workout";

export type DailyNotificationAggregate = Readonly<{
  categoryCounts: Readonly<Partial<Record<NotificationCategory, number | undefined>>>;
  consentScopeVersion: string;
  eligibleCount: number;
  gamePressure: number;
  hourlyBuckets: readonly Readonly<{ count: number; hour: number }>[];
  localDate: string;
  observationCompleteness: number;
  revision: number;
  rulesVersion: number;
  timezoneOffsetMinutes: number;
}>;

export type NotificationAggregateResult = Readonly<{
  localDate: string;
  revision: number;
  status: "accepted" | "duplicate";
}>;

export type IngestNotificationAggregate = AccessTokenClaims &
  Readonly<{
    aggregate: DailyNotificationAggregate;
    buildChannel: "dev" | "prod";
  }>;

type InstallationState = Readonly<{
  build_channel: "dev" | "prod";
  is_synthetic: boolean;
}>;

type StoredAggregate = Readonly<{
  build_channel: "dev" | "prod";
  category_counts: unknown;
  eligible_count: number;
  game_pressure: number;
  hourly_buckets: unknown;
  is_synthetic: boolean;
  observation_completeness: number;
  revision: number;
  rules_version: number;
  timezone_offset_minutes: number;
}>;

export class NotificationAggregateRequestError extends Error {
  public readonly statusCode = 400;

  public constructor() {
    super("notification_aggregate_request_invalid");
    this.name = "NotificationAggregateRequestError";
  }
}

export class NotificationAggregateRevisionConflictError extends Error {
  public constructor() {
    super("notification_aggregate_revision_conflict");
    this.name = "NotificationAggregateRevisionConflictError";
  }
}

export class NotificationAggregateRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async ingestDaily(
    input: IngestNotificationAggregate,
  ): Promise<NotificationAggregateResult> {
    return this.database.begin(async (transaction) => {
      await transaction`SELECT set_config('afterchime.user_id', ${input.userId}, true)`;
      await transaction`SELECT set_config('afterchime.installation_id', ${input.installationId}, true)`;

      const [installation] = await transaction<InstallationState[]>`
        SELECT build_channel::text, is_synthetic
        FROM installations
        WHERE id = ${input.installationId}
          AND user_id = ${input.userId}
      `;
      if (!installation || installation.build_channel !== input.buildChannel) {
        throw new NotificationAggregateRequestError();
      }
      if (installation.is_synthetic && installation.build_channel === "prod") {
        throw new NotificationAggregateRequestError();
      }

      const [consent] = await transaction<Readonly<{ granted: boolean; scope_version: string }>[]>`
        SELECT granted, scope_version
        FROM consent_records
        WHERE installation_id = ${input.installationId}
          AND scope = 'notification_aggregates'::consent_scope
        ORDER BY recorded_at DESC, id DESC
        LIMIT 1
      `;
      if (
        consent?.granted !== true ||
        consent.scope_version !== input.aggregate.consentScopeVersion
      ) {
        throw new ConsentInactiveError();
      }

      const categoryCounts = transaction.json(input.aggregate.categoryCounts);
      const hourlyBuckets = transaction.json(input.aggregate.hourlyBuckets);
      const [inserted] = await transaction<Readonly<{ revision: number }>[]>`
        INSERT INTO daily_notification_aggregates (
          id,
          installation_id,
          local_date,
          timezone_offset_minutes,
          revision,
          rules_version,
          eligible_count,
          category_counts,
          hourly_buckets,
          game_pressure,
          observation_completeness,
          build_channel,
          is_synthetic
        ) VALUES (
          ${randomUUID()},
          ${input.installationId},
          ${input.aggregate.localDate}::date,
          ${input.aggregate.timezoneOffsetMinutes},
          ${input.aggregate.revision},
          ${input.aggregate.rulesVersion},
          ${input.aggregate.eligibleCount},
          ${categoryCounts}::jsonb,
          ${hourlyBuckets}::jsonb,
          ${input.aggregate.gamePressure},
          ${input.aggregate.observationCompleteness},
          ${installation.build_channel}::build_channel,
          ${installation.is_synthetic}
        )
        ON CONFLICT (installation_id, local_date) DO NOTHING
        RETURNING revision
      `;
      if (inserted) {
        return accepted(input.aggregate);
      }

      const [existing] = await transaction<StoredAggregate[]>`
        SELECT
          revision,
          rules_version,
          eligible_count,
          category_counts,
          hourly_buckets,
          game_pressure,
          observation_completeness,
          timezone_offset_minutes,
          build_channel::text,
          is_synthetic
        FROM daily_notification_aggregates
        WHERE installation_id = ${input.installationId}
          AND local_date = ${input.aggregate.localDate}::date
        FOR UPDATE
      `;
      if (!existing) {
        throw new NotificationAggregateRequestError();
      }
      if (existing.revision === input.aggregate.revision && isExact(existing, input.aggregate)) {
        return duplicate(input.aggregate);
      }
      if (input.aggregate.revision <= existing.revision) {
        throw new NotificationAggregateRevisionConflictError();
      }

      await transaction`
        UPDATE daily_notification_aggregates
        SET
          timezone_offset_minutes = ${input.aggregate.timezoneOffsetMinutes},
          revision = ${input.aggregate.revision},
          rules_version = ${input.aggregate.rulesVersion},
          eligible_count = ${input.aggregate.eligibleCount},
          category_counts = ${categoryCounts}::jsonb,
          hourly_buckets = ${hourlyBuckets}::jsonb,
          game_pressure = ${input.aggregate.gamePressure},
          observation_completeness = ${input.aggregate.observationCompleteness},
          build_channel = ${installation.build_channel}::build_channel,
          is_synthetic = ${installation.is_synthetic},
          updated_at = now()
        WHERE installation_id = ${input.installationId}
          AND local_date = ${input.aggregate.localDate}::date
      `;
      return accepted(input.aggregate);
    });
  }
}

function accepted(aggregate: DailyNotificationAggregate): NotificationAggregateResult {
  return {
    localDate: aggregate.localDate,
    revision: aggregate.revision,
    status: "accepted",
  };
}

function duplicate(aggregate: DailyNotificationAggregate): NotificationAggregateResult {
  return {
    localDate: aggregate.localDate,
    revision: aggregate.revision,
    status: "duplicate",
  };
}

function isExact(existing: StoredAggregate, input: DailyNotificationAggregate): boolean {
  return (
    existing.rules_version === input.rulesVersion &&
    existing.eligible_count === input.eligibleCount &&
    existing.game_pressure === input.gamePressure &&
    existing.observation_completeness === input.observationCompleteness &&
    existing.timezone_offset_minutes === input.timezoneOffsetMinutes &&
    stableJson(existing.category_counts) === stableJson(input.categoryCounts) &&
    stableJson(existing.hourly_buckets) === stableJson(input.hourlyBuckets)
  );
}

function stableJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableJson).join(",")}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>).sort(([left], [right]) =>
    left.localeCompare(right),
  );
  return `{${entries.map(([key, entry]) => `${JSON.stringify(key)}:${stableJson(entry)}`).join(",")}}`;
}
