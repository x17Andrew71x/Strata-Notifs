package com.techfullymade.afterchime.ui.more

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MoreScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `settings expose explicit local controls and gate dependent consent`() {
    var openedSettings = false
    var changed = UserPreferences(onboardingComplete = true)
    composeRule.setContent { AfterchimeTheme { MoreScreen(changed, { openedSettings = true }, { changed = it }) } }
    composeRule.onNodeWithTag("setting-online-features").assertExists()
    composeRule.onNodeWithTag("setting-product-analytics").assertExists()
    composeRule.onNodeWithTag("setting-aggregate-sharing").assertExists()
    composeRule.onNodeWithTag("setting-reduce-motion").assertExists()
    composeRule.onNodeWithTag("setting-high-contrast").assertExists()
    composeRule.onNodeWithTag("setting-haptics").assertExists()
    composeRule.onNodeWithTag("setting-product-analytics").assertIsNotEnabled()
    composeRule.onNodeWithTag("setting-aggregate-sharing").assertIsNotEnabled()
    composeRule.onNodeWithTag("setting-haptics").performScrollTo().assertExists()
    composeRule.onNodeWithTag("notification-access").performClick()
    composeRule.runOnIdle { assertEquals(true, openedSettings) }
    composeRule.onNodeWithTag("setting-online-features").performClick()
    composeRule.runOnIdle { assertEquals(true, changed.onlineFeaturesEnabled) }
  }
}
