# Development feedback control plane

## Status

The Afterchime in-app development-feedback wrapper is retired as of Android `0.1.22`.

Afterchime no longer ships or serves:

- the movable web feedback control, selection overlay, form, work-item drawer, or polling runtime;
- development-feedback native bridge actions, capability fields, screenshot capture, API client, dedicated credentials, recovery hooks, or shell refresh action;
- a committed development APK package or product-server download route.

The web and Android bridge parsers reject the retired action names. Both development-review and production APKs are scanned for the former capability, route, class, credential, and action markers.

## Retained internal infrastructure

The development-only server control plane and its forward-only migration history remain temporarily available for the separately isolated Decree and Please Just Sleep development controllers and for historical audit readback. They are not part of the Afterchime client and remain excluded from production server artifacts and runtime migrations.

Afterchime's controller is disabled after the retirement release is verified live. Existing Afterchime records are preserved in a private owner export; the product does not expose them.

## Release boundary

Production readiness requires all of the following:

1. no retired wrapper chunk or bridge contract in the emitted web shell;
2. no retired Android class, action, capability, credential, or bundled-web marker in either review or production APKs;
3. no development-feedback module or migrations in the production server artifact;
4. no development-package route in the server;
5. exact-SHA CI, security, PostgreSQL, web, Android, privacy, and live-shell verification before any production promotion.
