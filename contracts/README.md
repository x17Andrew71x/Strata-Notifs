# Contracts

Canonical network contracts shared conceptually by Android and the API.

- `analytics-event.schema.json` is deliberately strict and excludes notification content, notification source identifiers, advertising IDs, contact data, and arbitrary nested payloads.
- `openapi.yaml` will be generated and checked in when phase 01 introduces public endpoints.

Schema changes require a version bump, backward-compatibility test, and migration or explicit rejection policy.
