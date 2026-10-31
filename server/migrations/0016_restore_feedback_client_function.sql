CREATE OR REPLACE FUNCTION public.afterchime_restore_latest_feedback_client(
  input_project varchar
)
RETURNS TABLE(target_installation_id uuid, moved_items integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  target_installation uuid;
  moved_count integer;
BEGIN
  IF input_project <> 'afterchime' THEN
    RAISE EXCEPTION 'feedback client restoration is limited to Afterchime' USING ERRCODE = '22023';
  END IF;

  SELECT installation.id
  INTO target_installation
  FROM installations AS installation
  WHERE installation.build_channel = 'dev'::build_channel
    AND installation.is_synthetic = false
    AND installation.app_version IN (
      '0.1.15-dev-debug',
      '0.1.16-dev-debug',
      '0.1.17-dev-debug'
    )
    AND installation.created_at >= now() - interval '7 days'
  ORDER BY installation.created_at DESC, installation.id DESC
  LIMIT 1;

  IF target_installation IS NULL THEN
    RETURN;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM dev_feedback_items AS item
    INNER JOIN dev_feedback_revisions AS source_revision
      ON source_revision.item_id = item.id
    INNER JOIN dev_feedback_revisions AS target_revision
      ON target_revision.installation_id = target_installation
      AND target_revision.idempotency_key = source_revision.idempotency_key
      AND target_revision.id <> source_revision.id
    WHERE item.project_id = input_project
      AND item.installation_id <> target_installation
      AND (
        item.status IN (
          'queued'::dev_feedback_status,
          'blocked'::dev_feedback_status,
          'failed'::dev_feedback_status,
          'ready_for_qa'::dev_feedback_status
        )
        OR (
          item.status = 'in_progress'::dev_feedback_status
          AND item.lease_expires_at < now()
        )
      )
  ) THEN
    RAISE EXCEPTION 'cannot restore feedback client because an idempotency key conflicts' USING ERRCODE = '23505';
  END IF;

  UPDATE dev_feedback_revisions AS revision
  SET installation_id = target_installation
  FROM dev_feedback_items AS item
  WHERE revision.item_id = item.id
    AND item.project_id = input_project
    AND item.installation_id <> target_installation
    AND (
      item.status IN (
        'queued'::dev_feedback_status,
        'blocked'::dev_feedback_status,
        'failed'::dev_feedback_status,
        'ready_for_qa'::dev_feedback_status
      )
      OR (
        item.status = 'in_progress'::dev_feedback_status
        AND item.lease_expires_at < now()
      )
    );

  WITH moved AS (
    UPDATE dev_feedback_items
    SET installation_id = target_installation
    WHERE project_id = input_project
      AND installation_id <> target_installation
      AND (
        status IN (
          'queued'::dev_feedback_status,
          'blocked'::dev_feedback_status,
          'failed'::dev_feedback_status,
          'ready_for_qa'::dev_feedback_status
        )
        OR (
          status = 'in_progress'::dev_feedback_status
          AND lease_expires_at < now()
        )
      )
    RETURNING 1
  )
  SELECT count(*)::integer INTO moved_count FROM moved;

  RETURN QUERY SELECT target_installation, moved_count;
END
$$;

REVOKE ALL ON FUNCTION public.afterchime_restore_latest_feedback_client(varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.afterchime_restore_latest_feedback_client(varchar) TO afterchime_runtime;
