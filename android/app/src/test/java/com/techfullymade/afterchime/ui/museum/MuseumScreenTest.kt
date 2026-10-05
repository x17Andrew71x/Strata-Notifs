package com.techfullymade.afterchime.ui.museum

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MuseumScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `empty museum explains the next local event without a pressure action`() {
    setMuseum(specimens = emptyList())

    composeRule.onNodeWithText("No specimens yet").assertExists()
    composeRule.onNodeWithText("A revealed local day will appear here.").assertExists()
    composeRule.onNodeWithTag("museum-specimen-1").assertDoesNotExist()
  }

  @Test
  fun `tier filtering keeps the collection concise and never exposes an unrevealed specimen`() {
    setMuseum(
      specimens = listOf(
        specimen(id = "common", family = Family.AMMONITE, tier = Tier.COMMON),
        specimen(id = "rare", family = Family.GEODE, tier = Tier.RARE),
        specimen(id = "queued", family = Family.AMBER, tier = Tier.SINGULAR, revealed = false),
      ),
    )

    composeRule.onNodeWithTag("museum-specimen-common").assertExists()
    composeRule.onNodeWithTag("museum-specimen-rare").assertExists()
    composeRule.onNodeWithTag("museum-specimen-queued").assertDoesNotExist()

    composeRule.onNodeWithTag("museum-filter-rare").performClick()

    composeRule.onNodeWithTag("museum-specimen-common").assertDoesNotExist()
    composeRule.onNodeWithTag("museum-specimen-rare").assertExists()
    composeRule.onNodeWithTag("museum-specimen-queued").assertDoesNotExist()
  }

  @Test
  fun `empty tier result keeps filters available so the collection can be restored`() {
    setMuseum(
      specimens = listOf(specimen(id = "common", family = Family.AMMONITE, tier = Tier.COMMON)),
    )

    composeRule.onNodeWithTag("museum-filter-singular").performClick()

    composeRule.onNodeWithText("No specimens in this tier").assertExists()
    composeRule.onNodeWithTag("museum-specimen-common").assertDoesNotExist()
    composeRule.onNodeWithTag("museum-filter-all").performClick()
    composeRule.onNodeWithTag("museum-specimen-common").assertExists()
  }

  @Test
  fun `selection persists while a temporary filter hides and restores the card`() {
    setMuseum(
      specimens = listOf(
        specimen(id = "common", family = Family.AMMONITE, tier = Tier.COMMON),
        specimen(id = "rare", family = Family.GEODE, tier = Tier.RARE),
      ),
    )

    composeRule.onNodeWithTag("museum-specimen-common").performClick()
    composeRule.onNodeWithTag("museum-specimen-common").assertIsSelected()

    composeRule.onNodeWithTag("museum-filter-rare").performClick()
    composeRule.onNodeWithTag("museum-specimen-common").assertDoesNotExist()
    composeRule.onNodeWithTag("museum-filter-all").performClick()

    composeRule.onNodeWithTag("museum-specimen-common").assertIsSelected()
  }

  @Test
  fun `ui state preserves date order and excludes unrevealed queued work`() {
    val newest = specimen(
      id = "newest",
      family = Family.TRILOBITE,
      tier = Tier.UNCOMMON,
      date = LocalDate.of(2026, 10, 3),
    )
    val oldest = specimen(
      id = "oldest",
      family = Family.TRACE_PLATE,
      tier = Tier.COMMON,
      date = LocalDate.of(2026, 10, 1),
    )
    val queued = specimen(
      id = "queued",
      family = Family.AMBER,
      tier = Tier.SINGULAR,
      date = LocalDate.of(2026, 10, 4),
      revealed = false,
    )

    val state = MuseumUiState(specimens = listOf(oldest, queued, newest))

    assertEquals(listOf("newest", "oldest"), state.visibleSpecimens.map(MuseumSpecimen::id))
  }

  private fun setMuseum(specimens: List<MuseumSpecimen>) {
    composeRule.setContent {
      var filter by remember { mutableStateOf(MuseumTierFilter.ALL) }
      var selectedSpecimenId by remember { mutableStateOf<String?>(null) }
      val state = MuseumUiState(
        specimens = specimens,
        tierFilter = filter,
        selectedSpecimenId = selectedSpecimenId,
      )
      AfterchimeTheme(reduceMotion = true) {
        MuseumScreen(
          state = state,
          onTierFilterSelected = { filter = it },
          onSpecimenSelected = { selectedSpecimenId = it },
        )
      }
    }
  }

  private fun specimen(
    id: String,
    family: Family,
    tier: Tier,
    date: LocalDate = LocalDate.of(2026, 10, 2),
    revealed: Boolean = true,
  ) = MuseumSpecimen(
    id = id,
    anchoredLocalDate = date,
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_420_800_000L,
    revealedAtEpochMillis = if (revealed) 1_759_507_200_000L else null,
    family = family,
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
