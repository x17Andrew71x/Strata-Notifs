CREATE POLICY users_security_definer_access ON public.users
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
CREATE POLICY installations_security_definer_access ON public.installations
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
CREATE POLICY auth_refresh_tokens_security_definer_access ON public.auth_refresh_tokens
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
CREATE POLICY idempotency_records_security_definer_access ON public.idempotency_records
  FOR ALL TO afterchime_migrator
  USING (true)
  WITH CHECK (true);
--> statement-breakpoint
CREATE FUNCTION public.afterchime_register_installation(
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

  INSERT INTO public.users (id, build_channel, is_synthetic)
  VALUES (p_user_id, p_build_channel, p_is_synthetic);
  INSERT INTO public.installations (id, user_id, app_version, build_channel, is_synthetic)
  VALUES (p_installation_id, p_user_id, p_app_version, p_build_channel, p_is_synthetic);
  INSERT INTO public.auth_refresh_tokens (
    id,
    installation_id,
    family_id,
    token_hash,
    expires_at
  ) VALUES (
    p_refresh_token_id,
    p_installation_id,
    p_refresh_family_id,
    p_refresh_token_hash,
    p_refresh_expires_at
  );
  INSERT INTO public.idempotency_records (
    id,
    installation_id,
    operation,
    idempotency_key,
    request_hash,
    result_id,
    result_status,
    expires_at
  ) VALUES (
    p_idempotency_record_id,
    p_installation_id,
    'installation_registration',
    p_idempotency_key,
    p_request_hash,
    p_installation_id,
    201,
    now() + interval '1 day'
  );

  RETURN QUERY SELECT p_installation_id, p_user_id, false;
END;
$$;
--> statement-breakpoint
CREATE FUNCTION public.afterchime_rotate_refresh_token(
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
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'refresh requires the runtime role' USING ERRCODE = '42501';
  END IF;

  SELECT token.*
  INTO presented_token
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash;
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

  INSERT INTO public.auth_refresh_tokens (
    id,
    installation_id,
    family_id,
    token_hash,
    expires_at
  ) VALUES (
    p_replacement_token_id,
    presented_token.installation_id,
    presented_token.family_id,
    p_replacement_token_hash,
    p_replacement_expires_at
  );
  UPDATE public.auth_refresh_tokens
  SET replaced_by_id = p_replacement_token_id
  WHERE id = presented_token.id;

  RETURN QUERY SELECT 'rotated'::text, presented_token.installation_id, recorded_user_id;
END;
$$;
--> statement-breakpoint
CREATE FUNCTION public.afterchime_revoke_refresh_token(
  p_token_hash varchar(128)
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  presented_token public.auth_refresh_tokens%ROWTYPE;
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'logout requires the runtime role' USING ERRCODE = '42501';
  END IF;

  SELECT token.*
  INTO presented_token
  FROM public.auth_refresh_tokens AS token
  WHERE token.token_hash = p_token_hash;
  IF NOT FOUND THEN
    RETURN false;
  END IF;

  UPDATE public.auth_refresh_tokens
  SET revoked_at = COALESCE(revoked_at, now())
  WHERE installation_id = presented_token.installation_id
    AND family_id = presented_token.family_id;
  RETURN true;
END;
$$;
--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_register_installation(uuid, uuid, varchar, uuid, uuid, varchar, build_channel, boolean, uuid, uuid, varchar, timestamp with time zone) FROM PUBLIC;
--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_rotate_refresh_token(varchar, uuid, varchar, timestamp with time zone) FROM PUBLIC;
--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_revoke_refresh_token(varchar) FROM PUBLIC;
--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_register_installation(uuid, uuid, varchar, uuid, uuid, varchar, build_channel, boolean, uuid, uuid, varchar, timestamp with time zone) TO afterchime_runtime;
--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_rotate_refresh_token(varchar, uuid, varchar, timestamp with time zone) TO afterchime_runtime;
--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_revoke_refresh_token(varchar) TO afterchime_runtime;
