package com.techfullymade.afterchime.ui.museum

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.techfullymade.afterchime.domain.CollectibleState
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class CombineDialogTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `combine confirmation names the irreversible exchange and only confirms explicitly`() {
    var confirmCount = 0
    var dismissCount = 0
    var outputState by mutableStateOf(CollectibleState.RESTORED)
    composeRule.setContent {
      AfterchimeTheme(reduceMotion = true) {
        CombineDialog(
          outputState = outputState,
          onConfirm = { confirmCount += 1 },
          onDismiss = { dismissCount += 1 },
        )
      }
    }

    composeRule.onNodeWithText("Combine three specimens?").assertExists()
    composeRule.onNodeWithText("Three specimens will be replaced with one Restored specimen.").assertExists()
    composeRule.onNodeWithTag("combine-dialog-cancel").performClick()

    assertEquals(0, confirmCount)
    assertEquals(1, dismissCount)

    composeRule.runOnIdle {
      outputState = CollectibleState.CENTRE_PIECE
    }
    composeRule.onNodeWithTag("combine-dialog-confirm").performClick()

    assertEquals(1, confirmCount)
    assertEquals(1, dismissCount)
  }
}
