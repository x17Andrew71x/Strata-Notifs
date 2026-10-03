import { sql } from "drizzle-orm";
import {
  boolean,
  check,
  date,
  integer,
  pgTable,
  primaryKey,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";
import { buildChannel, installations } from "./identity.js";

export const analyticsDailyGlobal = pgTable(
  "analytics_daily_global",
  {
    activeInstallationCount: integer("active_installation_count").notNull(),
    analyticsEventCount: integer("analytics_event_count").notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    gamePressureTotal: integer("game_pressure_total").notNull(),
    isSynthetic: boolean("is_synthetic").notNull(),
    localDate: date("local_date").notNull(),
    notificationEligibleCount: integer("notification_eligible_count").notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.localDate, table.buildChannel, table.isSynthetic] }),
    check(
      "analytics_daily_global_active_installation_count_check",
      sql`"active_installation_count" >= 0`,
    ),
    check("analytics_daily_global_event_count_check", sql`"analytics_event_count" >= 0`),
    check("analytics_daily_global_game_pressure_total_check", sql`"game_pressure_total" >= 0`),
    check(
      "analytics_daily_global_notification_eligible_count_check",
      sql`"notification_eligible_count" >= 0`,
    ),
  ],
);

export const analyticsDailyInstallation = pgTable(
  "analytics_daily_installation",
  {
    analyticsEventCount: integer("analytics_event_count").notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    gamePressureTotal: integer("game_pressure_total").notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    isSynthetic: boolean("is_synthetic").notNull(),
    localDate: date("local_date").notNull(),
    notificationEligibleCount: integer("notification_eligible_count").notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.installationId, table.localDate] }),
    check("analytics_daily_installation_event_count_check", sql`"analytics_event_count" >= 0`),
    check(
      "analytics_daily_installation_game_pressure_total_check",
      sql`"game_pressure_total" >= 0`,
    ),
    check(
      "analytics_daily_installation_notification_eligible_count_check",
      sql`"notification_eligible_count" >= 0`,
    ),
  ],
);

export const analyticsConsentCoverage = pgTable(
  "analytics_consent_coverage",
  {
    buildChannel: buildChannel("build_channel").notNull(),
    isSynthetic: boolean("is_synthetic").notNull(),
    notificationAggregateConsentCount: integer("notification_aggregate_consent_count").notNull(),
    observedDate: date("observed_date").notNull(),
    productAnalyticsConsentCount: integer("product_analytics_consent_count").notNull(),
    totalInstallationCount: integer("total_installation_count").notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.observedDate, table.buildChannel, table.isSynthetic] }),
    check(
      "analytics_consent_coverage_notification_aggregate_count_check",
      sql`"notification_aggregate_consent_count" >= 0`,
    ),
    check(
      "analytics_consent_coverage_product_analytics_count_check",
      sql`"product_analytics_consent_count" >= 0`,
    ),
    check(
      "analytics_consent_coverage_total_installation_count_check",
      sql`"total_installation_count" >= 0`,
    ),
  ],
);

export const analyticsReleaseHealth = pgTable(
  "analytics_release_health",
  {
    analyticsEventCount: integer("analytics_event_count").notNull(),
    appVersion: varchar("app_version", { length: 48 }).notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    failureEventCount: integer("failure_event_count").notNull(),
    isSynthetic: boolean("is_synthetic").notNull(),
    receivedDate: date("received_date").notNull(),
    versionCode: integer("version_code").notNull(),
  },
  (table) => [
    primaryKey({
      columns: [
        table.receivedDate,
        table.appVersion,
        table.versionCode,
        table.buildChannel,
        table.isSynthetic,
      ],
    }),
    check("analytics_release_health_event_count_check", sql`"analytics_event_count" >= 0`),
    check("analytics_release_health_failure_event_count_check", sql`"failure_event_count" >= 0`),
    check("analytics_release_health_version_code_check", sql`"version_code" >= 1`),
  ],
);

export const analyticsNotificationVolume = pgTable(
  "analytics_notification_volume",
  {
    buildChannel: buildChannel("build_channel").notNull(),
    eligibleCount: integer("eligible_count").notNull(),
    isSynthetic: boolean("is_synthetic").notNull(),
    reportingInstallationCount: integer("reporting_installation_count").notNull(),
    utcDate: date("utc_date").notNull(),
  },
  (table) => [
    primaryKey({ columns: [table.utcDate, table.buildChannel, table.isSynthetic] }),
    check("analytics_notification_volume_eligible_count_check", sql`"eligible_count" >= 0`),
    check(
      "analytics_notification_volume_reporting_installation_count_check",
      sql`"reporting_installation_count" >= 0`,
    ),
  ],
);
