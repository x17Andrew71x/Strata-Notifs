package com.techfullymade.afterchime.ui.museum

import android.os.Looper
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.fakes.FakeMuseumRepository
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MuseumViewModelTest {
  @Test
  fun `selection survives a temporary tier filter and clears only when local ownership is removed`() = runBlocking {
    val common = specimen(id = "common", tier = Tier.COMMON)
    val rare = specimen(id = "rare", tier = Tier.RARE)
    val repository = FakeMuseumRepository(specimens = listOf(common, rare))
    val viewModel = MuseumViewModel(repository)

    viewModel.await { it.visibleSpecimens.map(MuseumSpecimen::id) == listOf("common", "rare") }
    viewModel.selectSpecimen(common.id)
    advanceMainLooper()
    assertEquals(common.id, viewModel.await { it.selectedSpecimenId == common.id }.selectedSpecimenId)

    viewModel.selectTierFilter(MuseumTierFilter.RARE)
    advanceMainLooper()
    val filtered = viewModel.await { it.tierFilter == MuseumTierFilter.RARE }
    assertEquals(listOf(rare.id), filtered.visibleSpecimens.map(MuseumSpecimen::id))
    assertEquals(common.id, filtered.selectedSpecimenId)

    repository.replace(listOf(rare))
    advanceMainLooper()
    assertNull(viewModel.await { it.specimens == listOf(rare) }.selectedSpecimenId)
  }

  private suspend fun MuseumViewModel.await(predicate: (MuseumUiState) -> Boolean): MuseumUiState =
    withTimeout(3_000) { uiState.first(predicate) }

  private fun advanceMainLooper() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun specimen(id: String, tier: Tier) = MuseumSpecimen(
    id = id,
    anchoredLocalDate = LocalDate.of(2026, 10, 2),
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_420_800_000L,
    revealedAtEpochMillis = 1_759_507_200_000L,
    family = Family.AMMONITE,
    tier = tier,
    visual = VisualParameters(
      hueDegrees = 32,
      strataCount = 7,
      inclusionDensityPercent = 30,
      reliefPercent = 42,
      rotationDegrees = 14,
    ),
  )
}
