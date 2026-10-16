import { createHash, timingSafeEqual } from "node:crypto";

const digestPattern = /^[0-9a-f]{64}$/;
const hasControlCharacter = (value: string) =>
  Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || code === 127;
  });

export function verifyAdminKey(presented: string | undefined, configuredDigest: string): boolean {
  if (
    typeof presented !== "string" ||
    presented.length < 32 ||
    presented.length > 256 ||
    presented !== presented.trim() ||
    hasControlCharacter(presented) ||
    !digestPattern.test(configuredDigest)
  ) {
    return false;
  }
  const actual = createHash("sha256").update(presented, "utf8").digest();
  const expected = Buffer.from(configuredDigest, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
