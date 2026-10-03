CREATE TYPE "public"."device_class" AS ENUM('phone', 'tablet', 'foldable');--> statement-breakpoint
CREATE TYPE "public"."screen_name" AS ENUM('onboarding', 'privacy', 'settings', 'today', 'museum', 'community', 'more');--> statement-breakpoint
CREATE TYPE "public"."build_channel" AS ENUM('dev', 'prod');--> statement-breakpoint
CREATE TYPE "public"."consent_scope" AS ENUM('essential_online', 'product_analytics', 'notification_aggregates');--> statement-breakpoint
CREATE TYPE "public"."job_run_status" AS ENUM('running', 'succeeded', 'failed');--> statement-breakpoint
CREATE TYPE "public"."outbox_job_status" AS ENUM('pending', 'leased', 'succeeded', 'failed');--> statement-breakpoint
CREATE TABLE "analytics_events" (
	"id" uuid PRIMARY KEY NOT NULL,
	"event_id" uuid NOT NULL,
	"event_name" varchar(64) NOT NULL,
	"schema_version" integer NOT NULL,
	"user_id" uuid,
	"installation_id" uuid NOT NULL,
	"session_id" uuid,
	"occurred_at" timestamp with time zone NOT NULL,
	"received_at" timestamp with time zone DEFAULT now() NOT NULL,
	"local_date" date NOT NULL,
	"timezone_offset_minutes" integer NOT NULL,
	"app_version" varchar(48) NOT NULL,
	"version_code" integer NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"is_synthetic" boolean DEFAULT false NOT NULL,
	"android_api_level" integer NOT NULL,
	"device_class" "device_class" NOT NULL,
	"locale" varchar(16) NOT NULL,
	"screen_name" "screen_name",
	"consent_scope_version" integer NOT NULL,
	"properties" jsonb NOT NULL,
	CONSTRAINT "analytics_events_installation_event_key" UNIQUE("installation_id","event_id"),
	CONSTRAINT "analytics_events_schema_version_check" CHECK ("schema_version" = 1),
	CONSTRAINT "analytics_events_timezone_offset_check" CHECK ("timezone_offset_minutes" BETWEEN -840 AND 840),
	CONSTRAINT "analytics_events_version_code_check" CHECK ("version_code" BETWEEN 1 AND 2147483647),
	CONSTRAINT "analytics_events_api_level_check" CHECK ("android_api_level" BETWEEN 26 AND 100),
	CONSTRAINT "analytics_events_consent_version_check" CHECK ("consent_scope_version" BETWEEN 1 AND 1000),
	CONSTRAINT "analytics_events_properties_object_check" CHECK (jsonb_typeof("properties") = 'object'),
	CONSTRAINT "analytics_events_synthetic_build_check" CHECK (NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel))
);
--> statement-breakpoint
CREATE TABLE "daily_notification_aggregates" (
	"id" uuid PRIMARY KEY NOT NULL,
	"installation_id" uuid NOT NULL,
	"local_date" date NOT NULL,
	"timezone_offset_minutes" integer NOT NULL,
	"revision" integer NOT NULL,
	"rules_version" integer NOT NULL,
	"eligible_count" integer NOT NULL,
	"category_counts" jsonb NOT NULL,
	"hourly_buckets" jsonb NOT NULL,
	"game_pressure" integer NOT NULL,
	"observation_completeness" integer NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"is_synthetic" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "daily_notification_aggregates_installation_date_key" UNIQUE("installation_id","local_date"),
	CONSTRAINT "daily_notification_aggregates_timezone_offset_check" CHECK ("timezone_offset_minutes" BETWEEN -840 AND 840),
	CONSTRAINT "daily_notification_aggregates_revision_check" CHECK ("revision" > 0),
	CONSTRAINT "daily_notification_aggregates_rules_version_check" CHECK ("rules_version" > 0),
	CONSTRAINT "daily_notification_aggregates_eligible_count_check" CHECK ("eligible_count" >= 0),
	CONSTRAINT "daily_notification_aggregates_game_pressure_check" CHECK ("game_pressure" BETWEEN 0 AND 100),
	CONSTRAINT "daily_notification_aggregates_completeness_check" CHECK ("observation_completeness" BETWEEN 0 AND 100),
	CONSTRAINT "daily_notification_aggregates_category_counts_object_check" CHECK (jsonb_typeof("category_counts") = 'object'),
	CONSTRAINT "daily_notification_aggregates_hourly_buckets_array_check" CHECK (jsonb_typeof("hourly_buckets") = 'array' AND jsonb_array_length("hourly_buckets") <= 24),
	CONSTRAINT "daily_notification_aggregates_synthetic_build_check" CHECK (NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel))
);
--> statement-breakpoint
CREATE TABLE "auth_refresh_tokens" (
	"id" uuid PRIMARY KEY NOT NULL,
	"installation_id" uuid NOT NULL,
	"family_id" uuid NOT NULL,
	"token_hash" varchar(128) NOT NULL,
	"replaced_by_id" uuid,
	"issued_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"revoked_at" timestamp with time zone,
	"replayed_at" timestamp with time zone,
	CONSTRAINT "auth_refresh_tokens_token_hash_key" UNIQUE("token_hash"),
	CONSTRAINT "auth_refresh_tokens_expiry_check" CHECK ("expires_at" > "issued_at")
);
--> statement-breakpoint
CREATE TABLE "consent_records" (
	"id" uuid PRIMARY KEY NOT NULL,
	"installation_id" uuid NOT NULL,
	"scope" "consent_scope" NOT NULL,
	"scope_version" varchar(32) NOT NULL,
	"granted" boolean NOT NULL,
	"recorded_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "consent_records_scope_version_check" CHECK (length("scope_version") > 0)
);
--> statement-breakpoint
CREATE TABLE "installations" (
	"id" uuid PRIMARY KEY NOT NULL,
	"user_id" uuid NOT NULL,
	"app_version" varchar(48) NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"is_synthetic" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_seen_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "installations_synthetic_build_check" CHECK (NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel))
);
--> statement-breakpoint
CREATE TABLE "users" (
	"id" uuid PRIMARY KEY NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"is_synthetic" boolean DEFAULT false NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "users_synthetic_build_check" CHECK (NOT ("is_synthetic" AND "build_channel" = 'prod'::build_channel))
);
--> statement-breakpoint
CREATE TABLE "idempotency_records" (
	"id" uuid PRIMARY KEY NOT NULL,
	"installation_id" uuid NOT NULL,
	"operation" varchar(64) NOT NULL,
	"idempotency_key" uuid NOT NULL,
	"request_hash" varchar(64) NOT NULL,
	"result_id" uuid,
	"result_status" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	CONSTRAINT "idempotency_records_installation_operation_key" UNIQUE("installation_id","operation","idempotency_key"),
	CONSTRAINT "idempotency_records_operation_check" CHECK (length("operation") > 0),
	CONSTRAINT "idempotency_records_request_hash_check" CHECK ("request_hash" ~ '^[0-9a-f]{64}$'),
	CONSTRAINT "idempotency_records_expiry_check" CHECK ("expires_at" > "created_at")
);
--> statement-breakpoint
CREATE TABLE "job_runs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"job_name" varchar(64) NOT NULL,
	"status" "job_run_status" NOT NULL,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"finished_at" timestamp with time zone,
	"checkpoint" varchar(128),
	"failure_code" varchar(64),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "job_runs_name_check" CHECK (length("job_name") > 0),
	CONSTRAINT "job_runs_finished_status_check" CHECK (("status" = 'running'::job_run_status AND "finished_at" IS NULL) OR ("status" <> 'running'::job_run_status AND "finished_at" IS NOT NULL))
);
--> statement-breakpoint
CREATE TABLE "outbox_jobs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"job_type" varchar(64) NOT NULL,
	"dedupe_key" varchar(128) NOT NULL,
	"status" "outbox_job_status" DEFAULT 'pending' NOT NULL,
	"payload" jsonb NOT NULL,
	"attempt_count" integer DEFAULT 0 NOT NULL,
	"available_at" timestamp with time zone DEFAULT now() NOT NULL,
	"lease_expires_at" timestamp with time zone,
	"completed_at" timestamp with time zone,
	"failure_code" varchar(64),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "outbox_jobs_type_dedupe_key" UNIQUE("job_type","dedupe_key"),
	CONSTRAINT "outbox_jobs_type_check" CHECK (length("job_type") > 0),
	CONSTRAINT "outbox_jobs_dedupe_key_check" CHECK (length("dedupe_key") > 0),
	CONSTRAINT "outbox_jobs_attempt_count_check" CHECK ("attempt_count" >= 0),
	CONSTRAINT "outbox_jobs_payload_object_check" CHECK (jsonb_typeof("payload") = 'object')
);
--> statement-breakpoint
CREATE TABLE "schema_metadata" (
	"id" uuid PRIMARY KEY NOT NULL,
	"metadata_key" varchar(64) NOT NULL,
	"metadata_value" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "schema_metadata_key_unique" UNIQUE("metadata_key"),
	CONSTRAINT "schema_metadata_key_check" CHECK (length("metadata_key") > 0)
);
--> statement-breakpoint
ALTER TABLE "analytics_events" ADD CONSTRAINT "analytics_events_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "analytics_events" ADD CONSTRAINT "analytics_events_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "daily_notification_aggregates" ADD CONSTRAINT "daily_notification_aggregates_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "auth_refresh_tokens" ADD CONSTRAINT "auth_refresh_tokens_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "consent_records" ADD CONSTRAINT "consent_records_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "installations" ADD CONSTRAINT "installations_user_id_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "idempotency_records" ADD CONSTRAINT "idempotency_records_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "analytics_events_installation_occurred_idx" ON "analytics_events" USING btree ("installation_id","occurred_at");--> statement-breakpoint
CREATE INDEX "analytics_events_received_at_idx" ON "analytics_events" USING btree ("received_at");--> statement-breakpoint
CREATE INDEX "daily_notification_aggregates_local_date_idx" ON "daily_notification_aggregates" USING btree ("local_date");--> statement-breakpoint
CREATE INDEX "auth_refresh_tokens_family_id_idx" ON "auth_refresh_tokens" USING btree ("installation_id","family_id");--> statement-breakpoint
CREATE INDEX "auth_refresh_tokens_expires_at_idx" ON "auth_refresh_tokens" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "consent_records_installation_scope_recorded_idx" ON "consent_records" USING btree ("installation_id","scope","recorded_at");--> statement-breakpoint
CREATE INDEX "installations_user_id_idx" ON "installations" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "idempotency_records_expires_at_idx" ON "idempotency_records" USING btree ("expires_at");--> statement-breakpoint
CREATE INDEX "job_runs_job_name_started_idx" ON "job_runs" USING btree ("job_name","started_at");--> statement-breakpoint
CREATE INDEX "outbox_jobs_claim_idx" ON "outbox_jobs" USING btree ("status","available_at");--> statement-breakpoint
GRANT USAGE ON SCHEMA drizzle TO afterchime_runtime;--> statement-breakpoint
GRANT SELECT ON TABLE drizzle.__drizzle_migrations TO afterchime_runtime;--> statement-breakpoint
REVOKE ALL ON SCHEMA public FROM PUBLIC;--> statement-breakpoint
GRANT USAGE ON SCHEMA public TO afterchime_runtime;--> statement-breakpoint
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;--> statement-breakpoint
ALTER DEFAULT PRIVILEGES FOR ROLE afterchime_migrator IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;--> statement-breakpoint
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users, installations, auth_refresh_tokens TO afterchime_runtime;--> statement-breakpoint
GRANT SELECT, INSERT ON TABLE consent_records, analytics_events TO afterchime_runtime;--> statement-breakpoint
GRANT SELECT, INSERT, UPDATE ON TABLE daily_notification_aggregates, idempotency_records TO afterchime_runtime;--> statement-breakpoint
ALTER TABLE users ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE users FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY users_runtime_scope ON users FOR ALL TO afterchime_runtime
  USING (id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid)
  WITH CHECK (id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid);--> statement-breakpoint
