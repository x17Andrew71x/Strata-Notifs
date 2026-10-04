import { ZodError } from "zod";
import { RuntimeAttestationError } from "./db/runtime-attestation.js";

function safeUnknownErrorCode(error: unknown): string | undefined {
  if (error === null || typeof error !== "object" || !("code" in error)) return undefined;
  const code = (error as Readonly<{ code?: unknown }>).code;
  if (typeof code !== "string" || !/^[A-Z0-9_]{2,16}$/.test(code)) return undefined;
  return code.toLowerCase();
}

export function startupFailureCode(error: unknown): string {
  if (error instanceof RuntimeAttestationError) return error.code;
  if (error instanceof ZodError) return "configuration_invalid";
  const code = safeUnknownErrorCode(error);
  return code ? `startup_${code}` : "startup_failed";
}
