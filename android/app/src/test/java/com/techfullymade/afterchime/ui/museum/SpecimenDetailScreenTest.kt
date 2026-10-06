package com.techfullymade.afterchime.ui.museum

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
  fun `detail renderer follows selected cosmetic world`() {
    composeRule.setContent {
      AfterchimeTheme {
        SpecimenDetailScreen(
          specimen = specimen(),
          world = com.techfullymade.afterchime.render.World.DEEP_SPACE,
          onBack = {}, onSetLocked = {}, onBeginCombine = {},
        )
      }
    }
    composeRule.onNodeWithContentDescription("Deep Space specimen").assertExists()
    composeRule.onNodeWithTag("specimen-detail-renderer").assertExists()
  }

  @Test
  fun `detail renders only safe specimen presentation and offers contextual back and lock`() {
    var backCount = 0
    val lockRequests = mutableListOf<Boolean>()
    var combineCount = 0
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        SpecimenDetailScreen(
          specimen = specimen(),
          onBack = { backCount += 1 },
          onSetLocked = { locked -> lockRequests.add(locked); Unit },
          onBeginCombine = { combineCount += 1 },
        )
      }
    }

    composeRule.onNodeWithContentDescription("Specimen detail").assertExists()
    composeRule.onNodeWithTag("specimen-detail-renderer").assertExists()
    composeRule.onNodeWithText("Geode").assertExists()
    composeRule.onNodeWithText("Rare").assertExists()
    composeRule.onNodeWithText("Sealed").assertExists()
    composeRule.onNodeWithText("private-test-id").assertDoesNotExist()

    composeRule.onNodeWithTag("specimen-detail-lock").performScrollTo().performClick()
    composeRule.onNodeWithTag("specimen-detail-combine").performScrollTo().performClick()
    composeRule.onNodeWithTag("specimen-detail-back").performScrollTo().performClick()

    assertEquals(listOf(true), lockRequests)
    assertEquals(1, combineCount)
    assertEquals(1, backCount)
  }

  @Test
  fun `locked specimen offers an explicit unlock action`() {
    val lockRequests = mutableListOf<Boolean>()
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        SpecimenDetailScreen(
          specimen = specimen().copy(isLocked = true),
          onBack = {},
          onSetLocked = { locked -> lockRequests.add(locked); Unit },
          onBeginCombine = {},
        )
      }
    }

    composeRule.onNodeWithText("Locked").assertExists()
    composeRule.onNodeWithText("Unlock specimen").assertExists()
    composeRule.onNodeWithTag("specimen-detail-combine").assertDoesNotExist()
    composeRule.onNodeWithTag("specimen-detail-lock").performScrollTo().performClick()

    assertEquals(listOf(false), lockRequests)
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
