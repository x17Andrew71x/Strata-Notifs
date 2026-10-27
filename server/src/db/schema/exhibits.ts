import { sql } from "drizzle-orm";
import {
  bigint,
  boolean,
  check,
  date,
  index,
  integer,
  pgEnum,
  pgTable,
  primaryKey,
  timestamp,
  unique,
  uniqueIndex,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";
import { buildChannel, installations } from "./identity.js";

export const exhibitKind = pgEnum("exhibit_kind", ["featured", "archive"]);
export const exhibitStatus = pgEnum("exhibit_status", [
  "draft",
  "ready",
  "open",
  "closed",
  "cancelled",
]);

export const exhibits = pgTable(
  "exhibits",
  {
    id: uuid("id").primaryKey().notNull(),
    slug: varchar("slug", { length: 80 }).notNull(),
    title: varchar("title", { length: 80 }).notNull(),
    seasonLabel: varchar("season_label", { length: 48 }).notNull(),
    imagePath: varchar("image_path", { length: 180 }).notNull(),
    kind: exhibitKind("kind").notNull(),
    status: exhibitStatus("status").notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    targetUnits: integer("target_units").notNull(),
    personalMinimumUnits: integer("personal_minimum_units").notNull(),
    dailyContributionCap: integer("daily_contribution_cap").notNull(),
    totalUnits: bigint("total_units", { mode: "number" }).notNull().default(0),
    version: integer("version").notNull().default(1),
    startsAt: timestamp("starts_at", { withTimezone: true }).notNull(),
    endsAt: timestamp("ends_at", { withTimezone: true }),
    placeholderArtwork: boolean("placeholder_artwork").notNull().default(true),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("exhibits_build_slug_key").on(table.buildChannel, table.slug),
    index("exhibits_current_idx").on(table.buildChannel, table.kind, table.status, table.startsAt),
    uniqueIndex("exhibits_one_open_per_kind_idx")
      .on(table.buildChannel, table.kind)
      .where(sql`"status" = 'open'::exhibit_status`),
    check("exhibits_slug_check", sql`"slug" ~ '^[a-z0-9-]{1,80}$'`),
    check(
      "exhibits_image_path_check",
      sql`"image_path" ~ '^/exhibits/[a-z0-9-]{1,128}-[a-f0-9]{12}\\.webp$'`,
    ),
    check("exhibits_target_units_check", sql`"target_units" BETWEEN 1 AND 1000000000`),
    check(
      "exhibits_personal_minimum_check",
      sql`"personal_minimum_units" BETWEEN 1 AND "target_units"`,
    ),
    check("exhibits_daily_cap_check", sql`"daily_contribution_cap" BETWEEN 1 AND 100000`),
    check(
      "exhibits_total_units_check",
      sql`"total_units" BETWEEN 0 AND "target_units"::bigint * 1000000`,
    ),
    check("exhibits_version_check", sql`"version" >= 1`),
    check("exhibits_time_check", sql`"ends_at" IS NULL OR "ends_at" > "starts_at"`),
    check(
      "exhibits_kind_deadline_check",
      sql`("kind" = 'featured'::exhibit_kind AND "ends_at" IS NOT NULL) OR ("kind" = 'archive'::exhibit_kind AND "ends_at" IS NULL)`,
    ),
  ],
);

export const exhibitCohortSnapshots = pgTable(
  "exhibit_cohort_snapshots",
  {
    buildChannel: buildChannel("build_channel").notNull(),
    snapshotDate: date("snapshot_date").notNull(),
    eligibleUserCount: integer("eligible_user_count").notNull(),
    effectiveUserCount: integer("effective_user_count").notNull(),
    multiplierMicros: integer("multiplier_micros").notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    primaryKey({
      columns: [table.buildChannel, table.snapshotDate],
      name: "exhibit_cohort_snapshots_pk",
    }),
    check(
      "exhibit_cohort_snapshots_population_check",
      sql`"eligible_user_count" >= 0 AND "effective_user_count" = greatest(100, least(10000, "eligible_user_count"))`,
    ),
    check(
      "exhibit_cohort_snapshots_multiplier_check",
      sql`"multiplier_micros" = round(10000000000.0 / "effective_user_count")`,
    ),
  ],
);

export const exhibitContributions = pgTable(
  "exhibit_contributions",
  {
    id: uuid("id").primaryKey().notNull(),
    exhibitId: uuid("exhibit_id")
      .notNull()
      .references(() => exhibits.id, { onDelete: "restrict" }),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    idempotencyKey: uuid("idempotency_key").notNull(),
    localDate: date("local_date").notNull(),
    units: integer("units").notNull(),
    cohortSnapshotDate: date("cohort_snapshot_date").notNull(),
    eligibleUserCount: integer("eligible_user_count").notNull(),
    effectiveUserCount: integer("effective_user_count").notNull(),
    multiplierMicros: integer("multiplier_micros").notNull(),
    creditedMicrounits: bigint("credited_microunits", { mode: "number" }).notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("exhibit_contributions_installation_key").on(table.installationId, table.idempotencyKey),
    index("exhibit_contributions_exhibit_installation_idx").on(
      table.exhibitId,
      table.installationId,
    ),
    index("exhibit_contributions_installation_date_idx").on(table.installationId, table.localDate),
    check("exhibit_contributions_units_check", sql`"units" BETWEEN 1 AND 100000`),
    check(
      "exhibit_contributions_population_check",
      sql`"eligible_user_count" >= 0 AND "effective_user_count" = greatest(100, least(10000, "eligible_user_count"))`,
    ),
    check(
      "exhibit_contributions_multiplier_check",
      sql`"multiplier_micros" = round(10000000000.0 / "effective_user_count")`,
    ),
    check(
      "exhibit_contributions_credit_check",
      sql`"credited_microunits" = "units"::bigint * "multiplier_micros"`,
    ),
  ],
);

export const exhibitUnlocks = pgTable(
  "exhibit_unlocks",
  {
    id: uuid("id").primaryKey().notNull(),
    exhibitId: uuid("exhibit_id")
      .notNull()
      .references(() => exhibits.id, { onDelete: "restrict" }),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    unlockedAt: timestamp("unlocked_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    unique("exhibit_unlocks_exhibit_installation_key").on(table.exhibitId, table.installationId),
    index("exhibit_unlocks_installation_idx").on(table.installationId, table.unlockedAt),
  ],
);
