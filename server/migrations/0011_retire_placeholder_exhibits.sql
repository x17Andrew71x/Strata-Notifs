UPDATE "exhibits"
SET
  "status" = 'cancelled'::exhibit_status,
  "version" = "version" + 1,
  "updated_at" = now()
WHERE "build_channel" = 'dev'::build_channel
  AND "placeholder_artwork" = true
  AND "id" IN (
    '30000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000003',
    '30000000-0000-4000-8000-000000000004',
    '30000000-0000-4000-8000-000000000005'
  );
