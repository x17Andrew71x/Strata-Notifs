import { sql } from "drizzle-orm";
import {
  boolean,
  check,
  index,
  pgEnum,
  pgTable,
  timestamp,
  unique,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";

export const buildChannel = pgEnum("build_channel", ["dev", "prod"]);
export const consentScope = pgEnum("consent_scope", [
  "essential_online",
  "product_analytics",
  "notification_aggregates",
]);

export const users = pgTable(
  "users",
  {
    id: uuid("id").primaryKey().notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    isSynthetic: boolean("is_synthetic").notNull().default(false),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow(),
  },
  () => [
    check(
      "users_synthetic_build_check",
      sql`NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel)`,
    ),
  ],
);

export const installations = pgTable(
  "installations",
  {
    id: uuid("id").primaryKey().notNull(),
    userId: uuid("user_id")
      .notNull()
      .references(() => users.id, { onDelete: "cascade" }),
    appVersion: varchar("app_version", { length: 48 }).notNull(),
    buildChannel: buildChannel("build_channel").notNull(),
    isSynthetic: boolean("is_synthetic").notNull().default(false),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    lastSeenAt: timestamp("last_seen_at", { withTimezone: true }).notNull().defaultNow(),
  },
  () => [
    index("installations_user_id_idx").on(sql`"user_id"`),
    check(
      "installations_synthetic_build_check",
      sql`NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel)`,
    ),
  ],
);

export const authRefreshTokens = pgTable(
  "auth_refresh_tokens",
  {
    id: uuid("id").primaryKey().notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    familyId: uuid("family_id").notNull(),
    tokenHash: varchar("token_hash", { length: 128 }).notNull(),
    replacedById: uuid("replaced_by_id"),
    issuedAt: timestamp("issued_at", { withTimezone: true }).notNull().defaultNow(),
    expiresAt: timestamp("expires_at", { withTimezone: true }).notNull(),
    revokedAt: timestamp("revoked_at", { withTimezone: true }),
    replayedAt: timestamp("replayed_at", { withTimezone: true }),
  },
  (table) => [
    unique("auth_refresh_tokens_token_hash_key").on(table.tokenHash),
    index("auth_refresh_tokens_family_id_idx").on(table.installationId, table.familyId),
    index("auth_refresh_tokens_expires_at_idx").on(table.expiresAt),
    check("auth_refresh_tokens_expiry_check", sql`"expires_at" > "issued_at"`),
  ],
);

export const consentRecords = pgTable(
  "consent_records",
  {
    id: uuid("id").primaryKey().notNull(),
    installationId: uuid("installation_id")
      .notNull()
      .references(() => installations.id, { onDelete: "cascade" }),
    scope: consentScope("scope").notNull(),
    scopeVersion: varchar("scope_version", { length: 32 }).notNull(),
    granted: boolean("granted").notNull(),
    recordedAt: timestamp("recorded_at", { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => [
    index("consent_records_installation_scope_recorded_idx").on(
      table.installationId,
      table.scope,
      table.recordedAt,
    ),
    check("consent_records_scope_version_check", sql`length("scope_version") > 0`),
  ],
);
