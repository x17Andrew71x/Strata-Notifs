package com.techfullymade.afterchime.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.techfullymade.afterchime.ui.navigation.AfterchimeNavGraph
import com.techfullymade.afterchime.ui.navigation.RootDestination
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MuseumDetailNavigationTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `museum selection opens a contextual specimen detail and back restores the museum root`() {
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(
          onEnableNotificationAccess = {},
          initialNavigation = AfterchimeNavGraph.initial().selectRoot(RootDestination.MUSEUM),
          museumContent = { openSpecimen ->
            Text(
              text = "Open specimen",
              modifier = Modifier
                .testTag("open-specimen")
                .clickable { openSpecimen("specimen-1") },
            )
          },
          museumDetailContent = { specimenId, onBack ->
            Text(text = specimenId, modifier = Modifier.testTag("opened-specimen"))
            Text(
              text = "Back to museum",
              modifier = Modifier
                .testTag("detail-back")
                .clickable(onClick = onBack),
            )
          },
        )
      }
    }

    composeRule.onNodeWithTag("open-specimen").performClick()

    composeRule.onNodeWithTag("opened-specimen").assertExists()
    composeRule.onNodeWithTag("root-museum").assertDoesNotExist()

    composeRule.onNodeWithTag("detail-back").performClick()

    composeRule.onNodeWithTag("open-specimen").assertExists()
    composeRule.onNodeWithTag("root-museum").assertExists()
  }
}
