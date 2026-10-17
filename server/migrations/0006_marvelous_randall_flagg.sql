ALTER TABLE "dev_feedback_items" ADD COLUMN "project_id" varchar(32) DEFAULT 'afterchime' NOT NULL;--> statement-breakpoint
CREATE INDEX "dev_feedback_items_project_status_queue_idx" ON "dev_feedback_items" USING btree ("project_id","status","queue_position");--> statement-breakpoint
ALTER TABLE "dev_feedback_items" ADD CONSTRAINT "dev_feedback_items_project_check" CHECK ("project_id" IN ('afterchime', 'decree', 'please-just-sleep'));--> statement-breakpoint
INSERT INTO public.users (id, build_channel, is_synthetic)
VALUES
  ('10000000-0000-4000-8000-000000000001', 'dev', true),
  ('20000000-0000-4000-8000-000000000001', 'dev', true)
ON CONFLICT (id) DO NOTHING;--> statement-breakpoint
INSERT INTO public.installations (id, user_id, app_version, build_channel, is_synthetic)
VALUES
  ('10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'dev-feedback-proxy', 'dev', true),
  ('20000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001', 'dev-feedback-proxy', 'dev', true)
ON CONFLICT (id) DO NOTHING;--> statement-breakpoint
DROP FUNCTION public.afterchime_ready_dev_feedback(uuid, uuid, integer, varchar, varchar, varchar, varchar);--> statement-breakpoint
DROP FUNCTION public.afterchime_block_dev_feedback(uuid, uuid, integer, varchar);--> statement-breakpoint
DROP FUNCTION public.afterchime_progress_dev_feedback(uuid, uuid, integer, varchar, varchar);--> statement-breakpoint
DROP FUNCTION public.afterchime_heartbeat_dev_feedback(uuid, uuid, integer, integer);--> statement-breakpoint
DROP FUNCTION public.afterchime_claim_dev_feedback(uuid, integer);--> statement-breakpoint
DROP FUNCTION public.afterchime_get_dev_feedback(uuid);--> statement-breakpoint
CREATE FUNCTION public.afterchime_get_dev_feedback(p_project_id varchar, p_item_id uuid)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep') THEN
    RAISE EXCEPTION 'invalid development feedback project' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY
  SELECT
    item.id,
    item.installation_id,
    item.queue_position,
    item.current_revision,
    item.title,
    item.description,
    item.status,
    item.note,
    item.lease_id,
    item.lease_expires_at,
    item.target_commit,
    item.deployed_commit,
    item.created_at,
    item.updated_at,
    revision.context,
    revision.screenshot,
    revision.screenshot_media_type,
    revision.screenshot_sha256,
    revision.screenshot_width,
    revision.screenshot_height
  FROM public.dev_feedback_items AS item
  JOIN public.dev_feedback_revisions AS revision
    ON revision.item_id = item.id AND revision.revision = item.current_revision
  WHERE item.id = p_item_id AND item.project_id = p_project_id;
END;
$$;--> statement-breakpoint
CREATE FUNCTION public.afterchime_claim_dev_feedback(
  p_project_id varchar,
  p_lease_id uuid,
  p_lease_seconds integer
)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  claimed_id uuid;
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep')
    OR p_lease_seconds < 60 OR p_lease_seconds > 7200 THEN
    RAISE EXCEPTION 'invalid development feedback claim' USING ERRCODE = '42501';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(1095910217, hashtext(p_project_id));
  UPDATE public.dev_feedback_items
  SET status = 'queued'::public.dev_feedback_status,
      note = 'Recovered after an expired worker lease.',
      lease_id = NULL,
      lease_revision = NULL,
      lease_expires_at = NULL,
      updated_at = now()
  WHERE project_id = p_project_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_expires_at <= now();
  SELECT candidate.id INTO claimed_id
  FROM public.dev_feedback_items AS candidate
  WHERE candidate.project_id = p_project_id
    AND candidate.status = 'queued'::public.dev_feedback_status
    AND NOT EXISTS (
      SELECT 1 FROM public.dev_feedback_items AS active
      WHERE active.project_id = p_project_id
        AND active.status = 'in_progress'::public.dev_feedback_status
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.dev_feedback_items AS earlier
      WHERE earlier.project_id = p_project_id
        AND earlier.status = 'blocked'::public.dev_feedback_status
        AND earlier.queue_position < candidate.queue_position
    )
  ORDER BY candidate.queue_position
  LIMIT 1
  FOR UPDATE SKIP LOCKED;
  IF claimed_id IS NULL THEN RETURN; END IF;
  UPDATE public.dev_feedback_items
  SET status = 'in_progress'::public.dev_feedback_status,
      note = 'Working in submission order.',
      lease_id = p_lease_id,
      lease_revision = current_revision,
      lease_expires_at = now() + make_interval(secs => p_lease_seconds),
      updated_at = now()
  WHERE id = claimed_id AND project_id = p_project_id;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_project_id, claimed_id);
