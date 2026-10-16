import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { verifyAdminKey } from "../../src/security/admin-auth.js";

const rawKey = "development-controller-key-with-sufficient-entropy";
const digest = createHash("sha256").update(rawKey).digest("hex");

describe("development feedback admin authentication", () => {
  it("accepts only the exact key bound to the configured SHA-256 digest", () => {
    expect(verifyAdminKey(rawKey, digest)).toBe(true);
    expect(verifyAdminKey(`${rawKey}-wrong`, digest)).toBe(false);
  });

  it.each([
    ["", digest],
    [rawKey, ""],
    [rawKey, "A".repeat(64)],
    [rawKey, "0".repeat(63)],
    ["x".repeat(257), createHash("sha256").update("x".repeat(257)).digest("hex")],
  ])("fails closed for invalid raw or configured key material %#", (presented, configured) => {
    expect(verifyAdminKey(presented, configured)).toBe(false);
  });
});
