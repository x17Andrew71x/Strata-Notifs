package com.techfullymade.afterchime.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.techfullymade.afterchime.ui.navigation.AfterchimeNavGraph
import com.techfullymade.afterchime.ui.navigation.RootDestination
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import com.techfullymade.afterchime.ui.theme.Slate
import org.junit.Assert.assertEquals
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
  fun `incomplete onboarding blocks app roots until explicit persisted completion callback`() {
    var completed = false
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(onEnableNotificationAccess = {}, preferences = UserPreferences(), onOnboardingComplete = { completed = true })
      }
    }
    composeRule.onNodeWithTag("onboarding-screen").assertExists()
    composeRule.onNodeWithTag("root-today").assertDoesNotExist()
    composeRule.onNodeWithText("Continue").performClick()
    composeRule.runOnIdle { assert(completed) }
  }

  @Test
  fun `onboarding presents readable copy on the app background`() {
    val titleLayouts = mutableListOf<TextLayoutResult>()
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(onEnableNotificationAccess = {}, preferences = UserPreferences())
      }
    }

    composeRule
      .onNodeWithText("A quiet collection, kept here")
      .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(titleLayouts) }
    assertEquals(Slate, titleLayouts.single().layoutInput.style.color)
    composeRule.onNodeWithTag("onboarding-continue").assertIsDisplayed()
  }

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
  fun `world catalogue is nested under More and back restores the four-root navigation`() {
    composeRule.setContent {
      AfterchimeTheme {
        AfterchimeApp(onEnableNotificationAccess = {})
      }
    }
    composeRule.onNodeWithTag("root-more").performClick()
    composeRule.onNodeWithTag("more-worlds").performClick()
    composeRule.onNodeWithTag("worlds-screen").assertExists()
    RootDestination.entries.forEach { destination ->
      composeRule.onNodeWithTag("root-${destination.route}").assertDoesNotExist()
    }
    composeRule.onNodeWithTag("worlds-back").performClick()
    composeRule.onNodeWithTag("root-content-more").assertExists()
    assertEquals(4, RootDestination.entries.size)
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
