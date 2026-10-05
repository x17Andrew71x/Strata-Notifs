package com.techfullymade.afterchime.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRendererCanvasTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `every launch renderer mounts an accessible canvas`() {
    composeRule.setContent {
      AfterchimeTheme {
        Column {
          World.entries.forEach { world ->
            WorldRenderers.forWorld(world).Render(
              model = model,
              modifier = Modifier.size(96.dp),
            )
          }
        }
      }
    }

    World.entries.forEach { world ->
      composeRule.onNodeWithContentDescription(world.accessibilityLabel).assertExists()
    }
  }

  private companion object {
    val model = RenderModel(
      identitySeed = 42L,
      family = Family.GEODE,
      tier = Tier.EXCEPTIONAL,
      visual = VisualParameters(
        hueDegrees = 174,
        strataCount = 10,
        inclusionDensityPercent = 60,
        reliefPercent = 82,
        rotationDegrees = 73,
      ),
    )
  }
}
