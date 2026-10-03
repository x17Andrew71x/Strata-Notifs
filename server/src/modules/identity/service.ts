import { createHash, randomUUID } from "node:crypto";
import type { ServerConfig } from "../../config.js";
import {
  accessTokenLifetimeSeconds,
  createAccessToken,
  createRefreshSecret,
  deriveRegistrationRefreshSecret,
  hashRefreshSecret,
} from "../../security/tokens.js";
import type { IdentityRepository } from "./repository.js";

export type RegistrationDevice = Readonly<{
  androidApiLevel: number;
  deviceClass: "foldable" | "phone" | "tablet";
}>;

export type RegisterInstallationRequest = Readonly<{
  appVersion: string;
  device: RegistrationDevice;
  idempotencyKey: string;
}>;

export type SessionResponse = Readonly<{
  accessToken: string;
  expiresInSeconds: number;
  installationId: string;
  refreshToken: string;
  tokenType: "Bearer";
}>;

export type RegistrationResponse = Readonly<{
  result: SessionResponse;
  statusCode: 200 | 201;
}>;

export class IdentityService {
  public constructor(
    private readonly config: ServerConfig,
    private readonly repository: IdentityRepository,
  ) {}

  public async registerInstallation(
    request: RegisterInstallationRequest,
  ): Promise<RegistrationResponse> {
    const refreshToken = deriveRegistrationRefreshSecret(
      request.idempotencyKey,
      this.config.refreshTokenPepper,
    );
    const registration = await this.repository.registerInstallation({
      appVersion: request.appVersion,
      buildChannel: this.config.buildChannel,
      idempotencyKey: request.idempotencyKey,
      idempotencyRecordId: randomUUID(),
      installationId: randomUUID(),
      isSynthetic: false,
      refreshFamilyId: randomUUID(),
      refreshTokenHash: hashRefreshSecret(refreshToken, this.config.refreshTokenPepper),
      refreshTokenId: randomUUID(),
      requestHash: hashRegistrationRequest(request),
      userId: randomUUID(),
    });
    return {
      result: this.createSessionResponse(
        registration.installationId,
        registration.userId,
        refreshToken,
      ),
      statusCode: registration.idempotent ? 200 : 201,
    };
  }

  public async rotateRefreshToken(refreshToken: string): Promise<SessionResponse | null> {
    const replacementRefreshToken = createRefreshSecret();
    const rotated = await this.repository.rotateRefreshToken({
      replacementTokenHash: hashRefreshSecret(
        replacementRefreshToken,
        this.config.refreshTokenPepper,
      ),
      replacementTokenId: randomUUID(),
      tokenHash: hashRefreshSecret(refreshToken, this.config.refreshTokenPepper),
    });
    if (!rotated) {
      return null;
    }
    return this.createSessionResponse(
      rotated.installationId,
      rotated.userId,
      replacementRefreshToken,
    );
  }

  public async logout(refreshToken: string): Promise<void> {
    await this.repository.revokeRefreshToken(
      hashRefreshSecret(refreshToken, this.config.refreshTokenPepper),
    );
  }

  private createSessionResponse(
    installationId: string,
    userId: string,
    refreshToken: string,
  ): SessionResponse {
    return {
      accessToken: createAccessToken({
        audience: this.config.tokenAudience,
        installationId,
        secret: this.config.accessTokenSecret,
        userId,
      }),
      expiresInSeconds: accessTokenLifetimeSeconds,
      installationId,
      refreshToken,
      tokenType: "Bearer",
    };
  }
}

function hashRegistrationRequest(request: RegisterInstallationRequest): string {
  return createHash("sha256")
    .update(
      JSON.stringify({
        appVersion: request.appVersion,
        device: {
          androidApiLevel: request.device.androidApiLevel,
          deviceClass: request.device.deviceClass,
        },
      }),
    )
    .digest("hex");
}
