package com.techfullymade.afterchime.ui.onboarding

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `explains local privacy defaults and completes only on explicit action`() {
    var completed = false
    composeRule.setContent { AfterchimeTheme { OnboardingScreen(onComplete = { completed = true }) } }
    composeRule.onNodeWithText("reduced local notification timing", substring = true).assertExists()
    composeRule.onNodeWithText("starts off", substring = true).assertExists()
    composeRule.onNodeWithText("never kept", substring = true).assertExists()
    composeRule.onNodeWithText("optional and off", substring = true).assertExists()
    assertTrue(!completed)
    composeRule.onNodeWithTag("onboarding-continue").performScrollTo().assertExists()
    composeRule.onNodeWithTag("onboarding-continue").performClick()
    composeRule.runOnIdle { assertTrue(completed) }
  }
}
