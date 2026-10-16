CREATE TYPE "public"."dev_feedback_status" AS ENUM('queued', 'in_progress', 'blocked', 'ready_for_qa', 'accepted');--> statement-breakpoint
CREATE TABLE "dev_feedback_items" (
	"id" uuid PRIMARY KEY NOT NULL,
	"installation_id" uuid NOT NULL,
	"queue_position" bigint GENERATED ALWAYS AS IDENTITY (sequence name "dev_feedback_items_queue_position_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1),
	"current_revision" integer DEFAULT 1 NOT NULL,
	"title" varchar(120) NOT NULL,
	"description" text NOT NULL,
	"status" "dev_feedback_status" DEFAULT 'queued' NOT NULL,
	"note" varchar(600) DEFAULT 'Queued in submission order.' NOT NULL,
	"lease_id" uuid,
	"lease_revision" integer,
	"lease_expires_at" timestamp with time zone,
	"target_commit" varchar(40),
	"deployed_commit" varchar(40),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"accepted_at" timestamp with time zone,
	CONSTRAINT "dev_feedback_items_queue_position_unique" UNIQUE("queue_position"),
	CONSTRAINT "dev_feedback_items_revision_check" CHECK ("current_revision" >= 1),
	CONSTRAINT "dev_feedback_items_title_check" CHECK (length("title") BETWEEN 1 AND 120),
	CONSTRAINT "dev_feedback_items_description_check" CHECK (length("description") BETWEEN 1 AND 4000),
	CONSTRAINT "dev_feedback_items_note_check" CHECK (length("note") <= 600),
	CONSTRAINT "dev_feedback_items_lease_check" CHECK ((
        "status" = 'in_progress'::dev_feedback_status
        AND "lease_id" IS NOT NULL
        AND "lease_revision" = "current_revision"
        AND "lease_expires_at" IS NOT NULL
      ) OR (
        "status" <> 'in_progress'::dev_feedback_status
        AND "lease_id" IS NULL
        AND "lease_revision" IS NULL
        AND "lease_expires_at" IS NULL
      )),
	CONSTRAINT "dev_feedback_items_target_commit_check" CHECK ("target_commit" IS NULL OR "target_commit" ~ '^[0-9a-f]{40}$'),
	CONSTRAINT "dev_feedback_items_deployed_commit_check" CHECK ("deployed_commit" IS NULL OR "deployed_commit" ~ '^[0-9a-f]{40}$'),
	CONSTRAINT "dev_feedback_items_accepted_check" CHECK (("status" = 'accepted'::dev_feedback_status) = ("accepted_at" IS NOT NULL))
);
--> statement-breakpoint
CREATE TABLE "dev_feedback_revisions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"item_id" uuid NOT NULL,
	"installation_id" uuid NOT NULL,
	"revision" integer NOT NULL,
	"idempotency_key" uuid NOT NULL,
	"request_hash" varchar(64) NOT NULL,
	"title" varchar(120) NOT NULL,
	"description" text NOT NULL,
	"context" jsonb NOT NULL,
	"screenshot" "bytea" NOT NULL,
	"screenshot_media_type" varchar(32) NOT NULL,
	"screenshot_sha256" varchar(64) NOT NULL,
	"screenshot_width" integer NOT NULL,
	"screenshot_height" integer NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "dev_feedback_revisions_item_revision_unique" UNIQUE("item_id","revision"),
	CONSTRAINT "dev_feedback_revisions_installation_idempotency_unique" UNIQUE("installation_id","idempotency_key"),
	CONSTRAINT "dev_feedback_revisions_revision_check" CHECK ("revision" >= 1),
	CONSTRAINT "dev_feedback_revisions_title_check" CHECK (length("title") BETWEEN 1 AND 120),
	CONSTRAINT "dev_feedback_revisions_description_check" CHECK (length("description") BETWEEN 1 AND 4000),
	CONSTRAINT "dev_feedback_revisions_context_check" CHECK (jsonb_typeof("context") = 'object'),
	CONSTRAINT "dev_feedback_revisions_media_type_check" CHECK ("screenshot_media_type" = 'image/webp'),
	CONSTRAINT "dev_feedback_revisions_hash_check" CHECK ("screenshot_sha256" ~ '^[0-9a-f]{64}$'),
	CONSTRAINT "dev_feedback_revisions_request_hash_check" CHECK ("request_hash" ~ '^[0-9a-f]{64}$'),
	CONSTRAINT "dev_feedback_revisions_screenshot_size_check" CHECK (octet_length("screenshot") BETWEEN 1 AND 786432),
	CONSTRAINT "dev_feedback_revisions_dimensions_check" CHECK ("screenshot_width" BETWEEN 1 AND 16384 AND "screenshot_height" BETWEEN 1 AND 16384)
);
--> statement-breakpoint
ALTER TABLE "dev_feedback_items" ADD CONSTRAINT "dev_feedback_items_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "dev_feedback_revisions" ADD CONSTRAINT "dev_feedback_revisions_item_id_dev_feedback_items_id_fk" FOREIGN KEY ("item_id") REFERENCES "public"."dev_feedback_items"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "dev_feedback_revisions" ADD CONSTRAINT "dev_feedback_revisions_installation_id_installations_id_fk" FOREIGN KEY ("installation_id") REFERENCES "public"."installations"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "dev_feedback_items_status_queue_idx" ON "dev_feedback_items" USING btree ("status","queue_position");--> statement-breakpoint
CREATE INDEX "dev_feedback_items_installation_status_queue_idx" ON "dev_feedback_items" USING btree ("installation_id","status","queue_position");--> statement-breakpoint
CREATE INDEX "dev_feedback_revisions_item_created_idx" ON "dev_feedback_revisions" USING btree ("item_id","created_at");--> statement-breakpoint
GRANT SELECT, INSERT, UPDATE ON TABLE dev_feedback_items, dev_feedback_revisions TO afterchime_runtime;--> statement-breakpoint
GRANT USAGE ON TYPE dev_feedback_status TO afterchime_runtime;--> statement-breakpoint
GRANT USAGE, SELECT ON SEQUENCE dev_feedback_items_queue_position_seq TO afterchime_runtime;--> statement-breakpoint
ALTER TABLE dev_feedback_items ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE dev_feedback_items FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY dev_feedback_items_runtime_scope ON dev_feedback_items FOR ALL TO afterchime_runtime, afterchime_migrator
  USING (
    current_user = 'afterchime_migrator'
    OR EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = dev_feedback_items.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    current_user = 'afterchime_migrator'
    OR EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = dev_feedback_items.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
ALTER TABLE dev_feedback_revisions ENABLE ROW LEVEL SECURITY;--> statement-breakpoint
ALTER TABLE dev_feedback_revisions FORCE ROW LEVEL SECURITY;--> statement-breakpoint
CREATE POLICY dev_feedback_revisions_runtime_scope ON dev_feedback_revisions FOR ALL TO afterchime_runtime, afterchime_migrator
  USING (
    current_user = 'afterchime_migrator'
    OR EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = dev_feedback_revisions.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  )
  WITH CHECK (
    current_user = 'afterchime_migrator'
    OR EXISTS (
      SELECT 1 FROM installations
      WHERE installations.id = dev_feedback_revisions.installation_id
        AND installations.id = NULLIF(current_setting('afterchime.installation_id', true), '')::uuid
        AND installations.user_id = NULLIF(current_setting('afterchime.user_id', true), '')::uuid
    )
  );--> statement-breakpoint
CREATE TYPE public.dev_feedback_admin_item AS (
  id uuid,
  installation_id uuid,
  queue_position bigint,
  current_revision integer,
  title varchar,
  description text,
  status dev_feedback_status,
  note varchar,
  lease_id uuid,
  lease_expires_at timestamp with time zone,
  target_commit varchar,
  deployed_commit varchar,
  created_at timestamp with time zone,
  updated_at timestamp with time zone,
  context jsonb,
  screenshot bytea,
  screenshot_media_type varchar,
  screenshot_sha256 varchar,
  screenshot_width integer,
  screenshot_height integer
);--> statement-breakpoint
GRANT USAGE ON TYPE public.dev_feedback_admin_item TO afterchime_runtime;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_get_dev_feedback(p_item_id uuid)
RETURNS SETOF public.dev_feedback_admin_item
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
BEGIN
  IF session_user <> 'afterchime_runtime' THEN
    RAISE EXCEPTION 'development feedback admin read requires the runtime role' USING ERRCODE = '42501';
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
  WHERE item.id = p_item_id;
END;
$$;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_claim_dev_feedback(
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
  IF session_user <> 'afterchime_runtime' OR p_lease_seconds < 60 OR p_lease_seconds > 7200 THEN
    RAISE EXCEPTION 'invalid development feedback claim' USING ERRCODE = '42501';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(1095910217, 1);
  UPDATE public.dev_feedback_items
  SET status = 'queued'::public.dev_feedback_status,
      note = 'Recovered after an expired worker lease.',
      lease_id = NULL,
      lease_revision = NULL,
      lease_expires_at = NULL,
      updated_at = now()
  WHERE status = 'in_progress'::public.dev_feedback_status
    AND lease_expires_at <= now();
  SELECT candidate.id INTO claimed_id
  FROM public.dev_feedback_items AS candidate
  WHERE candidate.status = 'queued'::public.dev_feedback_status
    AND NOT EXISTS (
      SELECT 1 FROM public.dev_feedback_items AS active
      WHERE active.status = 'in_progress'::public.dev_feedback_status
    )
    AND NOT EXISTS (
      SELECT 1 FROM public.dev_feedback_items AS earlier
      WHERE earlier.status = 'blocked'::public.dev_feedback_status
        AND earlier.queue_position < candidate.queue_position
    )
  ORDER BY candidate.queue_position
  LIMIT 1
  FOR UPDATE SKIP LOCKED;
  IF claimed_id IS NULL THEN
    RETURN;
  END IF;
  UPDATE public.dev_feedback_items
  SET status = 'in_progress'::public.dev_feedback_status,
      note = 'Working in submission order.',
      lease_id = p_lease_id,
      lease_revision = current_revision,
      lease_expires_at = now() + make_interval(secs => p_lease_seconds),
      updated_at = now()
  WHERE dev_feedback_items.id = claimed_id;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(claimed_id);
END;
$$;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_heartbeat_dev_feedback(
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
  IF session_user <> 'afterchime_runtime' OR p_lease_seconds < 60 OR p_lease_seconds > 7200 THEN
    RAISE EXCEPTION 'invalid development feedback heartbeat' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET lease_expires_at = now() + make_interval(secs => p_lease_seconds),
      updated_at = now()
  WHERE id = p_item_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_item_id);
END;
$$;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_progress_dev_feedback(
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
    OR length(p_note) NOT BETWEEN 1 AND 600
    OR (p_target_commit IS NOT NULL AND p_target_commit !~ '^[0-9a-f]{40}$') THEN
    RAISE EXCEPTION 'invalid development feedback progress' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET note = p_note,
      target_commit = COALESCE(p_target_commit, target_commit),
      updated_at = now()
  WHERE id = p_item_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_item_id);
END;
$$;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_block_dev_feedback(
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
  IF session_user <> 'afterchime_runtime' OR length(p_note) NOT BETWEEN 1 AND 600 THEN
    RAISE EXCEPTION 'invalid development feedback block' USING ERRCODE = '42501';
  END IF;
  UPDATE public.dev_feedback_items
  SET status = 'blocked'::public.dev_feedback_status,
      note = p_note,
      lease_id = NULL,
      lease_revision = NULL,
      lease_expires_at = NULL,
      updated_at = now()
  WHERE id = p_item_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_item_id);
END;
$$;--> statement-breakpoint
CREATE OR REPLACE FUNCTION public.afterchime_ready_dev_feedback(
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
  WHERE id = p_item_id
    AND status = 'in_progress'::public.dev_feedback_status
    AND lease_id = p_lease_id
    AND current_revision = p_revision
    AND lease_revision = p_revision
    AND target_commit = p_target_commit
    AND lease_expires_at > now();
  IF NOT FOUND THEN RETURN; END IF;
  RETURN QUERY SELECT * FROM public.afterchime_get_dev_feedback(p_item_id);
END;
$$;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_get_dev_feedback(uuid) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_claim_dev_feedback(uuid, integer) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_heartbeat_dev_feedback(uuid, uuid, integer, integer) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_progress_dev_feedback(uuid, uuid, integer, varchar, varchar) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_block_dev_feedback(uuid, uuid, integer, varchar) FROM PUBLIC;--> statement-breakpoint
REVOKE ALL ON FUNCTION public.afterchime_ready_dev_feedback(uuid, uuid, integer, varchar, varchar, varchar, varchar) FROM PUBLIC;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_get_dev_feedback(uuid) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_claim_dev_feedback(uuid, integer) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_heartbeat_dev_feedback(uuid, uuid, integer, integer) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_progress_dev_feedback(uuid, uuid, integer, varchar, varchar) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_block_dev_feedback(uuid, uuid, integer, varchar) TO afterchime_runtime;--> statement-breakpoint
GRANT EXECUTE ON FUNCTION public.afterchime_ready_dev_feedback(uuid, uuid, integer, varchar, varchar, varchar, varchar) TO afterchime_runtime;
