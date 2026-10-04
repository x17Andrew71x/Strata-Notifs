package com.techfullymade.afterchime.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techfullymade.afterchime.ui.theme.AfterchimeElevation
import com.techfullymade.afterchime.ui.theme.AfterchimeFocus
import com.techfullymade.afterchime.ui.theme.AfterchimeMotion
import com.techfullymade.afterchime.ui.theme.AfterchimeShape
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing
import com.techfullymade.afterchime.ui.theme.AfterchimeTypography
import com.techfullymade.afterchime.ui.theme.Basalt
import com.techfullymade.afterchime.ui.theme.Bone
import com.techfullymade.afterchime.ui.theme.Copper
import com.techfullymade.afterchime.ui.theme.FossilMint
import com.techfullymade.afterchime.ui.theme.afterchimeColorScheme
import com.techfullymade.afterchime.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokenTest {
  @Test
  fun `semantic colours meet text and control contrast requirements`() {
    val standard = afterchimeColorScheme(highContrast = false)
    val highContrast = afterchimeColorScheme(highContrast = true)

    assertTrue(contrastRatio(Bone, Basalt) >= 4.5)
    assertTrue(contrastRatio(FossilMint, Basalt) >= 3.0)
    assertTrue(contrastRatio(Copper, Basalt) >= 4.5)
    assertTrue(contrastRatio(standard.onSurface, standard.surface) >= 4.5)
    assertTrue(contrastRatio(highContrast.onSurface, highContrast.surface) >= 7.0)
  }

  @Test
  fun `spacing motion focus elevation and shapes remain explicit shared tokens`() {
    assertEquals(16.dp, AfterchimeSpacing.screenHorizontal)
    assertEquals(8.dp, AfterchimeSpacing.controlGap)
    assertEquals(12.dp, AfterchimeElevation.raised)
    assertEquals(2.dp, AfterchimeFocus.outlineWidth)
    assertEquals(12.dp, AfterchimeShape.controlCorner)
    assertEquals(32.sp, AfterchimeTypography.headlineLarge.fontSize)
    assertTrue(AfterchimeMotion.standard.settleMillis > 0)
    assertEquals(0, AfterchimeMotion.reduced.settleMillis)
  }
}
