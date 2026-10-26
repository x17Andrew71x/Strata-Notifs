CREATE TYPE "public"."exhibit_kind" AS ENUM('featured', 'archive');--> statement-breakpoint
CREATE TYPE "public"."exhibit_status" AS ENUM('draft', 'ready', 'open', 'closed', 'cancelled');--> statement-breakpoint
CREATE TABLE "exhibit_contributions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"exhibit_id" uuid NOT NULL,
	"installation_id" uuid NOT NULL,
	"idempotency_key" uuid NOT NULL,
	"local_date" date NOT NULL,
	"units" integer NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "exhibit_contributions_installation_key" UNIQUE("installation_id","idempotency_key"),
	CONSTRAINT "exhibit_contributions_units_check" CHECK ("units" BETWEEN 1 AND 100000)
);
--> statement-breakpoint
CREATE TABLE "exhibit_unlocks" (
	"id" uuid PRIMARY KEY NOT NULL,
	"exhibit_id" uuid NOT NULL,
	"installation_id" uuid NOT NULL,
	"unlocked_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "exhibit_unlocks_exhibit_installation_key" UNIQUE("exhibit_id","installation_id")
);
--> statement-breakpoint
CREATE TABLE "exhibits" (
	"id" uuid PRIMARY KEY NOT NULL,
	"slug" varchar(80) NOT NULL,
	"title" varchar(80) NOT NULL,
	"season_label" varchar(48) NOT NULL,
	"image_path" varchar(180) NOT NULL,
	"kind" "exhibit_kind" NOT NULL,
	"status" "exhibit_status" NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"target_units" integer NOT NULL,
	"personal_minimum_units" integer NOT NULL,
	"daily_contribution_cap" integer NOT NULL,
	"total_units" integer DEFAULT 0 NOT NULL,
	"version" integer DEFAULT 1 NOT NULL,
	"starts_at" timestamp with time zone NOT NULL,
	"ends_at" timestamp with time zone,
	"placeholder_artwork" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "exhibits_build_slug_key" UNIQUE("build_channel","slug"),
	CONSTRAINT "exhibits_slug_check" CHECK ("slug" ~ '^[a-z0-9-]{1,80}$'),
	CONSTRAINT "exhibits_image_path_check" CHECK ("image_path" ~ '^/exhibits/[a-z0-9-]{1,128}-[a-f0-9]{12}\.webp$'),
	CONSTRAINT "exhibits_target_units_check" CHECK ("target_units" BETWEEN 1 AND 10000000),
	CONSTRAINT "exhibits_personal_minimum_check" CHECK ("personal_minimum_units" BETWEEN 1 AND "target_units"),
	CONSTRAINT "exhibits_daily_cap_check" CHECK ("daily_contribution_cap" BETWEEN 1 AND 100000),
	CONSTRAINT "exhibits_total_units_check" CHECK ("total_units" BETWEEN 0 AND "target_units"),
	CONSTRAINT "exhibits_version_check" CHECK ("version" >= 1),
	CONSTRAINT "exhibits_time_check" CHECK ("ends_at" IS NULL OR "ends_at" > "starts_at"),
	CONSTRAINT "exhibits_kind_deadline_check" CHECK (("kind" = 'featured'::exhibit_kind AND "ends_at" IS NOT NULL) OR ("kind" = 'archive'::exhibit_kind AND "ends_at" IS NULL))
);
--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD CONSTRAINT "exhibit_contributions_exhibit_id_exhibits_id_fk" FOREIGN KEY ("exhibit_id") REFERENCES "public"."exhibits"("id") ON DELETE restrict ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD CONSTRAINT "exhibit_contributions_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "exhibit_unlocks" ADD CONSTRAINT "exhibit_unlocks_exhibit_id_exhibits_id_fk" FOREIGN KEY ("exhibit_id") REFERENCES "public"."exhibits"("id") ON DELETE restrict ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "exhibit_unlocks" ADD CONSTRAINT "exhibit_unlocks_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "exhibit_contributions_exhibit_installation_idx" ON "exhibit_contributions" USING btree ("exhibit_id","installation_id");--> statement-breakpoint
CREATE INDEX "exhibit_contributions_installation_date_idx" ON "exhibit_contributions" USING btree ("installation_id","local_date");--> statement-breakpoint
CREATE INDEX "exhibit_unlocks_installation_idx" ON "exhibit_unlocks" USING btree ("installation_id","unlocked_at");--> statement-breakpoint
CREATE INDEX "exhibits_current_idx" ON "exhibits" USING btree ("build_channel","kind","status","starts_at");--> statement-breakpoint
CREATE UNIQUE INDEX "exhibits_one_open_per_kind_idx" ON "exhibits" USING btree ("build_channel","kind") WHERE "status" = 'open'::exhibit_status;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY "exhibit_contributions_runtime_scope" ON "exhibit_contributions"
  FOR ALL TO "afterchime_runtime"
  USING ("installation_id" = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid)
  WITH CHECK ("installation_id" = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid);--> statement-breakpoint
