import { describe, expect, it } from "vitest";
import { ZodError, z } from "zod";
import { RuntimeAttestationError } from "../src/db/runtime-attestation.js";
import { startupFailureCode } from "../src/startup-failure.js";

describe("safe startup failure code", () => {
  it("preserves only the attestation category", () => {
    expect(startupFailureCode(new RuntimeAttestationError("runtime_role_bypassrls"))).toBe(
      "runtime_role_bypassrls",
    );
  });

  it("classifies invalid configuration without serializing validation details", () => {
    const schema = z.object({ DATABASE_URL: z.string().url() });
    let error: unknown;
    try {
      schema.parse({ DATABASE_URL: "not-a-url" });
    } catch (caught) {
      error = caught;
    }
    expect(error).toBeInstanceOf(ZodError);
    expect(startupFailureCode(error)).toBe("configuration_invalid");
  });

  it("does not pass unknown error text into logs", () => {
    expect(startupFailureCode(new Error("postgresql://private:secret@example.invalid"))).toBe(
      "startup_failed",
    );
  });

  it("exposes only an allowlisted machine code for an unknown startup failure", () => {
    expect(
      startupFailureCode({ code: "ECONNREFUSED", message: "postgresql://private:secret" }),
    ).toBe("startup_econnrefused");
  });
});
