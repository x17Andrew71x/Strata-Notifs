import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "../../db/client.js";
import type { AccessTokenClaims } from "../../security/tokens.js";
import { ConsentInactiveError } from "../consent/service.js";
import { type AnalyticsEvent, AnalyticsRequestError } from "./registry.js";

export type AnalyticsIngestStatus = "accepted" | "duplicate" | "rejected";

export type AnalyticsIngestResult = Readonly<{
  eventId: string;
  status: AnalyticsIngestStatus;
}>;

export type IngestAnalyticsBatch = AccessTokenClaims &
  Readonly<{
    buildChannel: "dev" | "prod";
    events: readonly AnalyticsEvent[];
  }>;

type InstallationState = Readonly<{
  build_channel: "dev" | "prod";
  is_synthetic: boolean;
}>;

export class AnalyticsRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async ingestBatch(input: IngestAnalyticsBatch): Promise<readonly AnalyticsIngestResult[]> {
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
        throw new AnalyticsRequestError();
      }
      if (
        input.events.some((event) => event.build_channel !== installation.build_channel) ||
        (installation.is_synthetic && installation.build_channel === "prod")
      ) {
        throw new AnalyticsRequestError();
      }

      const [consent] = await transaction<Readonly<{ granted: boolean; scope_version: string }>[]>`
        SELECT granted, scope_version
        FROM consent_records
        WHERE installation_id = ${input.installationId}
          AND scope = 'product_analytics'::consent_scope
        ORDER BY recorded_at DESC, id DESC
        LIMIT 1
      `;
      if (
        consent?.granted !== true ||
        input.events.some((event) => consent.scope_version !== String(event.consent_scope_version))
      ) {
        throw new ConsentInactiveError();
      }

      const results: AnalyticsIngestResult[] = [];
      for (const event of input.events) {
        const properties = transaction.json(event.properties);
        const [inserted] = await transaction<Readonly<{ event_id: string }>[]>`
          INSERT INTO analytics_events (
            id,
            event_id,
            event_name,
            schema_version,
            user_id,
            installation_id,
            session_id,
            occurred_at,
            local_date,
            timezone_offset_minutes,
            app_version,
            version_code,
            build_channel,
            is_synthetic,
            android_api_level,
            device_class,
            locale,
            screen_name,
            consent_scope_version,
            properties
          ) VALUES (
            ${randomUUID()},
            ${event.event_id},
            ${event.event_name},
            ${event.schema_version},
            ${input.userId},
            ${input.installationId},
            ${event.session_id ?? null},
            ${event.occurred_at},
            ${event.local_date},
            ${event.timezone_offset_minutes},
            ${event.app_version},
            ${event.version_code},
            ${installation.build_channel}::build_channel,
            ${installation.is_synthetic},
            ${event.android_api_level},
            ${event.device_class}::device_class,
            ${event.locale},
            ${event.screen_name ?? null}::screen_name,
            ${event.consent_scope_version},
            ${properties}::jsonb
          )
          ON CONFLICT (installation_id, event_id) DO NOTHING
          RETURNING event_id::text
        `;
        if (inserted) {
          results.push({ eventId: event.event_id, status: "accepted" });
          continue;
        }

        const [existing] = await transaction<Readonly<{ is_exact: boolean }>[]>`
          SELECT
            event_name = ${event.event_name}
              AND schema_version = ${event.schema_version}
              AND user_id = ${input.userId}
              AND session_id IS NOT DISTINCT FROM ${event.session_id ?? null}::uuid
              AND occurred_at = ${event.occurred_at}::timestamptz
              AND local_date = ${event.local_date}::date
              AND timezone_offset_minutes = ${event.timezone_offset_minutes}
              AND app_version = ${event.app_version}
              AND version_code = ${event.version_code}
              AND build_channel = ${installation.build_channel}::build_channel
              AND is_synthetic = ${installation.is_synthetic}
              AND android_api_level = ${event.android_api_level}
              AND device_class = ${event.device_class}::device_class
              AND locale = ${event.locale}
              AND screen_name IS NOT DISTINCT FROM ${event.screen_name ?? null}::screen_name
              AND consent_scope_version = ${event.consent_scope_version}
              AND properties = ${properties}::jsonb AS is_exact
          FROM analytics_events
          WHERE installation_id = ${input.installationId}
            AND event_id = ${event.event_id}
        `;
        results.push({
          eventId: event.event_id,
          status: existing?.is_exact === true ? "duplicate" : "rejected",
        });
      }
      return results;
    });
  }
}
