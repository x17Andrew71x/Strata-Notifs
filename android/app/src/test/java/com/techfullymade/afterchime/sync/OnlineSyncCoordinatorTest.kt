package com.techfullymade.afterchime.sync

import com.techfullymade.afterchime.data.local.entity.DailyNotificationSummaryEntity
import com.techfullymade.afterchime.identity.CredentialSession
import com.techfullymade.afterchime.identity.IdentityFailure
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.identity.TokenResult
import com.techfullymade.afterchime.network.AnalyticsBatchResult
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ConsentResponse
import com.techfullymade.afterchime.network.ConsentState
import com.techfullymade.afterchime.network.ConsentUpdate
import com.techfullymade.afterchime.network.DailyAggregateResult
import com.techfullymade.afterchime.network.RegistrationRequest
import com.techfullymade.afterchime.settings.UserPreferences
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSyncCoordinatorTest {
  @Test
  fun `fresh defaults are network inert and clear stale non-secret state`() = runBlocking {
    val fixture = Fixture(UserPreferences())

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(0, fixture.identity.registerCalls)
    assertEquals(0, fixture.identity.logoutCalls)
    assertEquals(0, fixture.api.consentCalls.size)
    assertEquals(0, fixture.api.dailyBodies.size)
    assertEquals(0, fixture.token.calls)
    assertEquals(1, fixture.analytics.discardCalls)
  }

  @Test
  fun `registration retries reuse durable key and acknowledged consents do not duplicate`() = runBlocking {
    val fixture = Fixture(
      UserPreferences(
        onlineFeaturesEnabled = true,
        productAnalyticsEnabled = false,
        notificationAggregateSharingEnabled = false,
      ),
    )
    fixture.identity.registrationResults += IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Network))
    fixture.identity.registrationResults += IdentityResult.Success(SESSION)

    assertEquals(OnlineSyncOutcome.RETRY, fixture.coordinator().sync())
    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(listOf(ID_ONE, ID_ONE), fixture.identity.registrationKeys)
    assertEquals(
      listOf(
        OnlineConsentScope.ESSENTIAL_ONLINE.wireName to true,
        OnlineConsentScope.PRODUCT_ANALYTICS.wireName to false,
        OnlineConsentScope.NOTIFICATION_AGGREGATES.wireName to false,
      ),
      fixture.api.consentCalls.map { it.request.scope to it.request.granted },
    )

    fixture.api.consentCalls.clear()
    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertTrue(fixture.api.consentCalls.isEmpty())
  }

  @Test
  fun `registration conflict retires only the conflicting key before retry`() = runBlocking {
    val fixture = Fixture(UserPreferences(onlineFeaturesEnabled = true))
    fixture.identity.registrationResults += IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Conflict))
    fixture.identity.registrationResults += IdentityResult.Success(SESSION)

    assertEquals(OnlineSyncOutcome.RETRY, fixture.coordinator().sync())
    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(2, fixture.identity.registrationKeys.size)
    assertNotEquals(fixture.identity.registrationKeys[0], fixture.identity.registrationKeys[1])
  }

  @Test
  fun `daily totals upload only after matching consent and exact response`() = runBlocking {
    val summary = summary()
    val fixture = Fixture(
      UserPreferences(
        onlineFeaturesEnabled = true,
        notificationAggregateSharingEnabled = true,
      ),
      currentSession = SESSION,
      summaries = mutableListOf(summary),
    )

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(listOf(summary.localDate to summary.revision), fixture.summaryStore.marked)
    val payload = JSONObject(fixture.api.dailyBodies.single())
    assertEquals(
      setOf(
        "category_counts",
        "consent_scope_version",
        "eligible_count",
        "game_pressure",
        "hourly_buckets",
        "local_date",
        "observation_completeness",
        "revision",
        "rules_version",
        "timezone_offset_minutes",
      ),
      payload.keys().asSequence().toSet(),
    )
    assertEquals(3, payload.getInt("eligible_count"))
    assertEquals(3, payload.getJSONObject("category_counts").keys().asSequence().sumOf { payload.getJSONObject("category_counts").getInt(it) })
    assertEquals(3, (0 until payload.getJSONArray("hourly_buckets").length()).sumOf { payload.getJSONArray("hourly_buckets").getJSONObject(it).getInt("count") })
    assertEquals("1", payload.getString("consent_scope_version"))
    val rendered = payload.toString()
    assertFalse(rendered.contains(SOURCE_SENTINEL))
    assertFalse(rendered.contains("source"))
    assertFalse(rendered.contains("package"))
    assertFalse(rendered.contains("content"))
  }

  @Test
  fun `duplicate exact response marks while mismatch and conflict preserve pending row`() = runBlocking {
    val duplicateFixture = Fixture(
      UserPreferences(onlineFeaturesEnabled = true, notificationAggregateSharingEnabled = true),
      currentSession = SESSION,
      summaries = mutableListOf(summary()),
    )
    duplicateFixture.api.dailyStatus = "duplicate"
    assertEquals(OnlineSyncOutcome.SUCCESS, duplicateFixture.coordinator().sync())
    assertEquals(1, duplicateFixture.summaryStore.marked.size)

    val mismatchFixture = Fixture(
      UserPreferences(onlineFeaturesEnabled = true, notificationAggregateSharingEnabled = true),
      currentSession = SESSION,
      summaries = mutableListOf(summary()),
    )
    mismatchFixture.api.dailyDateOverride = "2026-10-12"
    assertEquals(OnlineSyncOutcome.FAILURE, mismatchFixture.coordinator().sync())
    assertTrue(mismatchFixture.summaryStore.marked.isEmpty())

    val conflictFixture = Fixture(
      UserPreferences(onlineFeaturesEnabled = true, notificationAggregateSharingEnabled = true),
      currentSession = SESSION,
      summaries = mutableListOf(summary()),
    )
    conflictFixture.api.dailyFailure = ApiFailure.Conflict
    assertEquals(OnlineSyncOutcome.FAILURE, conflictFixture.coordinator().sync())
    assertTrue(conflictFixture.summaryStore.marked.isEmpty())
  }

  @Test
  fun `daily sharing disabled acknowledges target but does not read or upload totals`() = runBlocking {
    val fixture = Fixture(
      UserPreferences(onlineFeaturesEnabled = true, notificationAggregateSharingEnabled = false),
      currentSession = SESSION,
      summaries = mutableListOf(summary()),
    )
    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(0, fixture.summaryStore.pendingCalls)
    assertTrue(fixture.api.dailyBodies.isEmpty())
    assertTrue(fixture.summaryStore.marked.isEmpty())
  }

  @Test
  fun `enabled product reporting uploads only after consent acknowledgement`() = runBlocking {
    val fixture = Fixture(
      UserPreferences(onlineFeaturesEnabled = true, productAnalyticsEnabled = true),
      currentSession = SESSION,
    )

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(listOf(SESSION.installationId to "access-token"), fixture.analytics.uploads)
    assertEquals(0, fixture.analytics.discardCalls)
    assertEquals(
      true,
      fixture.api.consentCalls.single { it.request.scope == "product_analytics" }.request.granted,
    )
  }

  @Test
  fun `opt out revokes optional scopes first and essential last then logs out`() = runBlocking {
    val fixture = Fixture(UserPreferences(), currentSession = SESSION)
    fixture.acknowledgeAll(true)

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(
      listOf(
        OnlineConsentScope.PRODUCT_ANALYTICS.wireName,
        OnlineConsentScope.NOTIFICATION_AGGREGATES.wireName,
        OnlineConsentScope.ESSENTIAL_ONLINE.wireName,
      ),
      fixture.api.consentCalls.map { it.request.scope },
    )
    assertTrue(fixture.api.consentCalls.all { !it.request.granted })
    assertEquals(1, fixture.identity.logoutCalls)
    assertNull(fixture.identity.current)
  }

  @Test
  fun `development feedback keeps the anonymous identity after consent revocation`() = runBlocking {
    val fixture = Fixture(
      UserPreferences(),
      currentSession = SESSION,
      retainIdentityWhenOnlineFeaturesDisabled = true,
    )
    fixture.acknowledgeAll(true)

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertEquals(
      listOf(
        OnlineConsentScope.PRODUCT_ANALYTICS.wireName,
        OnlineConsentScope.NOTIFICATION_AGGREGATES.wireName,
        OnlineConsentScope.ESSENTIAL_ONLINE.wireName,
      ),
      fixture.api.consentCalls.map { it.request.scope },
    )
    assertTrue(fixture.api.consentCalls.all { !it.request.granted })
    assertEquals(0, fixture.identity.logoutCalls)
    assertEquals(SESSION, fixture.identity.current)
  }

  @Test
  fun `uncertain logout preserves credentials and acknowledged revocations for retry`() = runBlocking {
    val fixture = Fixture(UserPreferences(), currentSession = SESSION)
    fixture.acknowledgeAll(true)
    fixture.identity.logoutResults += IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Network))
    fixture.identity.logoutResults += IdentityResult.Success(Unit)

    assertEquals(OnlineSyncOutcome.RETRY, fixture.coordinator().sync())
    assertEquals(SESSION, fixture.identity.current)
    assertEquals(3, fixture.api.consentCalls.size)
    fixture.api.consentCalls.clear()

    assertEquals(OnlineSyncOutcome.SUCCESS, fixture.coordinator().sync())
    assertTrue(fixture.api.consentCalls.isEmpty())
    assertNull(fixture.identity.current)
  }

  @Test
  fun `malformed consent acknowledgement fails without accepting local state`() = runBlocking {
    val fixture = Fixture(UserPreferences(onlineFeaturesEnabled = true), currentSession = SESSION)
    fixture.api.malformedConsentScope = true

    assertEquals(OnlineSyncOutcome.FAILURE, fixture.coordinator().sync())
    assertEquals(1, fixture.api.consentCalls.size)
  }

  private class Fixture(
    private val preferences: UserPreferences,
    currentSession: CredentialSession? = null,
    summaries: MutableList<DailyNotificationSummaryEntity> = mutableListOf(),
    private val retainIdentityWhenOnlineFeaturesDisabled: Boolean = false,
  ) {
    private val persistence = MemoryPersistence()
    private val ids = ArrayDeque(listOf(ID_ONE, ID_TWO, ID_THREE, ID_FOUR, ID_FIVE, ID_SIX, ID_SEVEN, ID_EIGHT))
    val state = SharedPreferencesOnlineSyncStateStore(persistence) { ids.removeFirst() }
    val identity = FakeIdentity(currentSession)
    val token = FakeToken()
    val api = FakeApi()
    val summaryStore = FakeSummaryStore(summaries)
    val analytics = FakeAnalyticsSync()

    fun coordinator() = OnlineSyncCoordinator(
      preferences = { preferences },
      identity = identity,
      tokenClient = token,
      api = api,
      summaries = summaryStore,
      analyticsOutbox = analytics,
      state = state,
      registrationRequest = { RegistrationRequest("0.1.8-dev-debug", 35, "phone") },
      retainIdentityWhenOnlineFeaturesDisabled = retainIdentityWhenOnlineFeaturesDisabled,
      nowMillis = { 99L },
    )

    fun acknowledgeAll(target: Boolean) {
      check(state.bindInstallation(SESSION.installationId))
      OnlineConsentScope.entries.forEach { scope ->
        val read = state.consentMutation(scope, target)
        val mutation = requireNotNull((read as SyncStateRead.Available<PendingConsentMutation?>).value)
        check(state.acknowledgeConsent(mutation))
      }
    }
  }

  private class FakeIdentity(currentSession: CredentialSession?) : OnlineIdentityClient {
    var current = currentSession
    val registrationResults = ArrayDeque<IdentityResult<CredentialSession>>()
    val logoutResults = ArrayDeque<IdentityResult<Unit>>()
    val registrationKeys = mutableListOf<String>()
    var registerCalls = 0
    var logoutCalls = 0

    override fun currentSession(): IdentityResult<CredentialSession?> = IdentityResult.Success(current)

    override fun register(request: RegistrationRequest, idempotencyKey: String): IdentityResult<CredentialSession> {
      registerCalls += 1
      registrationKeys += idempotencyKey
      val result = if (registrationResults.isEmpty()) IdentityResult.Success(SESSION) else registrationResults.removeFirst()
      if (result is IdentityResult.Success) current = result.value
      return result
    }

    override fun logout(): IdentityResult<Unit> {
      logoutCalls += 1
      val result = if (logoutResults.isEmpty()) IdentityResult.Success(Unit) else logoutResults.removeFirst()
      if (result is IdentityResult.Success || result == IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Unauthorized))) {
        current = null
      }
      return result
    }
  }

  private class FakeToken : OnlineTokenClient {
    var calls = 0
    var result: TokenResult = TokenResult.Available("access-token")
    override fun accessToken(): TokenResult {
      calls += 1
      return result
    }
  }

  private data class ConsentCall(
    val request: ConsentUpdate,
    val token: String,
    val key: String,
  )

  private class FakeApi : OnlineSyncApi {
    val consentCalls = mutableListOf<ConsentCall>()
    val dailyBodies = mutableListOf<String>()
    var malformedConsentScope = false
    var dailyStatus = "accepted"
    var dailyDateOverride: String? = null
    var dailyFailure: ApiFailure? = null

    override fun updateConsent(
      request: ConsentUpdate,
      bearerToken: String,
      idempotencyKey: String,
    ): ApiResult<ConsentResponse> {
      consentCalls += ConsentCall(request, bearerToken, idempotencyKey)
      val scope = if (malformedConsentScope) "product_analytics" else request.scope
      return ApiResult.success(
        ConsentResponse(listOf(ConsentState(request.granted, scope, request.scopeVersion))),
      )
    }

    override fun submitDailySummary(body: String, bearerToken: String): ApiResult<DailyAggregateResult> {
      dailyBodies += body
      dailyFailure?.let { return ApiResult.failure(it) }
      val root = JSONObject(body)
      return ApiResult.success(
        DailyAggregateResult(
          localDate = dailyDateOverride ?: root.getString("local_date"),
          revision = root.getInt("revision"),
          status = dailyStatus,
        ),
      )
    }

    override fun submitAnalytics(body: String, bearerToken: String): ApiResult<AnalyticsBatchResult> =
      ApiResult.success(AnalyticsBatchResult(emptyList()))
  }

  private class FakeAnalyticsSync : AnalyticsOutboxSync {
    var discardCalls = 0
    val uploads = mutableListOf<Pair<String, String>>()

    override suspend fun discard(): OnlineSyncOutcome {
      discardCalls += 1
      return OnlineSyncOutcome.SUCCESS
    }

    override suspend fun upload(installationId: String, bearerToken: String): OnlineSyncOutcome {
      uploads += installationId to bearerToken
      return OnlineSyncOutcome.SUCCESS
    }
  }

  private class FakeSummaryStore(
    val rows: MutableList<DailyNotificationSummaryEntity>,
  ) : DailySummaryUploadStore {
    var pendingCalls = 0
    val marked = mutableListOf<Pair<String, Int>>()

    override suspend fun pending(limit: Int): List<DailyNotificationSummaryEntity> {
      pendingCalls += 1
      return rows.take(limit)
    }

    override suspend fun markUploaded(
      localDate: String,
      revision: Int,
      uploadedAtEpochMillis: Long,
    ): Int {
      marked += localDate to revision
      return 1
    }
  }

  private class MemoryPersistence : SyncStatePersistence, SyncStateKeyPersistence {
    private val strings = mutableMapOf<String, String>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun keys(): Set<String> = strings.keys + booleans.keys

    override fun readString(key: String): StoredValue<String> =
      strings[key]?.let { StoredValue.Present(it) }
        ?: if (key in booleans) StoredValue.Malformed else StoredValue.Missing

    override fun readBoolean(key: String): StoredValue<Boolean> =
      booleans[key]?.let { StoredValue.Present(it) }
        ?: if (key in strings) StoredValue.Malformed else StoredValue.Missing

    override fun commit(
      strings: Map<String, String>,
      booleans: Map<String, Boolean>,
      removals: Set<String>,
      clear: Boolean,
    ): Boolean {
      if (clear) {
        this.strings.clear()
        this.booleans.clear()
      }
      removals.forEach {
        this.strings.remove(it)
        this.booleans.remove(it)
      }
      this.strings.putAll(strings)
      this.booleans.putAll(booleans)
      return true
    }
  }

  private fun summary() = DailyNotificationSummaryEntity(
    localDate = "2026-10-13",
    timezoneOffsetMinutes = -240,
    revision = 1,
    rulesVersion = 1,
    eligibleCount = 3,
    categoryCountsJson = """{"email":1,"message":2}""",
    hourlyBucketsJson = """[{"hour":8,"count":1},{"hour":9,"count":2}]""",
    gamePressure = 0,
    observationCompleteness = 100,
    createdAtEpochMillis = 1L,
    uploadedRevision = null,
    uploadedAtEpochMillis = null,
  )

  private fun <T> SyncStateRead<T>.available(): T =
    (this as SyncStateRead.Available<T>).value

  private companion object {
    const val ID_ONE = "123e4567-e89b-42d3-a456-426614174001"
    const val ID_TWO = "123e4567-e89b-42d3-a456-426614174002"
    const val ID_THREE = "123e4567-e89b-42d3-a456-426614174003"
    const val ID_FOUR = "123e4567-e89b-42d3-a456-426614174004"
    const val ID_FIVE = "123e4567-e89b-42d3-a456-426614174005"
    const val ID_SIX = "123e4567-e89b-42d3-a456-426614174006"
    const val ID_SEVEN = "123e4567-e89b-42d3-a456-426614174007"
    const val ID_EIGHT = "123e4567-e89b-42d3-a456-426614174008"
    const val SOURCE_SENTINEL = "source-token-must-not-upload"
    val SESSION = CredentialSession(
      installationId = "123e4567-e89b-42d3-a456-426614174010",
      accessToken = "access-token",
      refreshToken = "x".repeat(43),
      expiresAtEpochMillis = Long.MAX_VALUE,
    )
  }
}
