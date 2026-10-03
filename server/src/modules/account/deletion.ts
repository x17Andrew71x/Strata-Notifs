import type { DatabaseClient } from "../../db/client.js";
import type { AccessTokenClaims } from "../../security/tokens.js";

export type AccountDeletionResult = Readonly<{
  deleted: boolean;
}>;

export class AccountDeletionRepository {
  public constructor(private readonly database: DatabaseClient) {}

  public async deleteAccount(identity: AccessTokenClaims): Promise<AccountDeletionResult> {
    return this.database.begin(async (transaction) => {
      await transaction`SELECT set_config('afterchime.user_id', ${identity.userId}, true)`;
      await transaction`SELECT set_config('afterchime.installation_id', ${identity.installationId}, true)`;
      const [result] = await transaction<Readonly<{ deleted: boolean }>[]>`
        SELECT public.afterchime_delete_account(
          ${identity.userId}::uuid,
          ${identity.installationId}::uuid
        ) AS deleted
      `;
      return { deleted: result?.deleted === true };
    });
  }
}

export class AccountDeletionService {
  public constructor(private readonly repository: AccountDeletionRepository) {}

  public async deleteAccount(identity: AccessTokenClaims): Promise<AccountDeletionResult> {
    return this.repository.deleteAccount(identity);
  }
}
