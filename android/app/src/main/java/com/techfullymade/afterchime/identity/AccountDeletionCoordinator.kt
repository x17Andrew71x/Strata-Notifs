package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult

internal enum class AccountDeletionOutcome { DELETED, NO_ACCOUNT, RETRY, FAILURE }

/** Server-first account erasure. Local app-data reset is owned by the confirmed Activity action. */
internal class AccountDeletionCoordinator(
  private val currentSession: () -> IdentityResult<CredentialSession?>,
  private val accessToken: () -> TokenResult,
  private val deleteAccount: (String) -> ApiResult<Unit>,
) {
  fun delete(): AccountDeletionOutcome {
    when (val current = currentSession()) {
      is IdentityResult.Failure -> return current.reason.toDeletionOutcome()
      is IdentityResult.Success -> if (current.value == null) return AccountDeletionOutcome.NO_ACCOUNT
    }

    val token = when (val result = accessToken()) {
      is TokenResult.Available -> result.accessToken
      is TokenResult.Failure -> return result.reason.toDeletionOutcome()
    }
    val result = deleteAccount(token)
    if (result.value != null) return AccountDeletionOutcome.DELETED
    return result.failure.toDeletionOutcome()
  }
}

private fun IdentityFailure.toDeletionOutcome(): AccountDeletionOutcome = when (this) {
  is IdentityFailure.Api -> failure.toDeletionOutcome()
  IdentityFailure.ProtectedStorageUnavailable,
  IdentityFailure.RejectedSession,
  -> AccountDeletionOutcome.FAILURE
}

private fun ApiFailure?.toDeletionOutcome(): AccountDeletionOutcome = when (this) {
  ApiFailure.Network,
  ApiFailure.Http,
  -> AccountDeletionOutcome.RETRY
  else -> AccountDeletionOutcome.FAILURE
}
