package com.techfullymade.afterchime.ui

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class AfterchimeAppTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `root navigation exposes four labelled destinations and swaps root content`() {
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(onEnableNotificationAccess = {})
      }
    }

    RootDestination.entries.forEach { destination ->
      composeRule
        .onNodeWithContentDescription(destination.talkBackLabel, useUnmergedTree = true)
        .assertExists()
      composeRule.onNodeWithTag("root-${destination.route}").performClick()
      composeRule.onNodeWithTag("root-content-${destination.route}").assertExists()
    }
  }

  @Test
  fun `nested destination hides root navigation and back returns to its selected root`() {
    val nested = AfterchimeNavGraph.initial()
      .selectRoot(RootDestination.MUSEUM)
      .openNested("museum/specimen/42")
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(
          onEnableNotificationAccess = {},
          initialNavigation = nested,
        )
      }
    }

    composeRule.onNodeWithTag("nested-content").assertExists()
    RootDestination.entries.forEach { destination ->
      composeRule.onNodeWithTag("root-${destination.route}").assertDoesNotExist()
    }

    composeRule.onNodeWithTag("nested-back").performClick()

    composeRule.onNodeWithTag("root-content-museum").assertExists()
    RootDestination.entries.forEach { destination ->
      composeRule.onNodeWithTag("root-${destination.route}").assertExists()
    }
  }

  @Test
  fun `selected root survives saved state restoration`() {
    val restorationTester = StateRestorationTester(composeRule)
    restorationTester.setContent {
      AfterchimeTheme {
        AfterchimeApp(onEnableNotificationAccess = {})
      }
    }
    composeRule.onNodeWithTag("root-community").performClick()

    restorationTester.emulateSavedInstanceStateRestore()

    composeRule.onNodeWithTag("root-content-community").assertExists()
  }
}
