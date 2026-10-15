package com.techfullymade.afterchime.ui.worlds

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
  fun `catalogue lists only the launch world without development unlocks`() {
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
    composeRule.onNodeWithTag("world-unlock-primeval_strata").assertDoesNotExist()
    composeRule.onNodeWithTag("world-development-reset").assertDoesNotExist()
  }

  @Test
  fun `non-development catalogue exposes neither unlock nor reset controls`() {
    composeRule.setContent {
      AfterchimeTheme {
        WorldsScreen(
          state = DevelopmentWorldState(),
          developmentControlsEnabled = false,
          onBack = {}, onOwnForDevelopment = {}, onSelect = {}, onResetDevelopment = {},
        )
      }
    }
    composeRule.onNodeWithTag("world-unlock-primeval_strata").assertDoesNotExist()
    composeRule.onNodeWithTag("world-development-reset").assertDoesNotExist()
  }
}
