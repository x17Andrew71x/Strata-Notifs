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
