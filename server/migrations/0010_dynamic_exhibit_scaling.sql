CREATE TABLE "exhibit_cohort_snapshots" (
	"build_channel" "build_channel" NOT NULL,
	"snapshot_date" date NOT NULL,
	"eligible_user_count" integer NOT NULL,
	"effective_user_count" integer NOT NULL,
	"multiplier_micros" integer NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "exhibit_cohort_snapshots_pk" PRIMARY KEY("build_channel","snapshot_date"),
	CONSTRAINT "exhibit_cohort_snapshots_population_check" CHECK ("eligible_user_count" >= 0 AND "effective_user_count" = greatest(100, least(10000, "eligible_user_count"))),
	CONSTRAINT "exhibit_cohort_snapshots_multiplier_check" CHECK ("multiplier_micros" = round(10000000000.0 / "effective_user_count"))
);
--> statement-breakpoint
ALTER TABLE "exhibits" DROP CONSTRAINT "exhibits_target_units_check";--> statement-breakpoint
ALTER TABLE "exhibits" DROP CONSTRAINT "exhibits_total_units_check";--> statement-breakpoint
ALTER TABLE "exhibits" ALTER COLUMN "total_units" SET DATA TYPE bigint;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD COLUMN "cohort_snapshot_date" date;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD COLUMN "eligible_user_count" integer;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD COLUMN "effective_user_count" integer;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD COLUMN "multiplier_micros" integer;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD COLUMN "credited_microunits" bigint;--> statement-breakpoint
ALTER TABLE "users" ADD COLUMN "last_opened" timestamp with time zone;--> statement-breakpoint
UPDATE "users" SET "last_opened" = "created_at" WHERE "last_opened" IS NULL;--> statement-breakpoint
ALTER TABLE "users" ALTER COLUMN "last_opened" SET DEFAULT now();--> statement-breakpoint
ALTER TABLE "users" ALTER COLUMN "last_opened" SET NOT NULL;--> statement-breakpoint
CREATE POLICY "exhibit_contributions_migrator_access" ON "exhibit_contributions"
  FOR ALL TO "afterchime_migrator"
  USING (true)
  WITH CHECK (true);--> statement-breakpoint
UPDATE "exhibits"
SET
  "target_units" = "target_units" * 100,
  "total_units" = "total_units" * 100000000;--> statement-breakpoint
UPDATE "exhibit_contributions"
SET
  "cohort_snapshot_date" = ("created_at" AT TIME ZONE 'UTC')::date,
  "eligible_user_count" = 0,
  "effective_user_count" = 100,
  "multiplier_micros" = 100000000,
  "credited_microunits" = "units"::bigint * 100000000;--> statement-breakpoint
INSERT INTO "exhibit_cohort_snapshots" (
  "build_channel",
  "snapshot_date",
  "eligible_user_count",
  "effective_user_count",
  "multiplier_micros",
  "created_at"
)
SELECT
  exhibit."build_channel",
  contribution."cohort_snapshot_date",
  0,
  100,
  100000000,
  min(contribution."created_at")
FROM "exhibit_contributions" AS contribution
INNER JOIN "exhibits" AS exhibit ON exhibit."id" = contribution."exhibit_id"
GROUP BY exhibit."build_channel", contribution."cohort_snapshot_date"
ON CONFLICT ("build_channel", "snapshot_date") DO NOTHING;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ALTER COLUMN "cohort_snapshot_date" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ALTER COLUMN "eligible_user_count" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ALTER COLUMN "effective_user_count" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ALTER COLUMN "multiplier_micros" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ALTER COLUMN "credited_microunits" SET NOT NULL;--> statement-breakpoint
CREATE INDEX "users_build_last_opened_idx" ON "users" USING btree ("build_channel","last_opened");--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD CONSTRAINT "exhibit_contributions_population_check" CHECK ("eligible_user_count" >= 0 AND "effective_user_count" = greatest(100, least(10000, "eligible_user_count")));--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD CONSTRAINT "exhibit_contributions_multiplier_check" CHECK ("multiplier_micros" = round(10000000000.0 / "effective_user_count"));--> statement-breakpoint
ALTER TABLE "exhibit_contributions" ADD CONSTRAINT "exhibit_contributions_credit_check" CHECK ("credited_microunits" = "units"::bigint * "multiplier_micros");--> statement-breakpoint
ALTER TABLE "exhibits" ADD CONSTRAINT "exhibits_target_units_check" CHECK ("target_units" BETWEEN 1 AND 1000000000);--> statement-breakpoint
ALTER TABLE "exhibits" ADD CONSTRAINT "exhibits_total_units_check" CHECK ("total_units" BETWEEN 0 AND "target_units"::bigint * 1000000);--> statement-breakpoint
CREATE POLICY "consent_records_cohort_definer_access" ON "consent_records"
  FOR SELECT TO "afterchime_migrator"
  USING (true);--> statement-breakpoint
