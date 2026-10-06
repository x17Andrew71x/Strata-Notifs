package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.AfterchimeApi
import com.techfullymade.afterchime.network.LogoutRequest
import com.techfullymade.afterchime.network.RefreshRequest
import com.techfullymade.afterchime.network.RegistrationRequest
import com.techfullymade.afterchime.network.SessionResponse
import java.time.Clock

sealed interface IdentityFailure {
  data class Api(val failure: ApiFailure) : IdentityFailure
  data object ProtectedStorageUnavailable : IdentityFailure
  data object RejectedSession : IdentityFailure
}

sealed interface IdentityResult<out T> {
  data class Success<T>(val value: T) : IdentityResult<T>
  data class Failure(val reason: IdentityFailure) : IdentityResult<Nothing>
}

interface IdentityGateway {
  fun register(request: RegistrationRequest, idempotencyKey: String): ApiResult<SessionResponse>
  fun refresh(request: RefreshRequest): ApiResult<SessionResponse>
  fun logout(request: LogoutRequest): ApiResult<Unit>
}

class AfterchimeIdentityGateway(private val api: AfterchimeApi) : IdentityGateway {
  override fun register(request: RegistrationRequest, idempotencyKey: String) = api.register(request, idempotencyKey)
  override fun refresh(request: RefreshRequest) = api.refresh(request)
  override fun logout(request: LogoutRequest) = api.logout(request)
}

/** Inert until a method is explicitly invoked; owns only anonymous identity credentials. */
class OnlineIdentityRepository(
  private val gateway: IdentityGateway,
  private val credentials: CredentialStore,
  private val clock: Clock = Clock.systemUTC(),
) {
  @Synchronized
  fun register(request: RegistrationRequest, idempotencyKey: String): IdentityResult<CredentialSession> {
    when (credentials.read()) {
      CredentialRead.Unavailable -> return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      CredentialRead.Missing, is CredentialRead.Available -> Unit
    }
    val response = gateway.register(request, idempotencyKey)
    response.failure?.let { return IdentityResult.Failure(IdentityFailure.Api(it)) }
    val session = response.value?.toCredentialSession(clock) ?: return rejected()
    if (!credentials.save(session)) return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
    return IdentityResult.Success(session)
  }

  @Synchronized
  fun currentSession(): IdentityResult<CredentialSession?> = when (val stored = credentials.read()) {
    CredentialRead.Missing -> IdentityResult.Success(null)
    CredentialRead.Unavailable -> IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
    is CredentialRead.Available -> IdentityResult.Success(stored.session)
  }

  /** Exactly one refresh call per invocation. A 401/replay clears; all uncertain failures preserve. */
  @Synchronized
  fun refresh(): IdentityResult<CredentialSession> {
    val prior = when (val stored = credentials.read()) {
      CredentialRead.Missing -> return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      CredentialRead.Unavailable -> return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      is CredentialRead.Available -> stored.session
    }
    val response = gateway.refresh(RefreshRequest(prior.refreshToken))
    response.failure?.let { failure ->
      if (failure == ApiFailure.Unauthorized && !credentials.clear()) {
        return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      }
      return IdentityResult.Failure(IdentityFailure.Api(failure))
    }
    val replacement = response.value?.toCredentialSession(clock) ?: return rejected()
    if (replacement.installationId != prior.installationId) return rejected()
    if (!credentials.save(replacement)) return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
    return IdentityResult.Success(replacement)
  }

  @Synchronized
  fun logout(): IdentityResult<Unit> {
    val prior = when (val stored = credentials.read()) {
      CredentialRead.Missing -> return IdentityResult.Success(Unit)
      CredentialRead.Unavailable -> return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
      is CredentialRead.Available -> stored.session
    }
    val response = gateway.logout(LogoutRequest(prior.refreshToken))
    val failure = response.failure
    if (failure != null && failure != ApiFailure.Unauthorized) {
      return IdentityResult.Failure(IdentityFailure.Api(failure))
    }
    if (!credentials.clear()) return IdentityResult.Failure(IdentityFailure.ProtectedStorageUnavailable)
    return if (failure == null) IdentityResult.Success(Unit) else IdentityResult.Failure(IdentityFailure.Api(failure))
  }

  private fun SessionResponse.toCredentialSession(clock: Clock): CredentialSession? {
    if (installationId.isBlank() || accessToken.isBlank() || refreshToken.isBlank() || expiresInSeconds <= 0) return null
    val expiry = runCatching { Math.addExact(clock.millis(), Math.multiplyExact(expiresInSeconds.toLong(), 1_000L)) }
      .getOrNull() ?: return null
    return CredentialSession(installationId, accessToken, refreshToken, expiry)
  }

  private fun rejected() = IdentityResult.Failure(IdentityFailure.RejectedSession)
}