ALTER TABLE installations ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE installations FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY installations_runtime_scope ON installations FOR ALL TO afterchime_runtime
  USING (
    id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
    AND user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
  )
  WITH CHECK (
    id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
    AND user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
  );--> statement-breakpoint
ALTER TABLE auth_refresh_tokens ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE auth_refresh_tokens FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY auth_refresh_tokens_runtime_scope ON auth_refresh_tokens FOR ALL TO afterchime_runtime
  USING (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = auth_refresh_tokens.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = auth_refresh_tokens.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
ALTER TABLE consent_records ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE consent_records FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY consent_records_runtime_scope ON consent_records FOR ALL TO afterchime_runtime
  USING (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = consent_records.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = consent_records.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
ALTER TABLE analytics_events ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE analytics_events FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY analytics_events_runtime_scope ON analytics_events FOR ALL TO afterchime_runtime
  USING (
    (user_id IS NULL OR user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid)
    AND EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = analytics_events.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    (user_id IS NULL OR user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid)
    AND EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = analytics_events.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
ALTER TABLE daily_notification_aggregates ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE daily_notification_aggregates FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY daily_notification_aggregates_runtime_scope ON daily_notification_aggregates FOR ALL TO afterchime_runtime
  USING (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = daily_notification_aggregates.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = daily_notification_aggregates.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
ALTER TABLE idempotency_records ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE idempotency_records FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY idempotency_records_runtime_scope ON idempotency_records FOR ALL TO afterchime_runtime
  USING (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = idempotency_records.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = idempotency_records.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );