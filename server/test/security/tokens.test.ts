import { describe, expect, it } from "vitest";
import {
  createAccessToken,
  createRefreshSecret,
  deriveRegistrationRefreshSecret,
  hashRefreshSecret,
  verifyAccessToken,
} from "../../src/security/tokens.js";

const accessTokenSecret = "test-access-token-secret-at-least-32-characters";
const refreshTokenPepper = "test-refresh-token-pepper-at-least-32-characters";
const issuedAt = new Date("2026-10-03T12:00:00.000Z");

describe("installation session tokens", () => {
  it("issues bounded dev-audience access tokens and validates their signature, audience, and expiry", () => {
    const token = createAccessToken({
      audience: "afterchime-dev",
      installationId: "00000000-0000-4000-8000-000000000011",
      issuedAt,
      secret: accessTokenSecret,
      userId: "00000000-0000-4000-8000-000000000012",
    });

    expect(token.split(".")).toHaveLength(3);
    expect(
      verifyAccessToken({
        audience: "afterchime-dev",
        now: new Date("2026-10-03T12:14:59.000Z"),
        secret: accessTokenSecret,
        token,
      }),
    ).toEqual({
      audience: "afterchime-dev",
      installationId: "00000000-0000-4000-8000-000000000011",
      userId: "00000000-0000-4000-8000-000000000012",
    });
    expect(() =>
      verifyAccessToken({
        audience: "afterchime-prod",
        now: new Date("2026-10-03T12:14:59.000Z"),
        secret: accessTokenSecret,
        token,
      }),
    ).toThrow("token_audience_invalid");
    expect(() =>
      verifyAccessToken({
        audience: "afterchime-dev",
        now: new Date("2026-10-03T12:15:01.000Z"),
        secret: accessTokenSecret,
        token,
      }),
    ).toThrow("token_expired");
  });

  it("derives a retry-safe registration refresh secret without persisting it", () => {
    const idempotencyKey = "00000000-0000-4000-8000-000000000099";
    const secret = deriveRegistrationRefreshSecret(idempotencyKey, refreshTokenPepper);

    expect(secret).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(deriveRegistrationRefreshSecret(idempotencyKey, refreshTokenPepper)).toBe(secret);
    expect(
      deriveRegistrationRefreshSecret("00000000-0000-4000-8000-000000000098", refreshTokenPepper),
    ).not.toBe(secret);
    expect(hashRefreshSecret(secret, refreshTokenPepper)).toMatch(/^[0-9a-f]{64}$/);
  });

  it("creates opaque refresh secrets and stores only a keyed SHA-256 hash", () => {
    const refreshSecret = createRefreshSecret();

    expect(refreshSecret).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(createRefreshSecret()).not.toBe(refreshSecret);
    expect(hashRefreshSecret(refreshSecret, refreshTokenPepper)).toMatch(/^[0-9a-f]{64}$/);
    expect(hashRefreshSecret(refreshSecret, refreshTokenPepper)).toBe(
      hashRefreshSecret(refreshSecret, refreshTokenPepper),
    );
    expect(hashRefreshSecret(refreshSecret, refreshTokenPepper)).not.toContain(refreshSecret);
  });
});
