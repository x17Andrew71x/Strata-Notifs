package com.techfullymade.afterchime

import com.techfullymade.afterchime.identity.CredentialSession
import com.techfullymade.afterchime.identity.IdentityFailure
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.identity.TokenResult
import com.techfullymade.afterchime.network.ApiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevelopmentFeedbackTokenRecoveryTest {
  @Test
  fun `unavailable protected storage is reset before one bounded registration`() {
    var resets = 0
    var registrations = 0
    val recovery = DevelopmentFeedbackTokenRecovery(
      currentSession = {
        IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      },
      resetUnavailableCredentials = {
        resets += 1
        true
      },
      registerSession = {
        registrations += 1
        true
      },
      tokenLookup = { TokenResult.Available("recovered-access") },
    )

    assertEquals("recovered-access", recovery.accessToken())
    assertEquals(1, resets)
    assertEquals(1, registrations)
  }

  @Test
  fun `revoked refresh credential is replaced once in the same request`() {
    val sessions = ArrayDeque<IdentityResult<CredentialSession?>>()
    sessions += IdentityResult.Success(session())
    sessions += IdentityResult.Success(null)
    val tokens = ArrayDeque<TokenResult>()
    tokens += TokenResult.Failure(IdentityFailure.Api(ApiFailure.Unauthorized))
    tokens += TokenResult.Available("replacement-access")
    var registrations = 0

    val recovery = DevelopmentFeedbackTokenRecovery(
      currentSession = { sessions.removeFirst() },
      resetUnavailableCredentials = { error("unauthorized refresh already clears storage") },
      registerSession = {
        registrations += 1
        true
      },
      tokenLookup = { tokens.removeFirst() },
    )

    assertEquals("replacement-access", recovery.accessToken())
    assertEquals(1, registrations)
  }

  @Test
  fun `uncertain API failure is preserved without reset or retry`() {
    var resets = 0
    var registrations = 0
    var tokenLookups = 0
    val recovery = DevelopmentFeedbackTokenRecovery(
      currentSession = { IdentityResult.Success(session()) },
      resetUnavailableCredentials = {
        resets += 1
        true
      },
      registerSession = {
        registrations += 1
        true
      },
      tokenLookup = {
        tokenLookups += 1
        TokenResult.Failure(IdentityFailure.Api(ApiFailure.Network))
      },
    )

    assertNull(recovery.accessToken())
    assertEquals(0, resets)
    assertEquals(0, registrations)
    assertEquals(1, tokenLookups)
  }

  private fun session() = CredentialSession(
    installationId = "installation-1",
    accessToken = "access-1",
    refreshToken = "r".repeat(43),
    expiresAtEpochMillis = Long.MAX_VALUE,
  )
}
