import { ZodError } from "zod";
import { RuntimeAttestationError } from "./db/runtime-attestation.js";

export function startupFailureCode(error: unknown): string {
  if (error instanceof RuntimeAttestationError) return error.code;
  if (error instanceof ZodError) return "configuration_invalid";
  return "startup_failed";
}
