# Contracts

Canonical versioned network contracts, shared by Android and the API.

- `events/v1/` contains the closed analytics-event envelope, one schema per approved initial lifecycle/onboarding event, and no free-form properties.
- `fixtures/valid` and `fixtures/invalid` are shared by server and Android contract tests.
- `openapi/afterchime-v1.yaml` is JSON-compatible OpenAPI 3.1 YAML. It defines the bounded analytics batch transport; implementing that endpoint remains Plan 01 work.

Event values are bounded and event properties are explicitly allowlisted. Notification-derived content, notification-source identifiers, advertising IDs, contact data and arbitrary nested payloads are outside this contract.

Schema changes require a version bump, backward-compatibility test, data-dictionary update, and a migration or explicit rejection policy.