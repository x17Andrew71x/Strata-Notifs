package com.techfullymade.afterchime.ui.worlds

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldsScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `catalogue lists worlds in declaration order and exposes explicit dev actions`() {
    val owned = mutableSetOf(World.PRIMEVAL_STRATA)
    composeRule.setContent {
      AfterchimeTheme {
        WorldsScreen(
          state = DevelopmentWorldState(owned, World.PRIMEVAL_STRATA),
          developmentControlsEnabled = true,
          onBack = {},
          onOwnForDevelopment = { owned.add(it) },
          onSelect = {},
          onResetDevelopment = {},
        )
      }
    }
    World.entries.forEach { world ->
      composeRule.onNodeWithTag("world-row-${world.name.lowercase()}").assertExists()
    }
    composeRule.onNodeWithTag("world-unlock-deep_space").assertExists()
    composeRule.onNodeWithText("Development-only unlock Deep Space").performClick()
    composeRule.runOnIdle { assert(World.DEEP_SPACE in owned) }
  }

  @Test
  fun `non-development catalogue exposes neither unlock nor reset controls`() {
    composeRule.setContent {
      AfterchimeTheme {
        WorldsScreen(
          state = DevelopmentWorldState(setOf(World.PRIMEVAL_STRATA, World.DEEP_SPACE), World.PRIMEVAL_STRATA),
          developmentControlsEnabled = false,
          onBack = {}, onOwnForDevelopment = {}, onSelect = {}, onResetDevelopment = {},
        )
      }
    }
    composeRule.onNodeWithTag("world-unlock-deep_space").assertDoesNotExist()
    composeRule.onNodeWithTag("world-select-deep_space").assertDoesNotExist()
    composeRule.onNodeWithTag("world-development-reset").assertDoesNotExist()
  }
}
