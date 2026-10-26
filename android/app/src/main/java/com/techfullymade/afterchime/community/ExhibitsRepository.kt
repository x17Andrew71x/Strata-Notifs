package com.techfullymade.afterchime.community

import android.content.Context
import android.content.SharedPreferences
import com.techfullymade.afterchime.network.AfterchimeApi
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ExhibitApiItem
import com.techfullymade.afterchime.network.ExhibitContributionResponse
import com.techfullymade.afterchime.network.ExhibitsStateResponse
import com.techfullymade.afterchime.network.PastExhibitApiItem
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ExhibitsStatus { DISABLED, LOADING, READY, OFFLINE }

internal data class ExhibitSnapshot(
  val id: String,
  val title: String,
  val seasonLabel: String,
  val imagePath: String,
  val progressBasisPoints: Int,
  val personalContribution: Int,
  val personalMinimum: Int,
  val daysRemaining: Int?,
  val communityComplete: Boolean,
  val qualified: Boolean,
  val unlocked: Boolean,
  val closed: Boolean,
)

internal data class PastExhibitSnapshot(
  val id: String,
  val title: String,
  val seasonLabel: String,
  val imagePath: String,
  val unlocked: Boolean,
  val lockedReason: String?,
)

internal data class ExhibitsSnapshot(
  val status: ExhibitsStatus,
  val featured: ExhibitSnapshot? = null,
  val archive: ExhibitSnapshot? = null,
  val past: List<PastExhibitSnapshot> = emptyList(),
  val bankedEnergy: Int = 0,
  val contributionInFlight: Boolean = false,
  val version: Int = 0,
) {
  companion object {
    val Disabled = ExhibitsSnapshot(ExhibitsStatus.DISABLED)
    val Loading = ExhibitsSnapshot(ExhibitsStatus.LOADING)
    val Offline = ExhibitsSnapshot(ExhibitsStatus.OFFLINE)
  }
}

internal fun interface CommunityTokenProvider {
  suspend fun accessToken(): String?
}

internal interface ExhibitsApiGateway {
  fun getExhibits(localDate: String, bearerToken: String): ApiResult<ExhibitsStateResponse>

  fun contributeExhibit(
    exhibitId: String,
    localDate: String,
    units: Int,
    bearerToken: String,
    idempotencyKey: String,
  ): ApiResult<ExhibitContributionResponse>
}

internal class NetworkExhibitsApi(private val api: AfterchimeApi) : ExhibitsApiGateway {
  override fun getExhibits(localDate: String, bearerToken: String) =
    api.getExhibits(localDate, bearerToken)

  override fun contributeExhibit(
    exhibitId: String,
    localDate: String,
    units: Int,
    bearerToken: String,
    idempotencyKey: String,
  ) = api.contributeExhibit(exhibitId, localDate, units, bearerToken, idempotencyKey)
}

internal data class PendingExhibitContribution(
  val exhibitId: String,
  val localDate: String,
  val units: Int,
  val idempotencyKey: String,
)

internal interface ExhibitContributionAttemptStore {
  fun pending(): PendingExhibitContribution?
  fun begin(exhibitId: String, localDate: String, units: Int): PendingExhibitContribution?
  fun acknowledge(idempotencyKey: String): Boolean
}

internal class SharedPreferencesExhibitContributionAttemptStore(
  context: Context,
  private val preferences: SharedPreferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
  private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) : ExhibitContributionAttemptStore {
  override fun pending(): PendingExhibitContribution? {
    return try {
      if (!preferences.contains(KEY_IDEMPOTENCY)) {
        null
      } else {
        val exhibitId = preferences.getString(KEY_EXHIBIT, null)
        val localDate = preferences.getString(KEY_DATE, null)
        val units = preferences.getInt(KEY_UNITS, -1)
        val idempotencyKey = preferences.getString(KEY_IDEMPOTENCY, null)
        if (exhibitId == null || localDate == null || idempotencyKey == null) {
          null
        } else {
          PendingExhibitContribution(exhibitId, localDate, units, idempotencyKey).takeIf(::valid)
        }
      }
    } catch (_: RuntimeException) {
      null
    }
  }

  override fun begin(
    exhibitId: String,
    localDate: String,
    units: Int,
  ): PendingExhibitContribution? {
    pending()?.let { return it.takeIf { value -> value.exhibitId == exhibitId && value.localDate == localDate } }
    val value = PendingExhibitContribution(exhibitId, localDate, units, newIdempotencyKey())
    if (!valid(value)) return null
    return try {
      if (
        preferences.edit()
          .putString(KEY_EXHIBIT, value.exhibitId)
          .putString(KEY_DATE, value.localDate)
          .putInt(KEY_UNITS, value.units)
          .putString(KEY_IDEMPOTENCY, value.idempotencyKey)
          .commit()
      ) value else null
    } catch (_: RuntimeException) {
      null
    }
  }

  override fun acknowledge(idempotencyKey: String): Boolean {
    val current = pending() ?: return false
    if (current.idempotencyKey != idempotencyKey) return false
    return try {
      preferences.edit().clear().commit() && pending() == null
    } catch (_: RuntimeException) {
      false
    }
  }

  private fun valid(value: PendingExhibitContribution): Boolean =
    value.exhibitId.matches(Regex("^[a-z0-9-]{1,80}$")) &&
      runCatching { java.time.LocalDate.parse(value.localDate) }.isSuccess &&
      value.units in 1..100_000 &&
      runCatching { UUID.fromString(value.idempotencyKey) }.isSuccess

  private companion object {
    const val PREFERENCES_NAME = "afterchime_exhibit_contribution"
    const val KEY_EXHIBIT = "exhibit_id"
    const val KEY_DATE = "local_date"
    const val KEY_UNITS = "units"
    const val KEY_IDEMPOTENCY = "idempotency_key"
  }
}