ALTER TABLE "exhibit_unlocks" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "exhibit_unlocks" FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY "exhibit_unlocks_runtime_scope" ON "exhibit_unlocks"
  FOR ALL TO "afterchime_runtime"
  USING ("installation_id" = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid)
  WITH CHECK ("installation_id" = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid);--> statement-breakpoint
GRANT SELECT, INSERT ON TABLE "exhibit_contributions" TO "afterchime_runtime";--> statement-breakpoint
GRANT SELECT, INSERT ON TABLE "exhibit_unlocks" TO "afterchime_runtime";--> statement-breakpoint
GRANT SELECT ON TABLE "exhibits" TO "afterchime_runtime";--> statement-breakpoint
GRANT UPDATE ("total_units", "version", "updated_at") ON TABLE "exhibits" TO "afterchime_runtime";--> statement-breakpoint
INSERT INTO "exhibits" (
  "id", "slug", "title", "season_label", "image_path", "kind", "status", "build_channel",
  "target_units", "personal_minimum_units", "daily_contribution_cap", "total_units", "version",
  "starts_at", "ends_at", "placeholder_artwork"
) VALUES
  (
    '30000000-0000-4000-8000-000000000001', 'returning-light-2027', 'The Returning Light',
    'Spring 2027', '/exhibits/returning-light-placeholder-9b0223afcfc7.webp', 'featured', 'open', 'dev',
    5000, 900, 30, 2823, 1, '2026-10-01T00:00:00Z', '2027-01-01T00:00:00Z', true
  ),
  (
    '30000000-0000-4000-8000-000000000002', 'long-night-2026', 'The Long Night',
    'Winter 2026', '/exhibits/long-night-placeholder-65aa588c66bf.webp', 'archive', 'open', 'dev',
    20000, 1500, 30, 3648, 1, '2026-10-01T00:00:00Z', NULL, true
  ),
  (
    '30000000-0000-4000-8000-000000000003', 'monsoon-choir-2026', 'Monsoon Choir',
    'Summer 2026', '/exhibits/monsoon-choir-placeholder-c754a2db5e27.webp', 'featured', 'closed', 'dev',
    5000, 900, 30, 5000, 1, '2026-04-01T00:00:00Z', '2026-07-01T00:00:00Z', true
  ),
  (
    '30000000-0000-4000-8000-000000000004', 'ember-flight-2026', 'Ember Flight',
    'Autumn 2026', '/exhibits/ember-flight-placeholder-06a33afcd216.webp', 'featured', 'closed', 'dev',
    5000, 900, 30, 5000, 1, '2026-07-01T00:00:00Z', '2026-10-01T00:00:00Z', true
  ),
  (
    '30000000-0000-4000-8000-000000000005', 'tideglass-2026', 'Tideglass',
    'Spring 2026', '/exhibits/tideglass-placeholder-38d7b8496d0c.webp', 'featured', 'closed', 'dev',
    5000, 900, 30, 4300, 1, '2026-01-01T00:00:00Z', '2026-04-01T00:00:00Z', true
  )
ON CONFLICT ("build_channel", "slug") DO NOTHING;