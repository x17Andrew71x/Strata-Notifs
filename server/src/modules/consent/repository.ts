import { randomUUID } from "node:crypto";
import type { DatabaseClient } from "../../db/client.js";

export type ConsentScope = "essential_online" | "notification_aggregates" | "product_analytics";

export type ConsentIdentity = Readonly<{
  installationId: string;
  userId: string;
}>;

export type ConsentState = Readonly<{
  granted: boolean;
  scope: ConsentScope;
  scopeVersion: string;
}>;

export type RecordConsentInput = ConsentIdentity &
  Readonly<{
    granted: boolean;
    idempotencyKey: string;
    requestHash: string;
    scope: ConsentScope;
    scopeVersion: string;
  }>;

export class ConsentIdempotencyConflictError extends Error {
  public constructor() {
    super("consent_idempotency_conflict");
    this.name = "ConsentIdempotencyConflictError";
  }
}

export class ConsentRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async recordAndListCurrent(input: RecordConsentInput): Promise<readonly ConsentState[]> {
    return this.database.begin(async (transaction) => {
      await transaction`SELECT set_config('afterchime.user_id', ${input.userId}, true)`;
      await transaction`SELECT set_config('afterchime.installation_id', ${input.installationId}, true)`;

      const consentRecordId = randomUUID();
      const [createdIdempotency] = await transaction<
        Readonly<{ result_id: string; request_hash: string }>[]
      >`
        INSERT INTO idempotency_records (
          id,
          installation_id,
          operation,
          idempotency_key,
          request_hash,
          result_id,
          result_status,
          expires_at
        ) VALUES (
          ${randomUUID()},
          ${input.installationId},
          'consent_update',
          ${input.idempotencyKey},
          ${input.requestHash},
          ${consentRecordId},
          200,
          now() + interval '1 day'
        )
        ON CONFLICT (installation_id, operation, idempotency_key) DO NOTHING
        RETURNING result_id, request_hash
      `;

      if (!createdIdempotency) {
        const [existingIdempotency] = await transaction<
          Readonly<{ result_id: string | null; request_hash: string }>[]
        >`
          SELECT result_id, request_hash
          FROM idempotency_records
          WHERE installation_id = ${input.installationId}
            AND operation = 'consent_update'
            AND idempotency_key = ${input.idempotencyKey}
        `;
        if (!existingIdempotency || existingIdempotency.request_hash !== input.requestHash) {
          throw new ConsentIdempotencyConflictError();
        }
        if (!existingIdempotency.result_id) {
          throw new Error("consent_idempotency_result_missing");
        }

        const [existingConsent] = await transaction<Readonly<{ id: string }>[]>`SELECT id
          FROM consent_records
          WHERE id = ${existingIdempotency.result_id}
            AND installation_id = ${input.installationId}`;
        if (!existingConsent) {
          throw new Error("consent_idempotency_receipt_incomplete");
        }
      } else {
        await transaction`
          INSERT INTO consent_records (
            id,
            installation_id,
            scope,
            scope_version,
            granted
          ) VALUES (
            ${consentRecordId},
            ${input.installationId},
            ${input.scope}::consent_scope,
            ${input.scopeVersion},
            ${input.granted}
          )
        `;
      }

      const records = await transaction<
        Readonly<{ granted: boolean; scope: ConsentScope; scope_version: string }>[]
      >`
        SELECT DISTINCT ON (scope) granted, scope::text AS scope, scope_version
        FROM consent_records
        WHERE installation_id = ${input.installationId}
        ORDER BY scope, recorded_at DESC, id DESC
      `;
      return records.map((record) => ({
        granted: record.granted,
        scope: record.scope,
        scopeVersion: record.scope_version,
      }));
    });
  }

  public async hasActiveConsent(
    identity: ConsentIdentity,
    scope: ConsentScope,
    scopeVersion: string,
  ): Promise<boolean> {
    return this.database.begin(async (transaction) => {
      await transaction`SELECT set_config('afterchime.user_id', ${identity.userId}, true)`;
      await transaction`SELECT set_config('afterchime.installation_id', ${identity.installationId}, true)`;
      const [current] = await transaction<Readonly<{ granted: boolean; scope_version: string }>[]>`
        SELECT granted, scope_version
        FROM consent_records
        WHERE installation_id = ${identity.installationId}
          AND scope = ${scope}::consent_scope
        ORDER BY recorded_at DESC, id DESC
        LIMIT 1
      `;
      return current?.granted === true && current.scope_version === scopeVersion;
    });
  }
}
