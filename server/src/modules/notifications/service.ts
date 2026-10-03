import type {
  IngestNotificationAggregate,
  NotificationAggregateRepository,
  NotificationAggregateResult,
} from "./repository.js";

export class NotificationAggregateService {
  public constructor(private readonly repository: NotificationAggregateRepository) {}

  public async ingestDaily(
    request: IngestNotificationAggregate,
  ): Promise<NotificationAggregateResult> {
    return this.repository.ingestDaily(request);
  }
}
