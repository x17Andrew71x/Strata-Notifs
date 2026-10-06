package com.techfullymade.afterchime.identity

import java.time.Clock

sealed interface TokenResult {
  data class Available(val accessToken: String) : TokenResult
  data class Failure(val reason: IdentityFailure) : TokenResult
}

/** Explicit token lookup/refresh seam; intentionally not an interceptor or runtime activation hook. */
class TokenAuthenticator(
  private val repository: OnlineIdentityRepository,
  private val clock: Clock = Clock.systemUTC(),
  private val refreshSkewMillis: Long = 0L,
) {
  init {
    require(refreshSkewMillis >= 0L)
  }

  @Synchronized
  fun accessToken(): TokenResult {
    return when (val current = repository.currentSession()) {
      is IdentityResult.Failure -> TokenResult.Failure(current.reason)
      is IdentityResult.Success -> {
        val session = current.value
          ?: return TokenResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
        if (session.expiresAtEpochMillis > safeThreshold()) {
          TokenResult.Available(session.accessToken)
        } else {
          when (val refreshed = repository.refresh()) {
            is IdentityResult.Success -> TokenResult.Available(refreshed.value.accessToken)
            is IdentityResult.Failure -> TokenResult.Failure(refreshed.reason)
          }
        }
      }
    }
  }

  private fun safeThreshold(): Long = try {
    Math.addExact(clock.millis(), refreshSkewMillis)
  } catch (_: ArithmeticException) {
    Long.MAX_VALUE
  }
}
