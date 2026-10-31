DO $$
DECLARE
  target_installation uuid;
BEGIN
  SELECT installation.id
  INTO target_installation
  FROM installations AS installation
  WHERE installation.build_channel = 'dev'::build_channel
    AND installation.is_synthetic = false
    AND installation.app_version IN ('0.1.15-dev-debug', '0.1.16-dev-debug')
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
    WHERE item.project_id = 'afterchime'
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
    RAISE EXCEPTION 'cannot restore Afterchime work to the current client because an idempotency key conflicts';
  END IF;

  UPDATE dev_feedback_revisions AS revision
  SET installation_id = target_installation
  FROM dev_feedback_items AS item
  WHERE revision.item_id = item.id
    AND item.project_id = 'afterchime'
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

  UPDATE dev_feedback_items
  SET installation_id = target_installation
  WHERE project_id = 'afterchime'
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
    );
END
$$;
