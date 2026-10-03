import type { DatabaseClient } from "../../db/client.js";

const refreshTokenLifetimeMilliseconds = 30 * 24 * 60 * 60 * 1000;

export class IdempotencyConflictError extends Error {
  public constructor() {
    super("idempotency_conflict");
    this.name = "IdempotencyConflictError";
  }
}

export type RegisterInstallationInput = Readonly<{
  appVersion: string;
  buildChannel: "dev" | "prod";
  idempotencyKey: string;
  idempotencyRecordId: string;
  installationId: string;
  isSynthetic: boolean;
  refreshFamilyId: string;
  refreshTokenHash: string;
  refreshTokenId: string;
  requestHash: string;
  userId: string;
}>;

export type RegistrationRecord = Readonly<{
  idempotent: boolean;
  installationId: string;
  userId: string;
}>;

export type RotateRefreshTokenInput = Readonly<{
  replacementTokenHash: string;
  replacementTokenId: string;
  tokenHash: string;
}>;

export type RotatedRefreshToken = Readonly<{
  installationId: string;
  userId: string;
}>;

function expiresAt(now: Date = new Date()): Date {
  return new Date(now.getTime() + refreshTokenLifetimeMilliseconds);
}

export class IdentityRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async registerInstallation(input: RegisterInstallationInput): Promise<RegistrationRecord> {
    try {
      const [record] = await this.database<
        Readonly<{ idempotent: boolean; installation_id: string; user_id: string }>[]
      >`
        SELECT *
        FROM public.afterchime_register_installation(
          ${input.idempotencyRecordId},
          ${input.idempotencyKey},
          ${input.requestHash},
          ${input.userId},
          ${input.installationId},
          ${input.appVersion},
          ${input.buildChannel},
          ${input.isSynthetic},
          ${input.refreshTokenId},
          ${input.refreshFamilyId},
          ${input.refreshTokenHash},
          ${expiresAt()}
        )
      `;
      if (!record) {
        throw new Error("registration_result_missing");
      }
      return {
        idempotent: record.idempotent,
        installationId: record.installation_id,
        userId: record.user_id,
      };
    } catch (error) {
      if (isPostgresError(error, "P0001")) {
        throw new IdempotencyConflictError();
      }
      throw error;
    }
  }

  public async rotateRefreshToken(
    input: RotateRefreshTokenInput,
  ): Promise<RotatedRefreshToken | null> {
    const [record] = await this.database<
      Readonly<{
        installation_id: string | null;
        outcome: "invalid" | "replayed" | "rotated";
        user_id: string | null;
      }>[]
    >`
      SELECT *
      FROM public.afterchime_rotate_refresh_token(
        ${input.tokenHash},
        ${input.replacementTokenId},
        ${input.replacementTokenHash},
        ${expiresAt()}
      )
    `;
    if (record?.outcome !== "rotated" || !record.installation_id || !record.user_id) {
      return null;
    }
    return { installationId: record.installation_id, userId: record.user_id };
  }

  public async revokeRefreshToken(tokenHash: string): Promise<void> {
    await this.database`SELECT public.afterchime_revoke_refresh_token(${tokenHash})`;
  }
}

function isPostgresError(error: unknown, code: string): boolean {
  if (error === null || typeof error !== "object" || Array.isArray(error) || !("code" in error)) {
    return false;
  }
  return (error as Readonly<{ code?: unknown }>).code === code;
}
