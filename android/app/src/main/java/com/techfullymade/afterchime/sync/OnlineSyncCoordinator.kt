package com.techfullymade.afterchime.sync

import com.techfullymade.afterchime.data.local.dao.DailyNotificationSummaryDao
import com.techfullymade.afterchime.data.local.entity.DailyNotificationSummaryEntity
import com.techfullymade.afterchime.identity.CredentialSession
import com.techfullymade.afterchime.identity.IdentityFailure
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.identity.OnlineIdentityRepository
import com.techfullymade.afterchime.identity.TokenAuthenticator
import com.techfullymade.afterchime.identity.TokenResult
import com.techfullymade.afterchime.network.AfterchimeApi
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ConsentResponse
import com.techfullymade.afterchime.network.ConsentUpdate
import com.techfullymade.afterchime.network.DailyAggregateResult
import com.techfullymade.afterchime.network.RegistrationRequest
import com.techfullymade.afterchime.settings.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class OnlineSyncOutcome { SUCCESS, RETRY, FAILURE }

internal fun interface OnlineSyncRunner {
  suspend fun sync(): OnlineSyncOutcome
}

internal interface OnlineIdentityClient {
  fun currentSession(): IdentityResult<CredentialSession?>

  fun register(
    request: RegistrationRequest,
    idempotencyKey: String,
  ): IdentityResult<CredentialSession>

  fun logout(): IdentityResult<Unit>
}

internal class RepositoryOnlineIdentityClient(
  private val repository: OnlineIdentityRepository,
) : OnlineIdentityClient {
  override fun currentSession(): IdentityResult<CredentialSession?> = repository.currentSession()

  override fun register(
    request: RegistrationRequest,
    idempotencyKey: String,
  ): IdentityResult<CredentialSession> = repository.register(request, idempotencyKey)

  override fun logout(): IdentityResult<Unit> = repository.logout()
}

internal fun interface OnlineTokenClient {
  fun accessToken(): TokenResult
}

internal class AuthenticatorOnlineTokenClient(
  private val authenticator: TokenAuthenticator,
) : OnlineTokenClient {
  override fun accessToken(): TokenResult = authenticator.accessToken()
}

internal interface OnlineSyncApi {
  fun updateConsent(
    request: ConsentUpdate,
    bearerToken: String,
    idempotencyKey: String,
  ): ApiResult<ConsentResponse>

  fun submitDailySummary(
    body: String,
    bearerToken: String,
  ): ApiResult<DailyAggregateResult>
}

internal class AfterchimeOnlineSyncApi(
  private val api: AfterchimeApi,
) : OnlineSyncApi {
  override fun updateConsent(
    request: ConsentUpdate,
    bearerToken: String,
    idempotencyKey: String,
  ): ApiResult<ConsentResponse> = api.updateConsent(request, bearerToken, idempotencyKey)

  override fun submitDailySummary(
    body: String,
    bearerToken: String,
  ): ApiResult<DailyAggregateResult> = api.submitDailyAggregate(body, bearerToken)
}

internal interface DailySummaryUploadStore {
  suspend fun pending(limit: Int): List<DailyNotificationSummaryEntity>

  suspend fun markUploaded(
    localDate: String,
    revision: Int,
    uploadedAtEpochMillis: Long,
  ): Int
}

internal class RoomDailySummaryUploadStore(
  private val dao: DailyNotificationSummaryDao,
) : DailySummaryUploadStore {
  override suspend fun pending(limit: Int): List<DailyNotificationSummaryEntity> =
    dao.pendingForUpload(limit)

  override suspend fun markUploaded(
    localDate: String,
    revision: Int,
    uploadedAtEpochMillis: Long,
  ): Int = dao.markUploaded(localDate, revision, uploadedAtEpochMillis)
}

/**
 * Deterministic controller for anonymous registration, explicit consent, and daily-total upload.
 * Call it only from a network-constrained IO worker.
 */
