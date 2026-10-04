package com.techfullymade.afterchime.ui.today

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.domain.FormationLayer
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `awaiting access offers only the permission action`() {
    setScreen(snapshot(observation = FormationObservation.AwaitingAccess))

    composeRule.onNodeWithText("Notification access is needed to observe today.").assertExists()
    composeRule.onNodeWithText("Start collecting").assertExists()
    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `quiet active formation remains a valid visual state without a count`() {
    setScreen(snapshot(observation = FormationObservation.Active))

    composeRule.onNodeWithText("Today is still forming").assertExists()
    composeRule.onNodeWithText("A quiet day can become a clean mineral plate.").assertExists()
    composeRule.onNodeWithTag("formation-canvas").assertExists()
    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `noisy active formation renders reduced layers without a reward action`() {
    setScreen(
      snapshot(
        observation = FormationObservation.Active,
        layers = listOf(layer(), layer().copy(localHour = 18)),
      ),
    )

    composeRule.onNodeWithTag("formation-canvas").assertExists()
    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `unobserved sealed day is not turned into a quiet reward`() {
    setScreen(snapshot(observation = FormationObservation.SealedUnobserved))

    composeRule.onNodeWithText("Unobserved day").assertExists()
    composeRule.onNodeWithTag("formation-canvas").assertDoesNotExist()
    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `disconnected observation is never presented as quiet`() {
    setScreen(snapshot(observation = FormationObservation.Disconnected))

    composeRule.onNodeWithText("Observation paused").assertExists()
    composeRule.onNodeWithText("Today will stay separate from a quiet day until access returns.").assertExists()
    composeRule.onNodeWithText("Open notification settings").assertExists()
  }

  @Test
  fun `sealed unrevealed specimen exposes one reveal action`() {
    var revealedId: String? = null
    setScreen(
      snapshot(
        observation = FormationObservation.SealedObserved,
        specimen = specimen(),
      ),
      onReveal = { revealedId = it },
    )

    composeRule.onNodeWithText("Sealed formation").assertExists()
    composeRule.onNodeWithText("Reveal specimen").performClick()

    check(revealedId == "specimen-1")
  }

  @Test
  fun `reveal transition suppresses a duplicate reveal action`() {
    val state = TodayUiState.from(
      snapshot(
        observation = FormationObservation.SealedObserved,
        specimen = specimen(),
      ),
      revealInFlight = true,
    )
    composeRule.setContent {
      AfterchimeTheme {
        TodayScreen(
          state = state,
          onEnableNotificationAccess = {},
          onReveal = {},
          reduceMotion = true,
        )
      }
    }

    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `revealed specimen is distinct from an unrevealed sealed formation`() {
    setScreen(
      snapshot(
        observation = FormationObservation.SealedObserved,
        specimen = specimen(revealedAtEpochMillis = 2_000L),
      ),
    )

    composeRule.onNodeWithText("Specimen revealed").assertExists()
    composeRule.onNodeWithText("Reveal specimen").assertDoesNotExist()
  }

  @Test
  fun `formation motion is explicitly paused when the app is backgrounded or reduced`() {
    val state = TodayUiState.from(snapshot(observation = FormationObservation.Active, layers = listOf(layer())))
    composeRule.setContent {
      AfterchimeTheme {
        TodayScreen(
          state = state,
          onEnableNotificationAccess = {},
          onReveal = {},
          isAppInForeground = false,
          reduceMotion = true,
        )
      }
    }

    composeRule.onNodeWithTag("formation-motion-paused").assertExists()
    composeRule.onNodeWithTag("formation-motion-active").assertDoesNotExist()
  }

  private fun setScreen(
    snapshot: FormationSnapshot,
    onReveal: (String) -> Unit = {},
  ) {
    composeRule.setContent {
      AfterchimeTheme {
        TodayScreen(
          state = TodayUiState.from(snapshot),
          onEnableNotificationAccess = {},
          onReveal = onReveal,
          reduceMotion = true,
        )
      }
    }
  }

  private fun snapshot(
    observation: FormationObservation,
    layers: List<FormationLayer> = emptyList(),
    specimen: MuseumSpecimen? = null,
  ) = FormationSnapshot(
    localDate = LocalDate.of(2026, 10, 4),
    observation = observation,
    layers = layers,
    sealedSpecimen = specimen,
  )

  private fun layer() = FormationLayer(
    localHour = 8,
    category = CoarseNotificationCategory.EVENT,
    sourceColourRgb = 0x6D8779,
  )

  private fun specimen(revealedAtEpochMillis: Long? = null) = MuseumSpecimen(
    id = "specimen-1",
    anchoredLocalDate = LocalDate.of(2026, 10, 3),
    generatorVersion = 1,
    createdAtEpochMillis = 1_000L,
    revealedAtEpochMillis = revealedAtEpochMillis,
    family = Family.AMMONITE,
    tier = Tier.UNCOMMON,
    visual = VisualParameters(
      hueDegrees = 32,
      strataCount = 7,
      inclusionDensityPercent = 30,
      reliefPercent = 42,
      rotationDegrees = 14,
    ),
  )
}
