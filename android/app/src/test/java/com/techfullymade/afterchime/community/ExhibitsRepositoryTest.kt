package com.techfullymade.afterchime.community

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ExhibitContributionResponse
import com.techfullymade.afterchime.network.ExhibitsStateResponse
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExhibitsRepositoryTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    context.getSharedPreferences("afterchime_exhibit_contribution", Context.MODE_PRIVATE)
      .edit().clear().commit()
  }

  @Test
  fun `banked exhibit energy excludes already contributed surplus and respects the shared cap`() {
    assertEquals(18, availableExhibitEnergy(surplusEnergy = 18, todayContributedUnits = 0, dailyContributionCap = 30))
    assertEquals(12, availableExhibitEnergy(surplusEnergy = 42, todayContributedUnits = 18, dailyContributionCap = 30))
    assertEquals(0, availableExhibitEnergy(surplusEnergy = 18, todayContributedUnits = 18, dailyContributionCap = 30))
    assertEquals(0, availableExhibitEnergy(surplusEnergy = 100, todayContributedUnits = 30, dailyContributionCap = 30))
  }

  @Test
  fun `pending contribution is durable replay state until the exact attempt is acknowledged`() {
    val key = "11111111-1111-4111-8111-111111111111"
    val store = SharedPreferencesExhibitContributionAttemptStore(context, newIdempotencyKey = { key })

    val pending = store.begin("returning-light-2027", "2026-10-26", 18)
    assertEquals(PendingExhibitContribution("returning-light-2027", "2026-10-26", 18, key), pending)
    assertEquals(pending, store.pending())
    assertEquals(pending, store.begin("returning-light-2027", "2026-10-26", 18))
    assertNull(store.begin("long-night-2026", "2026-10-26", 12))
    assertFalse(store.acknowledge(UUID.randomUUID().toString()))
    assertTrue(store.acknowledge(key))
    assertNull(store.pending())
  }

  @Test
  fun `ambiguous contribution is replayed with the exact idempotency key during refresh`() = runBlocking {
    val key = "11111111-1111-4111-8111-111111111111"
    val store = SharedPreferencesExhibitContributionAttemptStore(context, newIdempotencyKey = { key })
    val api = FakeExhibitsApi()
    val repository = ExhibitsRepository(api, CommunityTokenProvider { "token" }, store)
    assertTrue(repository.refresh("2026-10-26", surplusEnergy = 18, onlineEnabled = true))

    api.results.add(ApiResult.failure(ApiFailure.Network))
    assertFalse(repository.contribute("returning-light-2027", "2026-10-26", surplusEnergy = 18))
    assertEquals(key, store.pending()?.idempotencyKey)

    api.state = api.state.copy(todayContributedUnits = 18, version = 2)
    api.results.add(ApiResult.success(ExhibitContributionResponse("duplicate", api.state)))
    assertTrue(repository.refresh("2026-10-26", surplusEnergy = 18, onlineEnabled = true))

    assertEquals(2, api.contributions.size)
    assertEquals(api.contributions[0], api.contributions[1])
    assertEquals(key, api.contributions.last().idempotencyKey)
    assertNull(store.pending())
    assertEquals(0, repository.state.value.bankedEnergy)
  }

  @Test
  fun `expired unresolved contribution is retired before current-day refresh`() = runBlocking {
    val key = "11111111-1111-4111-8111-111111111111"
    val store = SharedPreferencesExhibitContributionAttemptStore(context, newIdempotencyKey = { key })
    assertTrue(store.begin("returning-light-2027", "2026-10-20", 18) != null)
    val api = FakeExhibitsApi()
    val repository = ExhibitsRepository(api, CommunityTokenProvider { "token" }, store)

    assertTrue(repository.refresh("2026-10-26", surplusEnergy = 12, onlineEnabled = true))

    assertNull(store.pending())
    assertTrue(api.contributions.isEmpty())
    assertEquals(12, repository.state.value.bankedEnergy)
  }

  @Test
  fun `older refresh state cannot roll back accepted contribution state`() = runBlocking {
    val api = FakeExhibitsApi()
    api.state = api.state.copy(todayContributedUnits = 18, version = 2)
    val repository = ExhibitsRepository(
      api,
      CommunityTokenProvider { "token" },
      SharedPreferencesExhibitContributionAttemptStore(context),
    )
    assertTrue(repository.refresh("2026-10-26", surplusEnergy = 18, onlineEnabled = true))
    assertEquals(0, repository.state.value.bankedEnergy)

    api.state = api.state.copy(todayContributedUnits = 0, version = 1)
    assertTrue(repository.refresh("2026-10-26", surplusEnergy = 18, onlineEnabled = true))

    assertEquals(2, repository.state.value.version)
    assertEquals(0, repository.state.value.bankedEnergy)
  }

  private data class ContributionCall(
    val exhibitId: String,
    val localDate: String,
    val units: Int,
    val bearerToken: String,
    val idempotencyKey: String,
  )

  private class FakeExhibitsApi : ExhibitsApiGateway {
    var state = ExhibitsStateResponse("ready", null, null, emptyList(), 0, 30, 1)
    val results = ArrayDeque<ApiResult<ExhibitContributionResponse>>()
    val contributions = mutableListOf<ContributionCall>()

    override fun getExhibits(localDate: String, bearerToken: String) = ApiResult.success(state)

    override fun contributeExhibit(
      exhibitId: String,
      localDate: String,
      units: Int,
      bearerToken: String,
      idempotencyKey: String,
    ): ApiResult<ExhibitContributionResponse> {
      contributions += ContributionCall(exhibitId, localDate, units, bearerToken, idempotencyKey)
      return results.removeFirst()
    }
  }
}
