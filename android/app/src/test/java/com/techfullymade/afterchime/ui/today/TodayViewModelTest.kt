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
import java.time.LocalDate
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
  fun `sealed specimen reveal is submitted once to the local formation repository`() {
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

  private class FakeFormationRepository : FormationRepository {
    private val state = MutableStateFlow(
      FormationSnapshot(
        localDate = TODAY,
        observation = FormationObservation.SealedObserved,
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

    override fun observe(localDate: LocalDate): Flow<FormationSnapshot> = state

    override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
      reveals += 1
      revealedId = specimenId
      return RevealResult.Revealed(revealedAtEpochMillis)
    }
  }

  private companion object {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)
    val NOW = java.time.Instant.ofEpochMilli(2_000L)
  }
}
