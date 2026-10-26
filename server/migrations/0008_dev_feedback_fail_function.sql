CREATE FUNCTION public.afterchime_fail_dev_feedback(
  p_project_id varchar,
  p_item_id uuid,
  p_lease_id uuid,
  p_revision integer,
  p_note varchar
)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep')
    OR length(p_note) NOT BETWEEN 1 AND 600 THEN
    RAISE EXCEPTION 'invalid development feedback failure' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET status = 'failed'::public.dev_feedback_status,
      note = p_note,
      lease_id = NULL,
      lease_revision = NULL,
      lease_expires_at = NULL,
      updated_at = now()
  WHERE id = p_item_id AND project_id = p_project_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_project_id, p_item_id);
END;
$$;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_fail_dev_feedback(varchar, uuid, uuid, integer, varchar) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_fail_dev_feedback(varchar, uuid, uuid, integer, varchar) TO afterchime_runtime;--> statement-breakpoint
UPDATE public.dev_feedback_items
SET status = 'failed'::public.dev_feedback_status,
    note = 'Automation could not complete this request after 3 attempts because formatting verification failed; no action is needed from you.',
    lease_id = NULL,
    lease_revision = NULL,
    lease_expires_at = NULL,
    updated_at = now()
WHERE id = '0c04820d-abbd-4168-8de4-d698bed11f80'::uuid
  AND project_id = 'afterchime'
  AND status = 'blocked'::public.dev_feedback_status
  AND note = 'Automatic retries were exhausted after repeated infrastructure failures; Alfred must inspect the preserved evidence.';
