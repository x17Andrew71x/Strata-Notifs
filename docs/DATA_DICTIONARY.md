# Afterchime data dictionary

This foundation records approved analytics and notification-aggregate contracts only. Physical PostgreSQL tables, retention enforcement, consent checks and ingestion persistence are owned by **01 Server data foundation**; this document does not authorise collection beyond the product specification.

## Contract records

| Record | Grain | Owner | Source | Sensitivity | Retention |
|---|---|---|---|---|---|
| Analytics event v1 | One consented client interaction | Analytics domain | Encrypted Android outbox, then authenticated analytics ingestion | Pseudonymous product telemetry | Deleted in resumable bounded batches after 24 months; rejected payloads are never stored |
| Analytics event batch | One bounded upload request, 1–50 events and at most 128 KiB | Analytics ingestion boundary | Android WorkManager | Operational transport metadata only | Not retained as a raw request |
| Analytics rejection counter | One reason/category/window aggregate | Analytics operations | Server validator | No payload content or identifiers | Enforceable duration pending the server operations policy |
| Daily notification aggregate | One consented installation-local day and explicit revision | Notification aggregate domain | Android aggregate uploader, then authenticated aggregate ingestion | Pseudonymous derived counts only | Deleted in resumable bounded batches after 24 months |
| Analytics daily installation rollup | One installation-local day | Analytics worker | Security-definer daily rollup | Pseudonymous derived counts; forced-RLS, installation-scoped | Deleted after 24 months and immediately with its installation |
| Analytics daily global rollup | One local day, build channel and synthetic marker | Analytics worker | Security-definer daily rollup | Aggregate counts only | Irreversible non-reidentifiable totals are preserved after raw source expiry or account deletion |
| Analytics consent coverage | One observed UTC day, build channel and synthetic marker | Analytics worker | Security-definer daily rollup | Aggregate consent counts only | Irreversible non-reidentifiable totals are preserved after raw source expiry or account deletion |
| Analytics release health | One received UTC day and app release | Analytics worker | Security-definer daily rollup | Aggregate event/failure counts only | Irreversible non-reidentifiable totals are preserved after raw source expiry or account deletion |
| Analytics notification volume | One UTC day, build channel and synthetic marker | Analytics worker | Hourly notification aggregate rollup | Global counts only; no installation identifiers | Irreversible non-reidentifiable totals are preserved after raw source expiry or account deletion |
| Analytics worker job run | One leased daily-rollup or retention invocation | Analytics operations | `job_runs` | Safe status, checkpoint and failure code only | Operational record; checkpoints contain only category-level deletion counts or opaque source state |

## Daily notification aggregate

The aggregate endpoint accepts only one authenticated installation's local day. It stores no notification payload, source identity, source token, source colour, package identity, key, media, action, or free-form content.

| Field | Meaning and bound | Sensitivity / handling |
|---|---|---|
| `local_date` / `timezone_offset_minutes` | Installation-local calendar day and coarse offset | Derived day boundary; installation identity is server-derived, never supplied by the client |
| `revision` | Positive client revision for one local day | Exact replay is a duplicate; only a newer revision may replace the stored aggregate |
| `eligible_count` | Integer `0`–`100,000` | True eligible count; never a reward multiplier |
| `hourly_buckets` | At most one `{ hour, count }` per hour, summing to `eligible_count` | Coarse time distribution only; no event or source identity |
| `category_counts` | Closed approved Android category totals, summing to `eligible_count` | Coarse category totals only; unknown keys reject |
| `game_pressure` | Integer `0`–`100` | Separate capped community-progress measure |
| `observation_completeness` | Integer `0`–`100` | Distinguishes observation gaps from quiet days |
| `rules_version` | Positive local aggregation/generator rules version | Compatibility metadata |
| `consent_scope_version` | Active notification-aggregate consent version | Admission check only; consent history remains its own domain record |

Server-derived build channel and synthetic markers are never accepted from the client. A malformed, unconsented, stale, or conflicting request stores no additional aggregate row.

## Daily analytics marts and job state

The worker reads only the approved event and notification aggregates through a narrowly granted security-definer function. It returns a safe status/checkpoint/failure code; no mart, source row, identifier or notification-derived value is returned through the worker command.

| Record | Stored fields | Use and boundary |
|---|---|---|
| `analytics_daily_installation` | installation ID, local day, build/synthetic marker, event count, eligible notification count, game pressure | User-scoped derived row. Forced RLS permits the current installation to read only its own derived day; worker writes are security-definer-only. |
| `analytics_daily_global` | local day, build/synthetic marker, active installation count, event count, eligible notification count, game-pressure total | Aggregate reconciliation across installation days. |
| `analytics_consent_coverage` | observation day, build/synthetic marker, installation and active-consent counts | Daily aggregate coverage snapshot; no identity is retained. |
| `analytics_release_health` | received UTC day, release/version code, build/synthetic marker, event and failure counts | Coarse release-health evidence only. |
| `analytics_notification_volume` | UTC day, build/synthetic marker, eligible notification and reporting-installation counts | True global UTC notification volume is reconstructed from approved hourly buckets. |
| `job_runs` analytics-daily record | job name, status, start/finish, opaque source checkpoint, bounded lease owner/expiry and safe failure code | An advisory lock permits one rollup at a time. A stable-source mismatch is recorded as `rollup_drift` and never silently repaired. |
| `job_runs` retention record | job name, status, start/finish, bounded lease owner/expiry and category-level deletion counts | An advisory lock permits one bounded batch at a time. Repeating a completed run only processes the next eligible rows; no notification payload or identity enters the record. |

## Analytics event v1 envelope

| Field | Meaning and bound | Sensitivity / handling |
|---|---|---|
| `event_id` | Client-generated UUID; idempotency identity | Pseudonymous; unique per event |
| `event_name` | Closed v1 catalogue; unknown names reject | Product telemetry |
| `schema_version` | Integer constant `1`; unsupported versions reject | Contract metadata |
| `user_id` | Optional pseudonymous UUID | Pseudonymous identity; never a contact identifier |
| `installation_id` | Pseudonymous UUID for the authenticated installation | Pseudonymous identity |
| `session_id` | Optional pseudonymous UUID | Pseudonymous session identity |
| `occurred_at` / `local_date` / `timezone_offset_minutes` | Client behavioural time, local calendar day, coarse offset | Product telemetry; server validates allowed clock range |
| `received_at` | Server-assigned receipt time; not client-authoritative | Operational metadata |
| `app_version` / `version_code` / `build_channel` / `android_api_level` | Release and coarse compatibility metadata | Product telemetry |
| `device_class` / `locale` / `screen_name` | Closed coarse classifications | Product telemetry; no hardware serial or precise location |
| `consent_scope_version` | Version of the active consent scope | Consent audit metadata |
| `properties` | Closed event-specific object with at most three approved fields | Product telemetry; arbitrary keys and free-form text reject |

## Initial catalogue ownership

The v1 schemas define only the lifecycle/session and onboarding/permission/consent events in Product Specification §12.4. New event names or properties require a new versioned contract, fixture coverage, data-dictionary row and server acceptance review. Product behaviours that affect entitlements, identity, consent or community state must also be recorded in their dedicated domain tables; analytics is never the sole business record.

## Rejection boundary

When Plan 01 introduces the ingestion boundary, it must validate event name, schema version, object shape, bounded values, consent and timestamp range before persistence. It may count a safe rejection category operationally, but must never retain the rejected payload. No notification-derived content or raw package identity belongs in this contract, fixtures, analytics, logs or transport.
