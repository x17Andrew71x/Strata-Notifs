CREATE TABLE "analytics_consent_coverage" (
	"build_channel" "build_channel" NOT NULL,
	"is_synthetic" boolean NOT NULL,
	"notification_aggregate_consent_count" integer NOT NULL,
	"observed_date" date NOT NULL,
	"product_analytics_consent_count" integer NOT NULL,
	"total_installation_count" integer NOT NULL,
	CONSTRAINT "analytics_consent_coverage_observed_date_build_channel_is_synthetic_pk" PRIMARY KEY("observed_date","build_channel","is_synthetic"),
	CONSTRAINT "analytics_consent_coverage_notification_aggregate_count_check" CHECK ("notification_aggregate_consent_count" >= 0),
	CONSTRAINT "analytics_consent_coverage_product_analytics_count_check" CHECK ("product_analytics_consent_count" >= 0),
	CONSTRAINT "analytics_consent_coverage_total_installation_count_check" CHECK ("total_installation_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "analytics_daily_global" (
	"active_installation_count" integer NOT NULL,
	"analytics_event_count" integer NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"game_pressure_total" integer NOT NULL,
	"is_synthetic" boolean NOT NULL,
	"local_date" date NOT NULL,
	"notification_eligible_count" integer NOT NULL,
	CONSTRAINT "analytics_daily_global_local_date_build_channel_is_synthetic_pk" PRIMARY KEY("local_date","build_channel","is_synthetic"),
	CONSTRAINT "analytics_daily_global_active_installation_count_check" CHECK ("active_installation_count" >= 0),
	CONSTRAINT "analytics_daily_global_event_count_check" CHECK ("analytics_event_count" >= 0),
	CONSTRAINT "analytics_daily_global_game_pressure_total_check" CHECK ("game_pressure_total" >= 0),
	CONSTRAINT "analytics_daily_global_notification_eligible_count_check" CHECK ("notification_eligible_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "analytics_daily_installation" (
	"analytics_event_count" integer NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"game_pressure_total" integer NOT NULL,
	"installation_id" uuid NOT NULL,
	"is_synthetic" boolean NOT NULL,
	"local_date" date NOT NULL,
	"notification_eligible_count" integer NOT NULL,
	CONSTRAINT "analytics_daily_installation_installation_id_local_date_pk" PRIMARY KEY("installation_id","local_date"),
	CONSTRAINT "analytics_daily_installation_event_count_check" CHECK ("analytics_event_count" >= 0),
	CONSTRAINT "analytics_daily_installation_game_pressure_total_check" CHECK ("game_pressure_total" >= 0),
	CONSTRAINT "analytics_daily_installation_notification_eligible_count_check" CHECK ("notification_eligible_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "analytics_notification_volume" (
	"build_channel" "build_channel" NOT NULL,
	"eligible_count" integer NOT NULL,
	"is_synthetic" boolean NOT NULL,
	"reporting_installation_count" integer NOT NULL,
	"utc_date" date NOT NULL,
	CONSTRAINT "analytics_notification_volume_utc_date_build_channel_is_synthetic_pk" PRIMARY KEY("utc_date","build_channel","is_synthetic"),
	CONSTRAINT "analytics_notification_volume_eligible_count_check" CHECK ("eligible_count" >= 0),
	CONSTRAINT "analytics_notification_volume_reporting_installation_count_check" CHECK ("reporting_installation_count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "analytics_release_health" (
	"analytics_event_count" integer NOT NULL,
	"app_version" varchar(48) NOT NULL,
	"build_channel" "build_channel" NOT NULL,
	"failure_event_count" integer NOT NULL,
	"is_synthetic" boolean NOT NULL,
	"received_date" date NOT NULL,
	"version_code" integer NOT NULL,
	CONSTRAINT "analytics_release_health_received_date_app_version_version_code_build_channel_is_synthetic_pk" PRIMARY KEY("received_date","app_version","version_code","build_channel","is_synthetic"),
	CONSTRAINT "analytics_release_health_event_count_check" CHECK ("analytics_event_count" >= 0),
	CONSTRAINT "analytics_release_health_failure_event_count_check" CHECK ("failure_event_count" >= 0),
	CONSTRAINT "analytics_release_health_version_code_check" CHECK ("version_code" >= 1)
);
--> statement-breakpoint
ALTER TABLE "job_runs" DROP CONSTRAINT "job_runs_finished_status_check";--> statement-breakpoint
ALTER TABLE "job_runs" ADD COLUMN "lease_expires_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "job_runs" ADD COLUMN "lease_owner" varchar(64);--> statement-breakpoint
UPDATE "job_runs"
SET
  "status" = 'failed'::job_run_status,
  "finished_at" = now(),
  "failure_code" = COALESCE("failure_code", 'lease_unavailable')
WHERE "status" = 'running'::job_run_status;--> statement-breakpoint
ALTER TABLE "analytics_daily_installation" ADD CONSTRAINT "analytics_daily_installation_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "job_runs" ADD CONSTRAINT "job_runs_finished_status_check" CHECK (
        ("status" = 'running'::job_run_status
          AND "finished_at" IS NULL
          AND "lease_owner" IS NOT NULL
          AND "lease_expires_at" IS NOT NULL)
        OR
        ("status" <> 'running'::job_run_status
          AND "finished_at" IS NOT NULL
          AND "lease_owner" IS NULL
          AND "lease_expires_at" IS NULL)
      );--> statement-breakpoint
GRANT SELECT ON TABLE "analytics_daily_installation" TO afterchime_runtime;--> statement-breakpoint
ALTER TABLE "analytics_daily_installation" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "analytics_daily_installation" FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY analytics_daily_installation_runtime_scope ON "analytics_daily_installation" FOR ALL TO afterchime_runtime
  USING (
    EXISTS (
      SELECT 1
      FROM installations
      WHERE installations.id = analytics_daily_installation.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1
      FROM installations
      WHERE installations.id = analytics_daily_installation.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
CREATE POLICY analytics_daily_installation_security_definer_access ON "analytics_daily_installation" FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);--> statement-breakpoint
CREATE POLICY analytics_events_security_definer_access ON "analytics_events" FOR SELECT TO afterchime_migrator
  USING (true);--> statement-breakpoint
CREATE POLICY consent_records_security_definer_access ON "consent_records" FOR SELECT TO afterchime_migrator
  USING (true);--> statement-breakpoint
CREATE POLICY daily_notification_aggregates_security_definer_access ON "daily_notification_aggregates" FOR SELECT TO afterchime_migrator
  USING (true);--> statement-breakpoint
CREATE FUNCTION public.afterchime_run_analytics_daily(
  p_run_id uuid,
  p_lease_owner varchar(64),
  p_lease_seconds integer
)
RETURNS TABLE(status text, checkpoint text, failure_code text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_acquired boolean;
  v_drift boolean := false;
  v_previous_checkpoint text;
  v_source_checkpoint text;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'analytics daily runner requires the runtime role';
  END IF;

  IF p_lease_owner !~ '^[a-z0-9][a-z0-9_-]{2,63}$'
    OR p_lease_seconds < 1
    OR p_lease_seconds > 300 THEN
    RAISE EXCEPTION 'analytics daily runner input is invalid';
  END IF;

  INSERT INTO job_runs (
    id,
    job_name,
    status,
    lease_owner,
    lease_expires_at
  ) VALUES (
    p_run_id,
    'analytics_daily',
    'running'::job_run_status,
    p_lease_owner,
    now() + make_interval(secs => p_lease_seconds)
  );

  v_acquired := pg_try_advisory_xact_lock(hashtextextended('afterchime:analytics_daily', 0));
  IF NOT v_acquired THEN
    UPDATE job_runs
    SET
      status = 'failed'::job_run_status,
      finished_at = now(),
      failure_code = 'job_busy',
      lease_owner = NULL,
      lease_expires_at = NULL
    WHERE id = p_run_id;
    RETURN QUERY SELECT 'failed'::text, NULL::text, 'job_busy'::text;
    RETURN;
  END IF;

  BEGIN
    SELECT run.checkpoint
    INTO v_previous_checkpoint
    FROM job_runs AS run
    WHERE run.job_name = 'analytics_daily'
      AND run.status = 'succeeded'::job_run_status
    ORDER BY run.started_at DESC
    LIMIT 1;

    SELECT concat_ws(
      '|',
      COALESCE(
        to_char(max(source_activity), 'YYYY-MM-DD"T"HH24:MI:SS.USOF'),
        'epoch'
      ),
      (SELECT count(*)::text FROM analytics_events),
      (SELECT count(*)::text FROM daily_notification_aggregates),
      (SELECT count(*)::text FROM consent_records),
      (SELECT count(*)::text FROM installations)
    )
    INTO v_source_checkpoint
    FROM (
      SELECT max(received_at) AS source_activity FROM analytics_events
      UNION ALL
      SELECT max(updated_at) AS source_activity FROM daily_notification_aggregates
      UNION ALL
      SELECT max(recorded_at) AS source_activity FROM consent_records
      UNION ALL
      SELECT max(GREATEST(created_at, last_seen_at)) AS source_activity FROM installations
    ) AS source_activity;

    CREATE TEMPORARY TABLE analytics_daily_installation_source ON COMMIT DROP AS
    WITH source_rows AS (
      SELECT
        installation_id,
        local_date,
        build_channel,
        is_synthetic,
        1::integer AS analytics_event_count,
        0::integer AS notification_eligible_count,
        0::integer AS game_pressure_total
      FROM analytics_events
      UNION ALL
      SELECT
        installation_id,
        local_date,
        build_channel,
        is_synthetic,
        0::integer AS analytics_event_count,
        eligible_count AS notification_eligible_count,
        game_pressure AS game_pressure_total
      FROM daily_notification_aggregates
    )
    SELECT
      installation_id,
      local_date,
      build_channel,
      is_synthetic,
      sum(analytics_event_count)::integer AS analytics_event_count,
      sum(notification_eligible_count)::integer AS notification_eligible_count,
      sum(game_pressure_total)::integer AS game_pressure_total
    FROM source_rows
    GROUP BY installation_id, local_date, build_channel, is_synthetic;

    CREATE TEMPORARY TABLE analytics_daily_global_source ON COMMIT DROP AS
    SELECT
      local_date,
      build_channel,
      is_synthetic,
      count(DISTINCT installation_id)::integer AS active_installation_count,
      sum(analytics_event_count)::integer AS analytics_event_count,
      sum(notification_eligible_count)::integer AS notification_eligible_count,
      sum(game_pressure_total)::integer AS game_pressure_total
    FROM analytics_daily_installation_source
    GROUP BY local_date, build_channel, is_synthetic;

    CREATE TEMPORARY TABLE analytics_consent_coverage_source ON COMMIT DROP AS
    WITH latest_consent AS (
      SELECT DISTINCT ON (installation_id, scope)
        installation_id,
        scope,
        granted
      FROM consent_records
      ORDER BY installation_id, scope, recorded_at DESC, id DESC
    )
    SELECT
      current_date AS observed_date,
      installations.build_channel,
      installations.is_synthetic,
      count(*)::integer AS total_installation_count,
      count(*) FILTER (WHERE product_consent.granted)::integer AS product_analytics_consent_count,
      count(*) FILTER (WHERE notification_consent.granted)::integer AS notification_aggregate_consent_count
    FROM installations
    LEFT JOIN latest_consent AS product_consent
      ON product_consent.installation_id = installations.id
      AND product_consent.scope = 'product_analytics'::consent_scope
    LEFT JOIN latest_consent AS notification_consent
      ON notification_consent.installation_id = installations.id
      AND notification_consent.scope = 'notification_aggregates'::consent_scope
    GROUP BY installations.build_channel, installations.is_synthetic;

    CREATE TEMPORARY TABLE analytics_release_health_source ON COMMIT DROP AS
    SELECT
      (received_at AT TIME ZONE 'UTC')::date AS received_date,
      app_version,
      version_code,
      build_channel,
      is_synthetic,
      count(*)::integer AS analytics_event_count,
      count(*) FILTER (
        WHERE event_name IN ('sync_failed', 'worker_failed', 'nonfatal_error')
      )::integer AS failure_event_count
    FROM analytics_events
    GROUP BY
      (received_at AT TIME ZONE 'UTC')::date,
      app_version,
      version_code,
      build_channel,
      is_synthetic;

    CREATE TEMPORARY TABLE analytics_notification_volume_source ON COMMIT DROP AS
    WITH hourly_volume AS (
      SELECT
        (
          daily_notification_aggregates.local_date::timestamp
          + make_interval(hours => (bucket.value ->> 'hour')::integer)
          - make_interval(mins => daily_notification_aggregates.timezone_offset_minutes)
        )::date AS utc_date,
        daily_notification_aggregates.installation_id,
        daily_notification_aggregates.build_channel,
        daily_notification_aggregates.is_synthetic,
        (bucket.value ->> 'count')::integer AS eligible_count
      FROM daily_notification_aggregates
      CROSS JOIN LATERAL jsonb_array_elements(daily_notification_aggregates.hourly_buckets) AS bucket(value)
    )
    SELECT
      utc_date,
      build_channel,
      is_synthetic,
      sum(eligible_count)::integer AS eligible_count,
      count(DISTINCT installation_id)::integer AS reporting_installation_count
    FROM hourly_volume
    GROUP BY utc_date, build_channel, is_synthetic;

    IF v_previous_checkpoint = v_source_checkpoint THEN
      SELECT
        EXISTS (
          (SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation_source
           EXCEPT
           SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation)
          UNION ALL
          (SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation
           EXCEPT
           SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation_source)
        )
        OR EXISTS (
          (SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global_source
           EXCEPT
           SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global)
          UNION ALL
          (SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global
           EXCEPT
           SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global_source)
        )
        OR EXISTS (
          (SELECT observed_date, build_channel, is_synthetic, total_installation_count, product_analytics_consent_count, notification_aggregate_consent_count FROM analytics_consent_coverage_source
           EXCEPT
           SELECT observed_date, build_channel, is_synthetic, total_installation_count, product_analytics_consent_count, notification_aggregate_consent_count FROM analytics_consent_coverage
           WHERE observed_date = current_date)
          UNION ALL
          (SELECT observed_date, build_channel, is_synthetic, total_installation_count, product_analytics_consent_count, notification_aggregate_consent_count FROM analytics_consent_coverage
           WHERE observed_date = current_date
           EXCEPT
           SELECT observed_date, build_channel, is_synthetic, total_installation_count, product_analytics_consent_count, notification_aggregate_consent_count FROM analytics_consent_coverage_source)
        )
        OR EXISTS (
          (SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health_source
           EXCEPT
           SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health)
          UNION ALL
          (SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health
           EXCEPT
           SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health_source)
        )
        OR EXISTS (
          (SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume_source
           EXCEPT
           SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume)
          UNION ALL
          (SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume
           EXCEPT
           SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume_source)
        )
      INTO v_drift;

      IF v_drift THEN
        UPDATE job_runs
        SET
          status = 'failed'::job_run_status,
          finished_at = now(),
          failure_code = 'rollup_drift',
          lease_owner = NULL,
          lease_expires_at = NULL
        WHERE id = p_run_id;
        RETURN QUERY SELECT 'failed'::text, v_previous_checkpoint, 'rollup_drift'::text;
        RETURN;
      END IF;
    END IF;

    INSERT INTO analytics_daily_installation (
      installation_id,
      local_date,
      build_channel,
      is_synthetic,
      analytics_event_count,
      notification_eligible_count,
      game_pressure_total
    )
    SELECT
      installation_id,
      local_date,
      build_channel,
      is_synthetic,
      analytics_event_count,
      notification_eligible_count,
      game_pressure_total
    FROM analytics_daily_installation_source
    ON CONFLICT (installation_id, local_date) DO UPDATE
    SET
      build_channel = EXCLUDED.build_channel,
      is_synthetic = EXCLUDED.is_synthetic,
      analytics_event_count = EXCLUDED.analytics_event_count,
      notification_eligible_count = EXCLUDED.notification_eligible_count,
      game_pressure_total = EXCLUDED.game_pressure_total;

    DELETE FROM analytics_daily_installation AS target
    WHERE NOT EXISTS (
      SELECT 1
      FROM analytics_daily_installation_source AS source
      WHERE source.installation_id = target.installation_id
        AND source.local_date = target.local_date
    );

    INSERT INTO analytics_daily_global (
      local_date,
      build_channel,
      is_synthetic,
      active_installation_count,
      analytics_event_count,
      notification_eligible_count,
      game_pressure_total
    )
    SELECT
      local_date,
      build_channel,
      is_synthetic,
      active_installation_count,
      analytics_event_count,
      notification_eligible_count,
      game_pressure_total
    FROM analytics_daily_global_source
    ON CONFLICT (local_date, build_channel, is_synthetic) DO UPDATE
    SET
      active_installation_count = EXCLUDED.active_installation_count,
      analytics_event_count = EXCLUDED.analytics_event_count,
      notification_eligible_count = EXCLUDED.notification_eligible_count,
      game_pressure_total = EXCLUDED.game_pressure_total;

    DELETE FROM analytics_daily_global AS target
    WHERE NOT EXISTS (
      SELECT 1
      FROM analytics_daily_global_source AS source
      WHERE source.local_date = target.local_date
        AND source.build_channel = target.build_channel
        AND source.is_synthetic = target.is_synthetic
    );

    INSERT INTO analytics_consent_coverage (
      observed_date,
      build_channel,
      is_synthetic,
      total_installation_count,
      product_analytics_consent_count,
      notification_aggregate_consent_count
    )
    SELECT
      observed_date,
      build_channel,
      is_synthetic,
      total_installation_count,
      product_analytics_consent_count,
      notification_aggregate_consent_count
    FROM analytics_consent_coverage_source
    ON CONFLICT (observed_date, build_channel, is_synthetic) DO UPDATE
    SET
      total_installation_count = EXCLUDED.total_installation_count,
      product_analytics_consent_count = EXCLUDED.product_analytics_consent_count,
      notification_aggregate_consent_count = EXCLUDED.notification_aggregate_consent_count;

    INSERT INTO analytics_release_health (
      received_date,
      app_version,
      version_code,
      build_channel,
      is_synthetic,
      analytics_event_count,
      failure_event_count
    )
    SELECT
      received_date,
      app_version,
      version_code,
      build_channel,
      is_synthetic,
      analytics_event_count,
      failure_event_count
    FROM analytics_release_health_source
    ON CONFLICT (received_date, app_version, version_code, build_channel, is_synthetic) DO UPDATE
    SET
      analytics_event_count = EXCLUDED.analytics_event_count,
      failure_event_count = EXCLUDED.failure_event_count;

    DELETE FROM analytics_release_health AS target
    WHERE NOT EXISTS (
      SELECT 1
      FROM analytics_release_health_source AS source
      WHERE source.received_date = target.received_date
        AND source.app_version = target.app_version
        AND source.version_code = target.version_code
        AND source.build_channel = target.build_channel
        AND source.is_synthetic = target.is_synthetic
    );

    INSERT INTO analytics_notification_volume (
      utc_date,
      build_channel,
      is_synthetic,
      eligible_count,
      reporting_installation_count
    )
    SELECT
      utc_date,
      build_channel,
      is_synthetic,
      eligible_count,
      reporting_installation_count
    FROM analytics_notification_volume_source
    ON CONFLICT (utc_date, build_channel, is_synthetic) DO UPDATE
    SET
      eligible_count = EXCLUDED.eligible_count,
      reporting_installation_count = EXCLUDED.reporting_installation_count;

    DELETE FROM analytics_notification_volume AS target
    WHERE NOT EXISTS (
      SELECT 1
      FROM analytics_notification_volume_source AS source
      WHERE source.utc_date = target.utc_date
        AND source.build_channel = target.build_channel
        AND source.is_synthetic = target.is_synthetic
    );

    UPDATE job_runs
    SET
      status = 'succeeded'::job_run_status,
      finished_at = now(),
      checkpoint = v_source_checkpoint,
      failure_code = NULL,
      lease_owner = NULL,
      lease_expires_at = NULL
    WHERE id = p_run_id;
    RETURN QUERY SELECT 'succeeded'::text, v_source_checkpoint, NULL::text;
  EXCEPTION WHEN OTHERS THEN
    UPDATE job_runs
    SET
      status = 'failed'::job_run_status,
      finished_at = now(),
      failure_code = 'analytics_daily_failed',
      lease_owner = NULL,
      lease_expires_at = NULL
    WHERE id = p_run_id;
    RETURN QUERY SELECT 'failed'::text, v_previous_checkpoint, 'analytics_daily_failed'::text;
  END;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_run_analytics_daily(uuid, varchar, integer) OWNER TO afterchime_migrator;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_run_analytics_daily(uuid, varchar, integer) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_run_analytics_daily(uuid, varchar, integer) TO afterchime_runtime;