CREATE FUNCTION public.afterchime_exhibit_cohort_snapshot(
  p_build_channel build_channel
) RETURNS TABLE(
  snapshot_date date,
  eligible_user_count integer,
  effective_user_count integer,
  multiplier_micros integer
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_snapshot_date date := (now() AT TIME ZONE 'UTC')::date;
  v_eligible_user_count integer;
  v_effective_user_count integer;
  v_multiplier_micros integer;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'cohort snapshot requires the runtime role' USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.installations AS installation
    WHERE installation.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
      AND installation.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
      AND installation.build_channel = p_build_channel
  ) THEN
    RAISE EXCEPTION 'cohort snapshot build context is invalid' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    existing.snapshot_date,
    existing.eligible_user_count,
    existing.effective_user_count,
    existing.multiplier_micros
  FROM public.exhibit_cohort_snapshots AS existing
  WHERE existing.build_channel = p_build_channel
    AND existing.snapshot_date = v_snapshot_date;
  IF FOUND THEN
    RETURN;
  END IF;

  SELECT count(*)::integer
  INTO v_eligible_user_count
  FROM public.users AS profile
  WHERE profile.build_channel = p_build_channel
    AND profile.is_synthetic = false
    AND profile.last_opened >= now() - interval '28 days'
    AND EXISTS (
      SELECT 1
      FROM public.installations AS installation
      WHERE installation.user_id = profile.id
        AND installation.build_channel = p_build_channel
        AND (
          SELECT consent.granted AND consent.scope_version = '1'
          FROM public.consent_records AS consent
          WHERE consent.installation_id = installation.id
            AND consent.scope = 'essential_online'::public.consent_scope
          ORDER BY consent.recorded_at DESC, consent.id DESC
          LIMIT 1
        ) IS TRUE
    );

  v_effective_user_count := greatest(100, least(10000, v_eligible_user_count));
  v_multiplier_micros := round(10000000000.0 / v_effective_user_count)::integer;

  INSERT INTO public.exhibit_cohort_snapshots (
    build_channel,
    snapshot_date,
    eligible_user_count,
    effective_user_count,
    multiplier_micros
  ) VALUES (
    p_build_channel,
    v_snapshot_date,
    v_eligible_user_count,
    v_effective_user_count,
    v_multiplier_micros
  ) ON CONFLICT ON CONSTRAINT exhibit_cohort_snapshots_pk DO NOTHING;

  RETURN QUERY
  SELECT
    stored.snapshot_date,
    stored.eligible_user_count,
    stored.effective_user_count,
    stored.multiplier_micros
  FROM public.exhibit_cohort_snapshots AS stored
  WHERE stored.build_channel = p_build_channel
    AND stored.snapshot_date = v_snapshot_date;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_exhibit_cohort_snapshot(build_channel) OWNER TO afterchime_migrator;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_exhibit_cohort_snapshot(build_channel) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_exhibit_cohort_snapshot(build_channel) TO afterchime_runtime;--> statement-breakpoint
REVOKE ALL ON TABLE "exhibit_cohort_snapshots" FROM PUBLIC, "afterchime_runtime";