internal class OnlineSyncCoordinator(
  private val preferences: suspend () -> UserPreferences,
  private val identity: OnlineIdentityClient,
  private val tokenClient: OnlineTokenClient,
  private val api: OnlineSyncApi,
  private val summaries: DailySummaryUploadStore,
  private val state: OnlineSyncStateStore,
  private val registrationRequest: () -> RegistrationRequest,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) : OnlineSyncRunner {
  override suspend fun sync(): OnlineSyncOutcome {
    val currentPreferences = try {
      preferences()
    } catch (_: Exception) {
      return OnlineSyncOutcome.RETRY
    }
    val current = when (val result = identity.currentSession()) {
      is IdentityResult.Success -> result.value
      is IdentityResult.Failure -> return identityFailureOutcome(result.reason)
    }

    if (!currentPreferences.onlineFeaturesEnabled) {
      return disableOnlineFeatures(current)
    }

    val session = current ?: when (val registration = register()) {
      is SessionStep.Success -> registration.session
      is SessionStep.Done -> return registration.outcome
    }
    if (!state.bindInstallation(session.installationId)) return OnlineSyncOutcome.FAILURE
    if (!clearCompletedRegistrationAttempt()) return OnlineSyncOutcome.FAILURE

    val token = when (val result = tokenClient.accessToken()) {
      is TokenResult.Available -> result.accessToken
      is TokenResult.Failure -> return identityFailureOutcome(result.reason)
    }

    for ((scope, target) in enabledConsentTargets(currentPreferences)) {
      val outcome = syncConsent(scope, target, token)
      if (outcome != OnlineSyncOutcome.SUCCESS) return outcome
    }

    if (!currentPreferences.notificationAggregateSharingEnabled) return OnlineSyncOutcome.SUCCESS
    return uploadDailySummaries(token)
  }

  private fun register(): SessionStep {
    val idempotencyKey = when (val result = state.registrationIdempotencyKey()) {
      is SyncStateRead.Available -> result.value
      SyncStateRead.Unavailable -> return SessionStep.Done(OnlineSyncOutcome.FAILURE)
    }
    val request = try {
      registrationRequest()
    } catch (_: Exception) {
      return SessionStep.Done(OnlineSyncOutcome.FAILURE)
    }
    return when (val result = identity.register(request, idempotencyKey)) {
      is IdentityResult.Success -> {
        if (!state.bindInstallation(result.value.installationId)) {
          SessionStep.Done(OnlineSyncOutcome.FAILURE)
        } else if (!state.acknowledgeRegistration(idempotencyKey)) {
          SessionStep.Done(OnlineSyncOutcome.FAILURE)
        } else {
          SessionStep.Success(result.value)
        }
      }
      is IdentityResult.Failure -> {
        if (result.reason == IdentityFailure.Api(ApiFailure.Conflict)) {
          if (!state.acknowledgeRegistration(idempotencyKey)) {
            SessionStep.Done(OnlineSyncOutcome.FAILURE)
          } else {
            SessionStep.Done(OnlineSyncOutcome.RETRY)
          }
        } else {
          SessionStep.Done(identityFailureOutcome(result.reason))
        }
      }
    }
  }

  private fun clearCompletedRegistrationAttempt(): Boolean =
    when (val result = state.pendingRegistrationIdempotencyKey()) {
      is SyncStateRead.Available -> result.value?.let(state::acknowledgeRegistration) ?: true
      SyncStateRead.Unavailable -> false
    }

  private fun enabledConsentTargets(preferences: UserPreferences): List<Pair<OnlineConsentScope, Boolean>> =
    listOf(
      OnlineConsentScope.ESSENTIAL_ONLINE to true,
      OnlineConsentScope.PRODUCT_ANALYTICS to preferences.productAnalyticsEnabled,
      OnlineConsentScope.NOTIFICATION_AGGREGATES to preferences.notificationAggregateSharingEnabled,
    )

  private fun disabledConsentTargets(): List<Pair<OnlineConsentScope, Boolean>> =
    listOf(
      OnlineConsentScope.PRODUCT_ANALYTICS to false,
      OnlineConsentScope.NOTIFICATION_AGGREGATES to false,
      OnlineConsentScope.ESSENTIAL_ONLINE to false,
    )

  private fun syncConsent(
    scope: OnlineConsentScope,
    target: Boolean,
    token: String,
  ): OnlineSyncOutcome {
    val mutation = when (val result = state.consentMutation(scope, target)) {
      is SyncStateRead.Available -> result.value ?: return OnlineSyncOutcome.SUCCESS
      SyncStateRead.Unavailable -> return OnlineSyncOutcome.FAILURE
    }
    val response = api.updateConsent(
      ConsentUpdate(granted = target, scope = scope.wireName, scopeVersion = CONSENT_SCOPE_VERSION),
      token,
      mutation.idempotencyKey,
    )
    response.failure?.let { return apiFailureOutcome(it) }
    val acknowledged = response.value?.consents?.singleOrNull { consent -> consent.scope == scope.wireName }
    if (
      acknowledged?.granted != target ||
      acknowledged.scopeVersion != CONSENT_SCOPE_VERSION ||
      !state.acknowledgeConsent(mutation)
    ) {
      return OnlineSyncOutcome.FAILURE
    }
    return OnlineSyncOutcome.SUCCESS
  }

  private suspend fun uploadDailySummaries(token: String): OnlineSyncOutcome {
    val pending = try {
      summaries.pending(MAX_DAILY_UPLOADS)
    } catch (_: Exception) {
      return OnlineSyncOutcome.RETRY
    }
    if (pending.size > MAX_DAILY_UPLOADS) return OnlineSyncOutcome.FAILURE
    for (summary in pending) {
      val body = try {
        encodeDailySummary(summary)
      } catch (_: Exception) {
        return OnlineSyncOutcome.FAILURE
      }
      val response = api.submitDailySummary(body, token)
      response.failure?.let { return apiFailureOutcome(it) }
      val result = response.value ?: return OnlineSyncOutcome.FAILURE
      if (
        result.localDate != summary.localDate ||
        result.revision != summary.revision ||
        result.status !in setOf("accepted", "duplicate")
      ) {
        return OnlineSyncOutcome.FAILURE
      }
      val marked = try {
        summaries.markUploaded(summary.localDate, summary.revision, nowMillis())
      } catch (_: Exception) {
        return OnlineSyncOutcome.RETRY
      }
      if (marked !in 0..1) return OnlineSyncOutcome.FAILURE
    }
    return OnlineSyncOutcome.SUCCESS
  }

  private fun disableOnlineFeatures(session: CredentialSession?): OnlineSyncOutcome {
    if (session == null) {
      return if (state.clear()) OnlineSyncOutcome.SUCCESS else OnlineSyncOutcome.FAILURE
    }
    if (!state.bindInstallation(session.installationId)) return OnlineSyncOutcome.FAILURE
    if (!clearCompletedRegistrationAttempt()) return OnlineSyncOutcome.FAILURE
    val token = when (val result = tokenClient.accessToken()) {
      is TokenResult.Available -> result.accessToken
      is TokenResult.Failure -> return identityFailureOutcome(result.reason)
    }
    for ((scope, target) in disabledConsentTargets()) {
      val outcome = syncConsent(scope, target, token)
      if (outcome != OnlineSyncOutcome.SUCCESS) return outcome
    }
    return when (val result = identity.logout()) {
      is IdentityResult.Success -> if (state.clear()) OnlineSyncOutcome.SUCCESS else OnlineSyncOutcome.FAILURE
      is IdentityResult.Failure -> {
        if (result.reason == IdentityFailure.Api(ApiFailure.Unauthorized)) {
          if (state.clear()) OnlineSyncOutcome.SUCCESS else OnlineSyncOutcome.FAILURE
        } else {
          identityFailureOutcome(result.reason)
        }
      }
    }
  }

  private fun encodeDailySummary(summary: DailyNotificationSummaryEntity): String =
    JSONObject()
      .put("category_counts", JSONObject(summary.categoryCountsJson))
      .put("consent_scope_version", CONSENT_SCOPE_VERSION)
      .put("eligible_count", summary.eligibleCount)
      .put("game_pressure", summary.gamePressure)
      .put("hourly_buckets", JSONArray(summary.hourlyBucketsJson))
      .put("local_date", summary.localDate)
      .put("observation_completeness", summary.observationCompleteness)
      .put("revision", summary.revision)
      .put("rules_version", summary.rulesVersion)
      .put("timezone_offset_minutes", summary.timezoneOffsetMinutes)
      .toString()

  private sealed interface SessionStep {
    data class Success(val session: CredentialSession) : SessionStep
    data class Done(val outcome: OnlineSyncOutcome) : SessionStep
  }

  private companion object {
    const val CONSENT_SCOPE_VERSION = "1"
    const val MAX_DAILY_UPLOADS = 20

    fun identityFailureOutcome(failure: IdentityFailure): OnlineSyncOutcome = when (failure) {
      IdentityFailure.ProtectedStorageUnavailable,
      IdentityFailure.RejectedSession,
      -> OnlineSyncOutcome.FAILURE
      is IdentityFailure.Api -> apiFailureOutcome(failure.failure)
    }

    fun apiFailureOutcome(failure: ApiFailure): OnlineSyncOutcome = when (failure) {
      ApiFailure.Network,
      ApiFailure.Http,
      ApiFailure.Unauthorized,
      ApiFailure.ConsentInactive,
      -> OnlineSyncOutcome.RETRY
      ApiFailure.InvalidUrl,
      ApiFailure.UnsupportedSchemaVersion,
      ApiFailure.MalformedResponse,
      ApiFailure.Conflict,
      -> OnlineSyncOutcome.FAILURE
    }
  }
}