END;
$$;--> statement-breakpoint
CREATE FUNCTION public.afterchime_heartbeat_dev_feedback(
  p_project_id varchar,
  p_item_id uuid,
  p_lease_id uuid,
  p_revision integer,
  p_lease_seconds integer
)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep')
    OR p_lease_seconds < 60 OR p_lease_seconds > 7200 THEN
    RAISE EXCEPTION 'invalid development feedback heartbeat' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET lease_expires_at = now() + make_interval(secs => p_lease_seconds),
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
CREATE FUNCTION public.afterchime_progress_dev_feedback(
  p_project_id varchar,
  p_item_id uuid,
  p_lease_id uuid,
  p_revision integer,
  p_note varchar,
  p_target_commit varchar
)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep')
    OR length(p_note) NOT BETWEEN 1 AND 600
    OR (p_target_commit IS NOT NULL AND p_target_commit !~ '^[0-9a-f]{40}$') THEN
    RAISE EXCEPTION 'invalid development feedback progress' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET note = p_note,
      target_commit = COALESCE(p_target_commit, target_commit),
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
CREATE FUNCTION public.afterchime_block_dev_feedback(
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
    RAISE EXCEPTION 'invalid development feedback block' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET status = 'blocked'::public.dev_feedback_status,
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
CREATE FUNCTION public.afterchime_ready_dev_feedback(
  p_project_id varchar,
  p_item_id uuid,
  p_lease_id uuid,
  p_revision integer,
  p_note varchar,
  p_target_commit varchar,
  p_deployed_commit varchar,
  p_live_deployed_revision varchar
)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime'
    OR p_project_id NOT IN ('afterchime', 'decree', 'please-just-sleep')
    OR length(p_note) NOT BETWEEN 1 AND 600
    OR p_target_commit !~ '^[0-9a-f]{40}$'
    OR p_target_commit <> p_deployed_commit
    OR p_target_commit <> p_live_deployed_revision THEN
    RAISE EXCEPTION 'development feedback is not deployed to development' USING ERRCODE = '22023';
  END IF;
  UPDATE public.dev_feedback_items
  SET status = 'ready_for_qa'::public.dev_feedback_status,
      note = p_note,
      target_commit = p_target_commit,
      deployed_commit = p_deployed_commit,
      lease_id = NULL,
      lease_revision = NULL,
      lease_expires_at = NULL,
      updated_at = now()
  WHERE id = p_item_id AND project_id = p_project_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND target_commit = p_target_commit
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_project_id, p_item_id);
END;
$$;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_get_dev_feedback(varchar, uuid) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_claim_dev_feedback(varchar, uuid, integer) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_heartbeat_dev_feedback(varchar, uuid, uuid, integer, integer) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_progress_dev_feedback(varchar, uuid, uuid, integer, varchar, varchar) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_block_dev_feedback(varchar, uuid, uuid, integer, varchar) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_ready_dev_feedback(varchar, uuid, uuid, integer, varchar, varchar, varchar, varchar) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_get_dev_feedback(varchar, uuid) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_claim_dev_feedback(varchar, uuid, integer) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_heartbeat_dev_feedback(varchar, uuid, uuid, integer, integer) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_progress_dev_feedback(varchar, uuid, uuid, integer, varchar, varchar) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_block_dev_feedback(varchar, uuid, uuid, integer, varchar) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_ready_dev_feedback(varchar, uuid, uuid, integer, varchar, varchar, varchar, varchar) TO afterchime_runtime;
