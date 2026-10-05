package com.techfullymade.afterchime.ui.museum

import android.os.Looper
import com.techfullymade.afterchime.domain.CollectibleState
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

  @Test
  fun `revealed specimen lock updates through the local collection boundary`() = runBlocking {
    val common = specimen(id = "common", tier = Tier.COMMON)
    val repository = FakeMuseumRepository(specimens = listOf(common))
    val viewModel = MuseumViewModel(repository)

    viewModel.await { it.specimens == listOf(common) }
    viewModel.setSpecimenLocked(common.id, locked = true)
    advanceMainLooper()

    assertEquals(
      true,
      viewModel.await { state -> state.specimens.single().isLocked }.specimens.single().isLocked,
    )
  }

  @Test
  fun `eligible local selection requires an explicit confirmation before one combine request`() = runBlocking {
    val first = specimen(id = "first", tier = Tier.RARE)
    val second = specimen(id = "second", tier = Tier.RARE)
    val third = specimen(id = "third", tier = Tier.RARE)
    val repository = FakeMuseumRepository(specimens = listOf(first, second, third))
    val viewModel = MuseumViewModel(repository)

    viewModel.await { it.specimens.size == 3 }
    viewModel.beginCombine(first.id)
    viewModel.toggleCombineSpecimen(second.id)
    viewModel.toggleCombineSpecimen(third.id)
    advanceMainLooper()

    val ready = viewModel.await { it.combineOutputState == CollectibleState.RESTORED }
    assertEquals(listOf(first.id, second.id, third.id), ready.combineSelectionIds)
    assertEquals(0, repository.combineRequests.size)

    viewModel.reviewCombine()
    assertEquals(
      CollectibleState.RESTORED,
      viewModel.await { it.combineConfirmationOutputState != null }.combineConfirmationOutputState,
    )
    assertEquals(0, repository.combineRequests.size)

    viewModel.confirmCombine()
    advanceMainLooper()
    viewModel.await { repository.combineRequests.size == 1 && !it.isCombining }

    assertEquals(setOf(first.id, second.id, third.id), repository.combineRequests.single().inputItemIds.toSet())
    assertEquals(3, repository.combineRequests.single().inputItemIds.size)
  }

  @Test
  fun `unknown combine delivery preserves the exact request for an explicit retry`() = runBlocking {
    val first = specimen(id = "first", tier = Tier.RARE)
    val second = specimen(id = "second", tier = Tier.RARE)
    val third = specimen(id = "third", tier = Tier.RARE)
    val repository = FakeMuseumRepository(specimens = listOf(first, second, third))
    val viewModel = MuseumViewModel(repository)

    viewModel.await { it.specimens.size == 3 }
    viewModel.beginCombine(first.id)
    viewModel.toggleCombineSpecimen(second.id)
    viewModel.toggleCombineSpecimen(third.id)
    advanceMainLooper()
    viewModel.await { it.combineOutputState == CollectibleState.RESTORED }
    viewModel.reviewCombine()
    repository.failNextCombine = true

    viewModel.confirmCombine()
    val retryable = viewModel.await {
      repository.combineRequests.size == 1 &&
        !it.isCombineSubmitting &&
        it.combineConfirmationOutputState == CollectibleState.RESTORED
    }
    assertEquals(listOf(first.id, second.id, third.id), retryable.combineSelectionIds)
    assertEquals(1, repository.combineRequests.size)

    viewModel.confirmCombine()
    advanceMainLooper()
    viewModel.await { repository.combineRequests.size == 2 && !it.isCombining }

    assertEquals(
      repository.combineRequests[0].mutationId,
      repository.combineRequests[1].mutationId,
    )
    assertEquals(
      repository.combineRequests[0].outputItemId,
      repository.combineRequests[1].outputItemId,
    )
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
