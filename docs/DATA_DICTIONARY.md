# Afterchime data dictionary

This foundation records the approved analytics contract only. Physical PostgreSQL tables, retention enforcement, consent checks and ingestion persistence are owned by **01 Server data foundation**; this document does not authorise collection beyond the product specification.

## Contract records

| Record | Grain | Owner | Source | Sensitivity | Retention |
|---|---|---|---|---|---|
| Analytics event v1 | One consented client interaction | Analytics domain | Encrypted Android outbox, then authenticated analytics ingestion | Pseudonymous product telemetry | Enforceable duration pending the server retention policy; rejected payloads are never stored |
| Analytics event batch | One bounded upload request, 1–50 events and at most 128 KiB | Analytics ingestion boundary | Android WorkManager | Operational transport metadata only | Not retained as a raw request |
| Analytics rejection counter | One reason/category/window aggregate | Analytics operations | Server validator | No payload content or identifiers | Enforceable duration pending the server operations policy |

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
