package com.techfullymade.afterchime.sync

import com.techfullymade.afterchime.analytics.AnalyticsContext
import com.techfullymade.afterchime.analytics.AnalyticsEvent
import com.techfullymade.afterchime.analytics.AnalyticsRegistry
import com.techfullymade.afterchime.analytics.LaunchType
import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import com.techfullymade.afterchime.network.AnalyticsBatchResult
import com.techfullymade.afterchime.network.AnalyticsItemResult
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ConsentResponse
import com.techfullymade.afterchime.network.ConsentUpdate
import com.techfullymade.afterchime.network.DailyAggregateResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsOutboxSyncTest {
  @Test
  fun `accepted and duplicate exact results delete the submitted encrypted rows`() = runBlocking {
    val store = FakeStore(mutableListOf(row(ID_ONE), row(ID_TWO)))
    val api = FakeApi().apply { statuses = listOf("accepted", "duplicate") }
    val uploader = uploader(store, api)

    assertEquals(OnlineSyncOutcome.SUCCESS, uploader.upload(INSTALLATION_ID, "token"))
    assertEquals(listOf(ID_ONE, ID_TWO), store.deleted)
    assertTrue(store.rows.isEmpty())
    val events = JSONObject(api.analyticsBodies.single()).getJSONArray("events")
    assertEquals(2, events.length())
    assertEquals(ID_ONE, events.getJSONObject(0).getString("event_id"))
    assertEquals(ID_TWO, events.getJSONObject(1).getString("event_id"))
    assertEquals(INSTALLATION_ID, events.getJSONObject(0).getString("installation_id"))
  }

  @Test
  fun `rejected mismatched or failed responses preserve every local row`() = runBlocking {
    val rejectedStore = FakeStore(mutableListOf(row(ID_ONE)))
    val rejectedApi = FakeApi().apply { statuses = listOf("rejected") }
    assertEquals(OnlineSyncOutcome.FAILURE, uploader(rejectedStore, rejectedApi).upload(INSTALLATION_ID, "token"))
    assertTrue(rejectedStore.deleted.isEmpty())

    val mismatchedStore = FakeStore(mutableListOf(row(ID_ONE)))
    val mismatchedApi = FakeApi().apply { responseIdOverride = ID_TWO }
    assertEquals(OnlineSyncOutcome.FAILURE, uploader(mismatchedStore, mismatchedApi).upload(INSTALLATION_ID, "token"))
    assertTrue(mismatchedStore.deleted.isEmpty())

    val networkStore = FakeStore(mutableListOf(row(ID_ONE)))
    val networkApi = FakeApi().apply { analyticsFailure = ApiFailure.Network }
    assertEquals(OnlineSyncOutcome.RETRY, uploader(networkStore, networkApi).upload(INSTALLATION_ID, "token"))
    assertTrue(networkStore.deleted.isEmpty())
  }

  @Test
  fun `event identity installation and schema are bound before transport`() = runBlocking {
    val wrongEventIdStore = FakeStore(
      mutableListOf(
        AnalyticsOutboxEntity(ID_TWO, encrypted(envelope(ID_ONE, INSTALLATION_ID)), 1L),
      ),
    )
    val wrongEventApi = FakeApi()
    assertEquals(OnlineSyncOutcome.FAILURE, uploader(wrongEventIdStore, wrongEventApi).upload(INSTALLATION_ID, "token"))
    assertTrue(wrongEventApi.analyticsBodies.isEmpty())

    val wrongInstallationStore = FakeStore(mutableListOf(row(ID_ONE, OTHER_INSTALLATION_ID)))
    val wrongInstallationApi = FakeApi()
    assertEquals(
      OnlineSyncOutcome.FAILURE,
      uploader(wrongInstallationStore, wrongInstallationApi).upload(INSTALLATION_ID, "token"),
    )
    assertTrue(wrongInstallationApi.analyticsBodies.isEmpty())
  }

  @Test
  fun `corrupt envelopes and partial local deletion fail closed`() = runBlocking {
    val corruptStore = FakeStore(mutableListOf(AnalyticsOutboxEntity(ID_ONE, ByteArray(4), 1L)))
    val api = FakeApi()
    assertEquals(OnlineSyncOutcome.FAILURE, uploader(corruptStore, api).upload(INSTALLATION_ID, "token"))
    assertTrue(api.analyticsBodies.isEmpty())

    val partialStore = FakeStore(mutableListOf(row(ID_ONE), row(ID_TWO))).apply { deleteCountOverride = 1 }
    assertEquals(OnlineSyncOutcome.RETRY, uploader(partialStore, FakeApi()).upload(INSTALLATION_ID, "token"))
  }

  @Test
  fun `disabled consent clears encrypted outbox without decryption or transport`() = runBlocking {
    val store = FakeStore(mutableListOf(row(ID_ONE), row(ID_TWO)))
    val api = FakeApi()
    var decryptions = 0
    val uploader = AnalyticsOutboxUploader(
      store = store,
      decryptor = { decryptions += 1; error("must not decrypt") },
      api = api,
    )

    assertEquals(OnlineSyncOutcome.SUCCESS, uploader.discard())
    assertTrue(store.rows.isEmpty())
    assertEquals(0, decryptions)
    assertTrue(api.analyticsBodies.isEmpty())
  }

  private fun uploader(store: FakeStore, api: FakeApi) = AnalyticsOutboxUploader(
    store = store,
    decryptor = { envelope -> envelope.copyOfRange(ENVELOPE_PREFIX_BYTES, envelope.size) },
    api = api,
  )

  private fun row(eventId: String, installationId: String = INSTALLATION_ID) =
    AnalyticsOutboxEntity(eventId, encrypted(envelope(eventId, installationId)), 1L)

  private fun encrypted(plaintext: String): ByteArray =
    ByteArray(ENVELOPE_PREFIX_BYTES) { 7 } + plaintext.toByteArray(Charsets.UTF_8)

  private fun envelope(eventId: String, installationId: String): String =
    AnalyticsRegistry.envelope(
      AnalyticsEvent.AppOpened(LaunchType.COLD),
      AnalyticsContext(
        eventId = eventId,
        installationId = installationId,
        occurredAt = "2026-10-15T02:00:00Z",
        localDate = "2026-10-14",
        timezoneOffsetMinutes = -240,
        appVersion = "0.1.8-dev-debug",
        versionCode = 1008,
        buildChannel = "dev",
        androidApiLevel = 35,
        deviceClass = "phone",
        locale = "en-US",
        consentScopeVersion = 1,
        screenName = "today",
      ),
    )

  private class FakeStore(
    val rows: MutableList<AnalyticsOutboxEntity>,
  ) : AnalyticsUploadStore {
    val deleted = mutableListOf<String>()
    var deleteCountOverride: Int? = null

    override suspend fun oldest(limit: Int): List<AnalyticsOutboxEntity> = rows.take(limit)

    override suspend fun deleteAll(eventIds: List<String>): Int {
      deleted += eventIds
      val count = deleteCountOverride ?: eventIds.count { id -> rows.any { it.eventId == id } }
      if (deleteCountOverride == null) rows.removeAll { it.eventId in eventIds }
      return count
    }

    override suspend fun clear(): Int {
      val count = rows.size
      rows.clear()
      return count
    }
  }

  private class FakeApi : OnlineSyncApi {
    val analyticsBodies = mutableListOf<String>()
    var statuses: List<String> = emptyList()
    var responseIdOverride: String? = null
    var analyticsFailure: ApiFailure? = null

    override fun updateConsent(
      request: ConsentUpdate,
      bearerToken: String,
      idempotencyKey: String,
    ): ApiResult<ConsentResponse> = ApiResult.failure(ApiFailure.Http)

    override fun submitDailySummary(body: String, bearerToken: String): ApiResult<DailyAggregateResult> =
      ApiResult.failure(ApiFailure.Http)

    override fun submitAnalytics(body: String, bearerToken: String): ApiResult<AnalyticsBatchResult> {
      analyticsBodies += body
      analyticsFailure?.let { return ApiResult.failure(it) }
      val events = JSONObject(body).getJSONArray("events")
      val results = (0 until events.length()).map { index ->
        AnalyticsItemResult(
          eventId = responseIdOverride ?: events.getJSONObject(index).getString("event_id"),
          status = statuses.getOrElse(index) { "accepted" },
        )
      }
      return ApiResult.success(AnalyticsBatchResult(results))
    }
  }

  private companion object {
    const val ENVELOPE_PREFIX_BYTES = 28
    const val ID_ONE = "123e4567-e89b-42d3-a456-426614174001"
    const val ID_TWO = "123e4567-e89b-42d3-a456-426614174002"
    const val INSTALLATION_ID = "123e4567-e89b-42d3-a456-426614174010"
    const val OTHER_INSTALLATION_ID = "123e4567-e89b-42d3-a456-426614174011"
  }
}
