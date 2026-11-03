CREATE FUNCTION public.afterchime_installation_trust_status(
  p_installation_id uuid
) RETURNS TABLE(
  installation_id uuid,
  installation_trusted boolean,
  profile_trusted boolean,
  trust_source varchar(32),
  granted_at timestamp with time zone
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'trust status requires the runtime role' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY
  SELECT
    installation.id,
    installation.is_trusted,
    profile.is_trusted,
    trust_grant.source,
    trust_grant.granted_at
  FROM public.installations AS installation
  INNER JOIN public.users AS profile ON profile.id = installation.user_id
  LEFT JOIN public.installation_trust_grants AS trust_grant
    ON trust_grant.installation_id = installation.id
  WHERE installation.id = p_installation_id;
END;
$$;--> statement-breakpoint
ALTER FUNCTION public.afterchime_installation_trust_status(uuid) OWNER TO afterchime_migrator;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_installation_trust_status(uuid) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_installation_trust_status(uuid) TO afterchime_runtime;