internal class ExhibitsRepository(
  private val api: ExhibitsApiGateway,
  private val tokenProvider: CommunityTokenProvider,
  private val attempts: ExhibitContributionAttemptStore,
) {
  private val mutableState = MutableStateFlow(ExhibitsSnapshot.Loading)
  private val mutationMutex = Mutex()
  val state: StateFlow<ExhibitsSnapshot> = mutableState.asStateFlow()

  suspend fun refresh(
    localDate: String,
    surplusEnergy: Int,
    onlineEnabled: Boolean,
  ): Boolean = withContext(Dispatchers.IO) {
    mutationMutex.withLock {
      if (!onlineEnabled) {
        mutableState.value = ExhibitsSnapshot.Disabled
        return@withLock false
      }
      val token = tokenProvider.accessToken()
      if (token == null) {
        if (mutableState.value.status != ExhibitsStatus.READY) mutableState.value = ExhibitsSnapshot.Offline
        return@withLock false
      }
      reconcilePending(token, localDate)
      val response = api.getExhibits(localDate, token)
      val value = response.value
      if (value == null) {
        if (mutableState.value.status != ExhibitsStatus.READY) mutableState.value = ExhibitsSnapshot.Offline
        return@withLock false
      }
      val next = value.toSnapshot(surplusEnergy, contributionInFlight = false)
      if (mutableState.value.status != ExhibitsStatus.READY || next.version >= mutableState.value.version) {
        mutableState.value = next
      }
      true
    }
  }

  suspend fun contribute(
    exhibitId: String,
    localDate: String,
    surplusEnergy: Int,
  ): Boolean = withContext(Dispatchers.IO) {
    mutationMutex.withLock {
      val current = mutableState.value
      if (current.status != ExhibitsStatus.READY || current.bankedEnergy <= 0 || current.contributionInFlight) {
        return@withLock false
      }
      val attempt = attempts.begin(exhibitId, localDate, current.bankedEnergy) ?: return@withLock false
      mutableState.value = current.copy(contributionInFlight = true)
      val token = tokenProvider.accessToken()
      if (token == null) {
        mutableState.value = current.copy(contributionInFlight = false)
        return@withLock false
      }
      val response: ApiResult<ExhibitContributionResponse> = api.contributeExhibit(
        exhibitId = attempt.exhibitId,
        localDate = attempt.localDate,
        units = attempt.units,
        bearerToken = token,
        idempotencyKey = attempt.idempotencyKey,
      )
      val result = response.value
      if (result == null || result.status !in setOf("accepted", "duplicate")) {
        if (response.failure.isDefinitiveRejection()) {
          attempts.acknowledge(attempt.idempotencyKey)
        }
        mutableState.value = current.copy(contributionInFlight = false)
        return@withLock false
      }
      if (!attempts.acknowledge(attempt.idempotencyKey)) {
        mutableState.value = current.copy(contributionInFlight = false)
        return@withLock false
      }
      mutableState.value = result.state.toSnapshot(surplusEnergy, contributionInFlight = false)
      true
    }
  }

  private fun reconcilePending(token: String, currentLocalDate: String) {
    val pending = attempts.pending() ?: return
    val ageDays = runCatching {
      ChronoUnit.DAYS.between(LocalDate.parse(pending.localDate), LocalDate.parse(currentLocalDate))
    }.getOrNull()
    if (ageDays == null || ageDays !in 0..1) {
      attempts.acknowledge(pending.idempotencyKey)
      return
    }
    val response = api.contributeExhibit(
      exhibitId = pending.exhibitId,
      localDate = pending.localDate,
      units = pending.units,
      bearerToken = token,
      idempotencyKey = pending.idempotencyKey,
    )
    if (response.value?.status in setOf("accepted", "duplicate") || response.failure.isDefinitiveRejection()) {
      attempts.acknowledge(pending.idempotencyKey)
    }
  }
}

private fun ApiFailure?.isDefinitiveRejection(): Boolean =
  this in setOf(ApiFailure.Conflict, ApiFailure.ConsentInactive, ApiFailure.Unauthorized)

private fun ExhibitsStateResponse.toSnapshot(
  surplusEnergy: Int,
  contributionInFlight: Boolean,
): ExhibitsSnapshot {
  return ExhibitsSnapshot(
    status = ExhibitsStatus.READY,
    featured = featured?.toSnapshot(),
    archive = archive?.toSnapshot(),
    past = past.map(PastExhibitApiItem::toSnapshot),
    bankedEnergy = availableExhibitEnergy(
      surplusEnergy = surplusEnergy,
      todayContributedUnits = todayContributedUnits,
      dailyContributionCap = dailyContributionCap,
    ),
    contributionInFlight = contributionInFlight,
    version = version,
  )
}

internal fun availableExhibitEnergy(
  surplusEnergy: Int,
  todayContributedUnits: Int,
  dailyContributionCap: Int,
): Int {
  val remainingDaily = (dailyContributionCap - todayContributedUnits).coerceAtLeast(0)
  val unspentSurplus = (surplusEnergy - todayContributedUnits).coerceAtLeast(0)
  return minOf(remainingDaily, unspentSurplus)
}

private fun ExhibitApiItem.toSnapshot() = ExhibitSnapshot(
  id,
  title,
  seasonLabel,
  imagePath,
  progressBasisPoints,
  personalContribution,
  personalMinimum,
  daysRemaining,
  communityComplete,
  qualified,
  unlocked,
  closed,
)

private fun PastExhibitApiItem.toSnapshot() = PastExhibitSnapshot(
  id,
  title,
  seasonLabel,
  imagePath,
  unlocked,
  lockedReason,
)
