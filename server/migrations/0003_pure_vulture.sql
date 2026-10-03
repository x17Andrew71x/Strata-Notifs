DROP POLICY analytics_events_security_definer_access ON public.analytics_events;
--> statement-breakpoint
CREATE POLICY analytics_events_security_definer_access ON public.analytics_events
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
DROP POLICY daily_notification_aggregates_security_definer_access ON public.daily_notification_aggregates;
--> statement-breakpoint
CREATE POLICY daily_notification_aggregates_security_definer_access ON public.daily_notification_aggregates
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_run_analytics_daily(
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
  v_cutoff_date date := (current_date - interval '24 months')::date;
  v_cutoff_timestamp timestamp with time zone := now() - interval '24 months';
  v_drift boolean := false;
  v_notification_cutoff_date date := (current_date - interval '24 months' - interval '1 day')::date;
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
      (SELECT count(*)::text FROM analytics_events WHERE received_at >= v_cutoff_timestamp),
      (SELECT count(*)::text FROM daily_notification_aggregates WHERE local_date >= v_cutoff_date),
      (SELECT count(*)::text FROM consent_records),
      (SELECT count(*)::text FROM installations)
    )
    INTO v_source_checkpoint
    FROM (
      SELECT max(received_at) AS source_activity
      FROM analytics_events
      WHERE received_at >= v_cutoff_timestamp
      UNION ALL
      SELECT max(updated_at) AS source_activity
      FROM daily_notification_aggregates
      WHERE local_date >= v_cutoff_date
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
      WHERE received_at >= v_cutoff_timestamp
        AND local_date >= v_cutoff_date
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
      WHERE local_date >= v_cutoff_date
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
    WHERE received_at >= v_cutoff_timestamp
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
      WHERE daily_notification_aggregates.local_date >= v_cutoff_date
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
           SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation
           WHERE local_date >= v_cutoff_date)
          UNION ALL
          (SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation
           WHERE local_date >= v_cutoff_date
           EXCEPT
           SELECT installation_id, local_date, build_channel, is_synthetic, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_installation_source)
        )
        OR EXISTS (
          (SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global_source
           EXCEPT
           SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global
           WHERE local_date >= v_cutoff_date)
          UNION ALL
          (SELECT local_date, build_channel, is_synthetic, active_installation_count, analytics_event_count, notification_eligible_count, game_pressure_total FROM analytics_daily_global
           WHERE local_date >= v_cutoff_date
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
           SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health
           WHERE received_date >= v_cutoff_date)
          UNION ALL
          (SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health
           WHERE received_date >= v_cutoff_date
           EXCEPT
           SELECT received_date, app_version, version_code, build_channel, is_synthetic, analytics_event_count, failure_event_count FROM analytics_release_health_source)
        )
        OR EXISTS (
          (SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume_source
           EXCEPT
           SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume
           WHERE utc_date >= v_notification_cutoff_date)
          UNION ALL
          (SELECT utc_date, build_channel, is_synthetic, eligible_count, reporting_installation_count FROM analytics_notification_volume
           WHERE utc_date >= v_notification_cutoff_date
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
    WHERE target.local_date >= v_cutoff_date
      AND NOT EXISTS (
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
    WHERE target.local_date >= v_cutoff_date
      AND NOT EXISTS (
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
    WHERE target.received_date >= v_cutoff_date
      AND NOT EXISTS (
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
    WHERE target.utc_date >= v_notification_cutoff_date
      AND NOT EXISTS (
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
$$;
--> statement-breakpoint
ALTER FUNCTION public.afterchime_run_analytics_daily(uuid, varchar, integer) OWNER TO afterchime_migrator;
--> statement-breakpoint
CREATE FUNCTION public.afterchime_delete_account(
  p_user_id uuid,
  p_installation_id uuid
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_deleted boolean := false;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'account deletion requires the runtime role' USING ERRCODE = '42501';
  END IF;

  IF NULLIF(current_setting('afterchime.user_id', true), '')::uuid IS DISTINCT FROM p_user_id
    OR NULLIF(current_setting('afterchime.installation_id', true), '')::uuid IS DISTINCT FROM p_installation_id THEN
    RAISE EXCEPTION 'account deletion scope is invalid' USING ERRCODE = '42501';
  END IF;

  DELETE FROM public.users AS account
  WHERE account.id = p_user_id
    AND EXISTS (
      SELECT 1
      FROM public.installations AS installation
      WHERE installation.id = p_installation_id
        AND installation.user_id = p_user_id
    );
  v_deleted := FOUND;
  RETURN v_deleted;
END;
$$;
--> statement-breakpoint
ALTER FUNCTION public.afterchime_delete_account(uuid, uuid) OWNER TO afterchime_migrator;
--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_delete_account(uuid, uuid) FROM PUBLIC;
--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_delete_account(uuid, uuid) TO afterchime_runtime;
--> statement-breakpoint
CREATE FUNCTION public.afterchime_run_retention(
  p_run_id uuid,
  p_lease_owner varchar(64),
  p_lease_seconds integer,
  p_batch_size integer
)
RETURNS TABLE(status text, checkpoint text, failure_code text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
  v_acquired boolean;
  v_checkpoint text;
  v_deleted_aggregates integer := 0;
  v_deleted_events integer := 0;
  v_deleted_idempotency integer := 0;
  v_deleted_installation_marts integer := 0;
  v_deleted_tokens integer := 0;
  v_remaining integer;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'retention runner requires the runtime role';
  END IF;

  IF p_lease_owner !~ '^[a-z0-9][a-z0-9_-]{2,63}$'
    OR p_lease_seconds < 1
    OR p_lease_seconds > 300
    OR p_batch_size < 1
    OR p_batch_size > 1000 THEN
    RAISE EXCEPTION 'retention runner input is invalid';
  END IF;

  INSERT INTO job_runs (
    id,
    job_name,
    status,
    lease_owner,
    lease_expires_at
  ) VALUES (
    p_run_id,
    'retention',
    'running'::job_run_status,
    p_lease_owner,
    now() + make_interval(secs => p_lease_seconds)
  );

  v_acquired := pg_try_advisory_xact_lock(hashtextextended('afterchime:retention', 0));
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
    v_remaining := p_batch_size;

    DELETE FROM public.analytics_events AS target
    WHERE target.id IN (
      SELECT candidate.id
      FROM public.analytics_events AS candidate
      WHERE candidate.received_at < now() - interval '24 months'
      ORDER BY candidate.received_at, candidate.id
      LIMIT v_remaining
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS v_deleted_events = ROW_COUNT;
    v_remaining := v_remaining - v_deleted_events;

    DELETE FROM public.daily_notification_aggregates AS target
    WHERE target.id IN (
      SELECT candidate.id
      FROM public.daily_notification_aggregates AS candidate
      WHERE candidate.local_date < (current_date - interval '24 months')::date
      ORDER BY candidate.local_date, candidate.id
      LIMIT v_remaining
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS v_deleted_aggregates = ROW_COUNT;
    v_remaining := v_remaining - v_deleted_aggregates;

    DELETE FROM public.analytics_daily_installation AS target
    WHERE target.ctid IN (
      SELECT candidate.ctid
      FROM public.analytics_daily_installation AS candidate
      WHERE candidate.local_date < (current_date - interval '24 months')::date
      ORDER BY candidate.local_date, candidate.installation_id
      LIMIT v_remaining
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS v_deleted_installation_marts = ROW_COUNT;
    v_remaining := v_remaining - v_deleted_installation_marts;

    DELETE FROM public.auth_refresh_tokens AS target
    WHERE target.id IN (
      SELECT candidate.id
      FROM public.auth_refresh_tokens AS candidate
      WHERE candidate.expires_at < now()
      ORDER BY candidate.expires_at, candidate.id
      LIMIT v_remaining
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS v_deleted_tokens = ROW_COUNT;
    v_remaining := v_remaining - v_deleted_tokens;

    DELETE FROM public.idempotency_records AS target
    WHERE target.id IN (
      SELECT candidate.id
      FROM public.idempotency_records AS candidate
      WHERE candidate.expires_at < now()
      ORDER BY candidate.expires_at, candidate.id
      LIMIT v_remaining
      FOR UPDATE SKIP LOCKED
    );
    GET DIAGNOSTICS v_deleted_idempotency = ROW_COUNT;

    v_checkpoint := concat_ws(
      '|',
      'analytics_events:' || v_deleted_events::text,
      'daily_notification_aggregates:' || v_deleted_aggregates::text,
      'analytics_daily_installation:' || v_deleted_installation_marts::text,
      'auth_refresh_tokens:' || v_deleted_tokens::text,
      'idempotency_records:' || v_deleted_idempotency::text
    );

    UPDATE job_runs
    SET
      status = 'succeeded'::job_run_status,
      finished_at = now(),
      checkpoint = v_checkpoint,
      failure_code = NULL,
      lease_owner = NULL,
      lease_expires_at = NULL
    WHERE id = p_run_id;
    RETURN QUERY SELECT 'succeeded'::text, v_checkpoint, NULL::text;
  EXCEPTION WHEN OTHERS THEN
    UPDATE job_runs
    SET
      status = 'failed'::job_run_status,
      finished_at = now(),
      failure_code = 'retention_failed',
      lease_owner = NULL,
      lease_expires_at = NULL
    WHERE id = p_run_id;
    RETURN QUERY SELECT 'failed'::text, NULL::text, 'retention_failed'::text;
  END;
END;
$$;
--> statement-breakpoint
ALTER FUNCTION public.afterchime_run_retention(uuid, varchar, integer, integer) OWNER TO afterchime_migrator;
--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_run_retention(uuid, varchar, integer, integer) FROM PUBLIC;
--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_run_retention(uuid, varchar, integer, integer) TO afterchime_runtime;
