package com.techfullymade.afterchime.ui.museum

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class SpecimenDetailScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `detail renders only safe specimen presentation and offers contextual back`() {
    var backCount = 0
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        SpecimenDetailScreen(
          specimen = specimen(),
          onBack = { backCount += 1 },
        )
      }
    }

    composeRule.onNodeWithContentDescription("Specimen detail").assertExists()
    composeRule.onNodeWithTag("specimen-detail-renderer").assertExists()
    composeRule.onNodeWithText("Geode").assertExists()
    composeRule.onNodeWithText("Rare").assertExists()
    composeRule.onNodeWithText("Sealed").assertExists()
    composeRule.onNodeWithText("private-test-id").assertDoesNotExist()

    composeRule.onNodeWithTag("specimen-detail-back").performClick()

    assertEquals(1, backCount)
  }

  private fun specimen() = MuseumSpecimen(
    id = "private-test-id",
    anchoredLocalDate = LocalDate.of(2026, 10, 2),
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_420_800_000L,
    revealedAtEpochMillis = 1_759_507_200_000L,
    family = Family.GEODE,
    tier = Tier.RARE,
    visual = VisualParameters(
      hueDegrees = 32,
      strataCount = 7,
      inclusionDensityPercent = 30,
      reliefPercent = 42,
      rotationDegrees = 14,
    ),
  )
}
