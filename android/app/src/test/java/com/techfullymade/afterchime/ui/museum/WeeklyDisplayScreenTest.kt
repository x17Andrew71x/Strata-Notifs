package com.techfullymade.afterchime.ui.museum

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.domain.WeeklyDisplay
import com.techfullymade.afterchime.domain.WeeklyDisplaySlot
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class WeeklyDisplayScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `renders exactly seven days in supplied chronological order`() {
    setDisplay(display())

    (1..7).forEach { index ->
      composeRule.onNodeWithTag("weekly-display-day-$index").assertExists()
    }
    composeRule.onNodeWithTag("weekly-display-day-8").assertDoesNotExist()
    composeRule.onNodeWithTag("weekly-display-day-2").assertTextContains("Sealed formation")
    composeRule.onNodeWithTag("weekly-display-day-1").assertTextContains("Missing day")
    composeRule.onNodeWithTag("weekly-display-day-4").assertTextContains("Unobserved day")
    (1..7).forEach { index ->
      val state = when (index) {
        2 -> "sealed formation"
        4 -> "not observed"
        else -> "missing specimen"
      }
      composeRule.onNodeWithContentDescription("Day $index, ${date(index)}, $state").assertExists()
    }
    composeRule.onNodeWithTag("weekly-display-day-7").performScrollTo().assertExists()
  }

  @Test
  fun `missing and unobserved days have distinct visible and accessible meaning`() {
    setDisplay(display())

    composeRule.onNodeWithTag("weekly-display-day-1")
      .assertTextContains("Missing day")
      .assertTextContains("This day has no sealed specimen.")
    composeRule.onNodeWithTag("weekly-display-day-4")
      .assertTextContains("Unobserved day")
      .assertTextContains("This day was not observed.")
  }

  @Test
  fun `each world value mounts its renderer for sealed specimens`() {
    var selectedWorld by mutableStateOf(World.PRIMEVAL_STRATA)
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        WeeklyDisplayScreen(display = display(), world = selectedWorld)
      }
    }
    World.entries.forEach { world ->
      composeRule.runOnIdle { selectedWorld = world }
      composeRule.onNodeWithContentDescription(world.accessibilityLabel).assertExists()
    }
  }

  private fun setDisplay(display: WeeklyDisplay) {
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        WeeklyDisplayScreen(display = display, world = World.PRIMEVAL_STRATA)
      }
    }
  }

  private fun display(): WeeklyDisplay {
    val start = LocalDate.of(2026, 10, 1)
    return WeeklyDisplay(
      days = (0..6).map { offset ->
        val date = start.plusDays(offset.toLong())
        when (offset) {
          1 -> WeeklyDisplaySlot.Sealed(date, specimen(date))
          3 -> WeeklyDisplaySlot.Unobserved(date)
          else -> WeeklyDisplaySlot.Missing(date)
        }
      },
    )
  }

  private fun date(day: Int) = LocalDate.of(2026, 10, day).format(
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
      .withLocale(java.util.Locale.getDefault()),
  )

  private fun specimen(date: LocalDate) = MuseumSpecimen(
    id = "private-test-id",
    anchoredLocalDate = date,
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
