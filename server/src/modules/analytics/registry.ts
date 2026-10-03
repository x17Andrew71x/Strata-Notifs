import { z } from "zod";

const semanticVersion =
  /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

const baseEvent = z
  .object({
    android_api_level: z.number().int().min(26).max(100),
    app_version: z.string().max(48).regex(semanticVersion),
    build_channel: z.enum(["dev", "prod"]),
    consent_scope_version: z.number().int().min(1).max(1000),
    device_class: z.enum(["phone", "tablet", "foldable"]),
    event_id: z.string().uuid(),
    event_name: z.string().regex(/^[a-z][a-z_]{2,63}$/),
    installation_id: z.string().uuid(),
    locale: z
      .string()
      .max(16)
      .regex(/^[a-z]{2,3}(?:-[A-Z]{2})?$/),
    local_date: z.iso.date(),
    occurred_at: z.iso.datetime({ offset: true }),
    properties: z.object({}).passthrough(),
    schema_version: z.literal(1),
    screen_name: z
      .enum(["onboarding", "privacy", "settings", "today", "museum", "community", "more"])
      .optional(),
    session_id: z.string().uuid().optional(),
    timezone_offset_minutes: z.number().int().min(-840).max(840),
    user_id: z.string().uuid().optional(),
    version_code: z.number().int().min(1).max(2_147_483_647),
  })
  .strict();

const entryPoint = <T extends readonly [string, ...string[]]>(values: T) =>
  z.object({ entry_point: z.enum(values) }).strict();

export const analyticsEvent = z.union([
  baseEvent.extend({
    event_name: z.literal("installation_created"),
    properties: entryPoint(["first_run", "online_mode_enabled"]),
  }),
  baseEvent.extend({
    event_name: z.literal("app_opened"),
    properties: z.object({ launch_type: z.enum(["cold", "warm"]) }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("session_started"),
    properties: entryPoint(["cold_start", "foreground_return"]),
  }),
  baseEvent.extend({
    event_name: z.literal("session_ended"),
    properties: z.object({ duration_seconds: z.number().int().min(0).max(86_400) }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("app_backgrounded"),
    properties: z.object({ duration_seconds: z.number().int().min(0).max(86_400) }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("app_updated"),
    properties: z
      .object({ previous_app_version: z.string().max(48).regex(semanticVersion) })
      .strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("auth_session_created"),
    properties: z.object({ auth_method: z.literal("anonymous") }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("auth_session_refreshed"),
    properties: z.object({ outcome: z.literal("rotated") }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("online_mode_disabled"),
    properties: z.object({ reason: z.literal("user_request") }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("onboarding_started"),
    properties: entryPoint(["first_run", "resume"]),
  }),
  baseEvent.extend({
    event_name: z.literal("onboarding_step_viewed"),
    properties: z
      .object({
        step: z.enum(["welcome", "privacy", "notification_access", "analytics_consent", "ready"]),
        step_index: z.number().int().min(1).max(5),
      })
      .strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("onboarding_completed"),
    properties: z.object({ duration_seconds: z.number().int().min(0).max(3_600) }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("notification_access_prompted"),
    properties: entryPoint(["onboarding", "settings"]),
  }),
  baseEvent.extend({
    event_name: z.literal("notification_access_result"),
    properties: z.object({ result: z.enum(["granted", "denied", "unavailable"]) }).strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("analytics_consent_changed"),
    properties: z
      .object({
        consent_version: z.number().int().min(1).max(1000),
        enabled: z.boolean(),
      })
      .strict(),
  }),
  baseEvent.extend({
    event_name: z.literal("notification_aggregate_consent_changed"),
    properties: z
      .object({
        consent_version: z.number().int().min(1).max(1000),
        enabled: z.boolean(),
      })
      .strict(),
  }),
]);

export const analyticsBatch = z
  .object({
    events: z.array(analyticsEvent).min(1).max(50),
  })
  .strict();

export type AnalyticsEvent = z.output<typeof analyticsEvent>;

export class AnalyticsRequestError extends Error {
  public readonly statusCode = 400;

  public constructor() {
    super("analytics_request_invalid");
    this.name = "AnalyticsRequestError";
  }
}

export function assertPlausibleEventTimes(events: readonly AnalyticsEvent[]): void {
  const latestPermitted = Date.now() + 15 * 60 * 1000;
  for (const event of events) {
    const occurredAt = Date.parse(event.occurred_at);
    if (!Number.isFinite(occurredAt) || occurredAt > latestPermitted) {
      throw new AnalyticsRequestError();
    }
    const localDate = new Date(occurredAt + event.timezone_offset_minutes * 60 * 1000)
      .toISOString()
      .slice(0, 10);
    if (localDate !== event.local_date) {
      throw new AnalyticsRequestError();
    }
  }
}
