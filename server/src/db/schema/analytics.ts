import { sql } from "drizzle-orm";
import {
  boolean,
  check,
  date,
  index,
  integer,
  jsonb,
  pgEnum,
  pgTable,
  timestamp,
  unique,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";
import { buildChannel, installations, users } from "./identity.js";

export const deviceClass = pgEnum("device_class", ["phone", "tablet", "foldable"]);
export const screenName = pgEnum("screen_name", [
  "onboarding",
  "privacy",
  "settings",
  "today",
  "museum",
  "community",
  "more",
]);

export const analyticsEvents = pgTable(
  "analytics_events",
  {
    id: uuid("id").primaryKey().notNull(),
    eventId: uuid("event_id").notNull(),
    eventName: varchar("event_name", { length: 64 }).notNull(),
    schemaVersion: integer("schema_version").notNull(),
    userId: uuid("user_id").references(() => users.id, { onDelete: "set null" }),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    sessionId: uuid("session_id"),
    occurredAt: timestamp("occurred_at", { withTimezone: true }).notNull(),
    receivedAt: timestamp("received_at", { withTimezone: true }).notNull().defaultNow(),
    localDate: date("local_date").notNull(),
    timezoneOffsetMinutes: integer("timezone_offset_minutes").notNull(),
    appVersion: varchar("app_version", { length: 48 }).notNull(),
    versionCode: integer("version_code").notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    isSynthetic: boolean("is_synthetic").notNull().default(false),
    androidApiLevel: integer("android_api_level").notNull(),
    deviceClass: deviceClass("device_class").notNull(),
    locale: varchar("locale", { length: 16 }).notNull(),
    screenName: screenName("screen_name"),
    consentScopeVersion: integer("consent_scope_version").notNull(),
    properties: jsonb("properties").notNull(),
  },
  (table) => [
    unique("analytics_events_installation_event_key").on(table.installationId, table.eventId),
    index("analytics_events_installation_occurred_idx").on(table.installationId, table.occurredAt),
    index("analytics_events_received_at_idx").on(table.receivedAt),
    check("analytics_events_schema_version_check", sql`"schema_version" = 1`),
    check(
      "analytics_events_timezone_offset_check",
      sql`"timezone_offset_minutes" BETWEEN -840 AND 840`,
    ),
    check("analytics_events_version_code_check", sql`"version_code" BETWEEN 1 AND 2147483647`),
    check("analytics_events_api_level_check", sql`"android_api_level" BETWEEN 26 AND 100`),
    check(
      "analytics_events_consent_version_check",
      sql`"consent_scope_version" BETWEEN 1 AND 1000`,
    ),
    check("analytics_events_properties_object_check", sql`jsonb_typeof("properties") = 'object'`),
    check(
      "analytics_events_synthetic_build_check",
      sql`NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel)`,
    ),
  ],
);

export const dailyNotificationAggregates = pgTable(
  "daily_notification_aggregates",
  {
    id: uuid("id").primaryKey().notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    localDate: date("local_date").notNull(),
    timezoneOffsetMinutes: integer("timezone_offset_minutes").notNull(),
    revision: integer("revision").notNull(),
    rulesVersion: integer("rules_version").notNull(),
    eligibleCount: integer("eligible_count").notNull(),
    categoryCounts: jsonb("category_counts").notNull(),
    hourlyBuckets: jsonb("hourly_buckets").notNull(),
    gamePressure: integer("game_pressure").notNull(),
    observationCompleteness: integer("observation_completeness").notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    isSynthetic: boolean("is_synthetic").notNull().default(false),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("daily_notification_aggregates_installation_date_key").on(
      table.installationId,
      table.localDate,
    ),
    index("daily_notification_aggregates_local_date_idx").on(table.localDate),
    check(
      "daily_notification_aggregates_timezone_offset_check",
      sql`"timezone_offset_minutes" BETWEEN -840 AND 840`,
    ),
    check("daily_notification_aggregates_revision_check", sql`"revision" > 0`),
    check("daily_notification_aggregates_rules_version_check", sql`"rules_version" > 0`),
    check("daily_notification_aggregates_eligible_count_check", sql`"eligible_count" >= 0`),
    check(
      "daily_notification_aggregates_game_pressure_check",
      sql`"game_pressure" BETWEEN 0 AND 100`,
    ),
    check(
      "daily_notification_aggregates_completeness_check",
      sql`"observation_completeness" BETWEEN 0 AND 100`,
    ),
    check(
      "daily_notification_aggregates_category_counts_object_check",
      sql`jsonb_typeof("category_counts") = 'object'`,
    ),
    check(
      "daily_notification_aggregates_hourly_buckets_array_check",
      sql`jsonb_typeof("hourly_buckets") = 'array' AND jsonb_array_length("hourly_buckets") <= 24`,
    ),
    check(
      "daily_notification_aggregates_synthetic_build_check",
      sql`NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel)`,
    ),
  ],
);
