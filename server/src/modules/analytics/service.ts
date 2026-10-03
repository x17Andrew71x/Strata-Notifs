import type { AccessTokenClaims } from "../../security/tokens.js";
import {
  type AnalyticsEvent,
  AnalyticsRequestError,
  assertPlausibleEventTimes,
} from "./registry.js";
import type { AnalyticsIngestResult, AnalyticsRepository } from "./repository.js";

export type IngestAnalyticsRequest = AccessTokenClaims &
  Readonly<{
    buildChannel: "dev" | "prod";
    events: readonly AnalyticsEvent[];
  }>;

export class AnalyticsService {
  public constructor(private readonly repository: AnalyticsRepository) {}

  public async ingestBatch(
    request: IngestAnalyticsRequest,
  ): Promise<readonly AnalyticsIngestResult[]> {
    assertPlausibleEventTimes(request.events);
    for (const event of request.events) {
      if (
        event.installation_id !== request.installationId ||
        (event.user_id !== undefined && event.user_id !== request.userId) ||
        event.build_channel !== request.buildChannel
      ) {
        throw new AnalyticsRequestError();
      }
    }
    return this.repository.ingestBatch(request);
  }
}
