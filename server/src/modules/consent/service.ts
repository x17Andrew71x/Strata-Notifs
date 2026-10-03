import { createHash } from "node:crypto";
import type { AccessTokenClaims } from "../../security/tokens.js";
import type {
  ConsentRepository,
  ConsentScope,
  ConsentState,
  RecordConsentInput,
} from "./repository.js";

export type UpdateConsentRequest = AccessTokenClaims &
  Readonly<{
    granted: boolean;
    idempotencyKey: string;
    scope: ConsentScope;
    scopeVersion: string;
  }>;

export type RequireConsentRequest = AccessTokenClaims &
  Readonly<{
    scope: ConsentScope;
    scopeVersion: string;
  }>;

export class ConsentInactiveError extends Error {
  public constructor() {
    super("consent_inactive");
    this.name = "ConsentInactiveError";
  }
}

export class ConsentService {
  public constructor(private readonly repository: ConsentRepository) {}

  public async updateConsent(request: UpdateConsentRequest): Promise<readonly ConsentState[]> {
    const input: RecordConsentInput = {
      granted: request.granted,
      idempotencyKey: request.idempotencyKey,
      installationId: request.installationId,
      requestHash: hashConsentRequest(request),
      scope: request.scope,
      scopeVersion: request.scopeVersion,
      userId: request.userId,
    };
    return this.repository.recordAndListCurrent(input);
  }

  public async requireActiveConsent(request: RequireConsentRequest): Promise<void> {
    const active = await this.repository.hasActiveConsent(
      request,
      request.scope,
      request.scopeVersion,
    );
    if (!active) {
      throw new ConsentInactiveError();
    }
  }
}

function hashConsentRequest(request: UpdateConsentRequest): string {
  return createHash("sha256")
    .update(
      JSON.stringify({
        granted: request.granted,
        scope: request.scope,
        scopeVersion: request.scopeVersion,
      }),
    )
    .digest("hex");
}
