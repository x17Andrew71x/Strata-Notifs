package com.techfullymade.afterchime.ui.today

import com.techfullymade.afterchime.domain.FormationLayer
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.domain.RevealResult
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayViewModelTest {
  @Test
  fun `previous day specimen reveal is submitted once on the next visit`() {
    val repository = FakeFormationRepository()
    val viewModel = TodayViewModel(repository, TODAY, Clock.fixed(NOW, ZoneOffset.UTC))
    val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val collection = collectionScope.launch { viewModel.uiState.collect { } }
    shadowOf(android.os.Looper.getMainLooper()).idle()

    viewModel.reveal()
    viewModel.reveal()
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(1, repository.reveals)
    assertEquals("local-specimen", repository.revealedId)
    collection.cancel()
    collectionScope.cancel()
  }

  @Test
  fun `refresh switches the observed excavation at the local day boundary`() {
    val repository = FakeFormationRepository()
    val clock = MutableClock(Instant.parse("2026-10-05T23:59:00Z"))
    val viewModel = TodayViewModel(repository, TODAY, clock, ZoneOffset.UTC)
    val collectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val collection = collectionScope.launch { viewModel.uiState.collect { } }
    shadowOf(android.os.Looper.getMainLooper()).idle()

    clock.current = Instant.parse("2026-10-06T00:01:00Z")
    viewModel.refreshDate()
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertEquals(listOf(TODAY, TODAY.plusDays(1)), repository.observedDates)
    collection.cancel()
    collectionScope.cancel()
  }

  private class FakeFormationRepository : FormationRepository {
    private val state = MutableStateFlow(
      FormationSnapshot(
        localDate = TODAY,
        observation = FormationObservation.Active,
        layers = emptyList<FormationLayer>(),
        sealedSpecimen = MuseumSpecimen(
          id = "local-specimen",
          anchoredLocalDate = TODAY.minusDays(1),
          generatorVersion = 1,
          createdAtEpochMillis = 1_000L,
          revealedAtEpochMillis = null,
          family = Family.AMMONITE,
          tier = Tier.UNCOMMON,
          visual = VisualParameters(32, 7, 30, 42, 14),
        ),
      ),
    )
    var reveals = 0
    var revealedId: String? = null
    val observedDates = mutableListOf<LocalDate>()

    override fun observe(localDate: LocalDate): Flow<FormationSnapshot> {
      observedDates += localDate
      return state
    }

    override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
      reveals += 1
      revealedId = specimenId
      return RevealResult.Revealed(revealedAtEpochMillis)
    }
  }

  private class MutableClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
  }

  private companion object {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)
    val NOW: Instant = Instant.ofEpochMilli(2_000L)
  }
}
