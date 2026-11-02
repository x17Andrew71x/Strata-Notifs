ALTER TABLE "users" ADD COLUMN "is_trusted" boolean DEFAULT false NOT NULL;--> statement-breakpoint
ALTER TABLE "installations" ADD COLUMN "is_trusted" boolean DEFAULT false NOT NULL;--> statement-breakpoint
CREATE TABLE "installation_trust_grants" (
  "installation_id" uuid PRIMARY KEY NOT NULL REFERENCES "installations"("id") ON DELETE CASCADE,
  "source" varchar(32) NOT NULL,
  "granted_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "installation_trust_grants_source_check" CHECK ("source" IN ('owner_admin', 'play_integrity'))
);--> statement-breakpoint
ALTER TABLE "installation_trust_grants" ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE "installation_trust_grants" FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY "installation_trust_grants_migrator_access" ON "installation_trust_grants"
  FOR ALL TO "afterchime_migrator"
  USING (true)
  WITH CHECK (true);--> statement-breakpoint
REVOKE ALL ON TABLE "installation_trust_grants" FROM PUBLIC, "afterchime_runtime";--> statement-breakpoint
CREATE UNIQUE INDEX "auth_refresh_tokens_one_active_leaf_per_family_idx"
  ON "auth_refresh_tokens" ("installation_id", "family_id")
  WHERE "revoked_at" IS NULL AND "replaced_by_id" IS NULL;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_register_installation(
  p_idempotency_record_id uuid,
  p_idempotency_key uuid,
  p_request_hash varchar(64),
  p_user_id uuid,
  p_installation_id uuid,
  p_app_version varchar(48),
  p_build_channel build_channel,
  p_is_synthetic boolean,
  p_refresh_token_id uuid,
  p_refresh_family_id uuid,
  p_refresh_token_hash varchar(128),
  p_refresh_expires_at timestamp with time zone
) RETURNS TABLE(installation_id uuid, user_id uuid, idempotent boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  existing_record public.idempotency_records%ROWTYPE;
  recorded_user_id uuid;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'registration requires the runtime role' USING ERRCODE = '42501';
  END IF;

  SELECT record.*
  INTO existing_record
  FROM public.idempotency_records AS record
  WHERE record.operation = 'installation_registration'
    AND record.idempotency_key = p_idempotency_key;

  IF FOUND THEN
    IF existing_record.request_hash <> p_request_hash THEN
      RAISE EXCEPTION 'idempotency request conflict' USING ERRCODE = 'P0001';
    END IF;
    SELECT installation.user_id
    INTO recorded_user_id
    FROM public.installations AS installation
    WHERE installation.id = existing_record.result_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'registration receipt is incomplete' USING ERRCODE = 'P0002';
    END IF;
    RETURN QUERY SELECT existing_record.result_id, recorded_user_id, true;
    RETURN;
  END IF;

  INSERT INTO public.users (id, build_channel, is_synthetic, is_trusted)
  VALUES (p_user_id, p_build_channel, p_is_synthetic, false);
  INSERT INTO public.installations (
    id, user_id, app_version, build_channel, is_synthetic, is_trusted
  ) VALUES (
    p_installation_id, p_user_id, p_app_version, p_build_channel, p_is_synthetic, false
  );
  INSERT INTO public.auth_refresh_tokens (
    id, installation_id, family_id, token_hash, expires_at
  ) VALUES (
    p_refresh_token_id, p_installation_id, p_refresh_family_id, p_refresh_token_hash, p_refresh_expires_at
  );
  INSERT INTO public.idempotency_records (
    id, installation_id, operation, idempotency_key, request_hash, result_id, result_status, expires_at
  ) VALUES (
    p_idempotency_record_id, p_installation_id, 'installation_registration', p_idempotency_key,
    p_request_hash, p_installation_id, 201, now() + interval '1 day'
  );
  RETURN QUERY SELECT p_installation_id, p_user_id, false;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_register_installation(uuid, uuid, varchar, uuid, uuid, varchar, build_channel, boolean, uuid, uuid, varchar, timestamp with time zone) OWNER TO afterchime_migrator;--> statement-breakpoint
CREATE FUNCTION public.afterchime_grant_installation_trust(
  p_installation_id uuid,
  p_source varchar(32)
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_user_id uuid;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'trust grant requires the runtime role' USING ERRCODE = '42501';
  END IF;
  IF p_source NOT IN ('owner_admin', 'play_integrity') THEN
    RAISE EXCEPTION 'trust grant source is invalid' USING ERRCODE = '22023';
  END IF;

  SELECT installation.user_id
  INTO v_user_id
  FROM public.installations AS installation
  WHERE installation.id = p_installation_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RETURN false;
  END IF;

  INSERT INTO public.installation_trust_grants (installation_id, source)
  VALUES (p_installation_id, p_source)
  ON CONFLICT (installation_id) DO NOTHING;
  UPDATE public.installations SET is_trusted = true WHERE id = p_installation_id;
  UPDATE public.users SET is_trusted = true WHERE id = v_user_id;
  RETURN true;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_grant_installation_trust(uuid, varchar) OWNER TO afterchime_migrator;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_grant_installation_trust(uuid, varchar) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_grant_installation_trust(uuid, varchar) TO afterchime_runtime;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_rotate_refresh_token(
  p_token_hash varchar(128),
  p_replacement_token_id uuid,
  p_replacement_token_hash varchar(128),
  p_replacement_expires_at timestamp with time zone
) RETURNS TABLE(outcome text, installation_id uuid, user_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  presented_token public.auth_refresh_tokens%ROWTYPE;
  recorded_user_id uuid;
  v_family_id uuid;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'refresh requires the runtime role' USING ERRCODE = '42501';
  END IF;

  SELECT token.family_id
  INTO v_family_id
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'invalid'::text, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_family_id::text, 0));

  SELECT token.*
  INTO presented_token
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash
  FOR UPDATE;
  IF NOT FOUND OR presented_token.expires_at <= now() THEN
    RETURN QUERY SELECT 'invalid'::text, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;

  IF presented_token.revoked_at IS NOT NULL OR presented_token.replaced_by_id IS NOT NULL THEN
    UPDATE public.auth_refresh_tokens AS token
    SET revoked_at = COALESCE(token.revoked_at, now())
    WHERE token.installation_id = presented_token.installation_id
      AND token.family_id = presented_token.family_id;
    UPDATE public.auth_refresh_tokens
    SET replayed_at = COALESCE(replayed_at, now())
    WHERE id = presented_token.id;
    RETURN QUERY SELECT 'replayed'::text, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;

  SELECT installation.user_id
  INTO recorded_user_id
  FROM public.installations AS installation
  WHERE installation.id = presented_token.installation_id;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'invalid'::text, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;

  UPDATE public.auth_refresh_tokens
  SET replaced_by_id = p_replacement_token_id
  WHERE id = presented_token.id;
  INSERT INTO public.auth_refresh_tokens (
    id, installation_id, family_id, token_hash, expires_at
  ) VALUES (
    p_replacement_token_id, presented_token.installation_id, presented_token.family_id,
    p_replacement_token_hash, p_replacement_expires_at
  );
  RETURN QUERY SELECT 'rotated'::text, presented_token.installation_id, recorded_user_id;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_rotate_refresh_token(varchar, uuid, varchar, timestamp with time zone) OWNER TO afterchime_migrator;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_revoke_refresh_token(
  p_token_hash varchar(128)
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  presented_token public.auth_refresh_tokens%ROWTYPE;
  v_family_id uuid;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'logout requires the runtime role' USING ERRCODE = '42501';
  END IF;

  SELECT token.family_id
  INTO v_family_id
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash;
  IF NOT FOUND THEN
    RETURN false;
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_family_id::text, 0));

  SELECT token.*
  INTO presented_token
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash
  FOR UPDATE;
  IF NOT FOUND THEN
    RETURN false;
  END IF;

  UPDATE public.auth_refresh_tokens
  SET revoked_at = COALESCE(revoked_at, now())
  WHERE installation_id = presented_token.installation_id
    AND family_id = presented_token.family_id;
  RETURN true;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_revoke_refresh_token(varchar) OWNER TO afterchime_migrator;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_exhibit_cohort_snapshot(
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
  SELECT existing.snapshot_date, existing.eligible_user_count,
         existing.effective_user_count, existing.multiplier_micros
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
    AND profile.is_trusted = true
    AND profile.last_opened >= now() - interval '28 days'
    AND EXISTS (
      SELECT 1
      FROM public.installations AS installation
      WHERE installation.user_id = profile.id
        AND installation.build_channel = p_build_channel
        AND installation.is_trusted = true
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
    build_channel, snapshot_date, eligible_user_count, effective_user_count, multiplier_micros
  ) VALUES (
    p_build_channel, v_snapshot_date, v_eligible_user_count,
    v_effective_user_count, v_multiplier_micros
  ) ON CONFLICT ON CONSTRAINT exhibit_cohort_snapshots_pk DO NOTHING;

  RETURN QUERY
  SELECT stored.snapshot_date, stored.eligible_user_count,
         stored.effective_user_count, stored.multiplier_micros
  FROM public.exhibit_cohort_snapshots AS stored
  WHERE stored.build_channel = p_build_channel
    AND stored.snapshot_date = v_snapshot_date;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_exhibit_cohort_snapshot(build_channel) OWNER TO afterchime_migrator;
