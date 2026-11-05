package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionCoordinatorTest {
  private val session = CredentialSession(
    installationId = "installation",
    accessToken = "access",
    refreshToken = "refresh",
    expiresAtEpochMillis = Long.MAX_VALUE,
  )

  @Test
  fun `deletes only after a current identity and valid token are available`() {
    var receivedToken: String? = null
    val coordinator = AccountDeletionCoordinator(
      currentSession = { IdentityResult.Success(session) },
      accessToken = { TokenResult.Available("fresh-access") },
      deleteAccount = { token ->
        receivedToken = token
        ApiResult.success(Unit)
      },
    )

    assertEquals(AccountDeletionOutcome.DELETED, coordinator.delete())
    assertEquals("fresh-access", receivedToken)
  }

  @Test
  fun `does not call the server when no online account exists`() {
    var tokenRequested = false
    var deletionRequested = false
    val coordinator = AccountDeletionCoordinator(
      currentSession = { IdentityResult.Success(null) },
      accessToken = {
        tokenRequested = true
        TokenResult.Available("unused")
      },
      deleteAccount = {
        deletionRequested = true
        ApiResult.success(Unit)
      },
    )

    assertEquals(AccountDeletionOutcome.NO_ACCOUNT, coordinator.delete())
    assertFalse(tokenRequested)
    assertFalse(deletionRequested)
  }

  @Test
  fun `preserves local state on uncertain server failure`() {
    var deletionRequested = false
    val coordinator = AccountDeletionCoordinator(
      currentSession = { IdentityResult.Success(session) },
      accessToken = { TokenResult.Available("fresh-access") },
      deleteAccount = {
        deletionRequested = true
        ApiResult.failure(ApiFailure.Network)
      },
    )

    assertEquals(AccountDeletionOutcome.RETRY, coordinator.delete())
    assertTrue(deletionRequested)
  }

  @Test
  fun `fails closed when protected credentials are unavailable`() {
    val coordinator = AccountDeletionCoordinator(
      currentSession = { IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable) },
      accessToken = { TokenResult.Available("unused") },
      deleteAccount = { ApiResult.success(Unit) },
    )

    assertEquals(AccountDeletionOutcome.FAILURE, coordinator.delete())
  }
}
