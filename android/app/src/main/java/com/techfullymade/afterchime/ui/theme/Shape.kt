package com.techfullymade.afterchime.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class AfterchimeMotionSpec(
  val revealMillis: Int,
  val settleMillis: Int,
)

object AfterchimeSpacing {
  val screenHorizontal: Dp = 16.dp
  val screenVertical: Dp = 20.dp
  val section: Dp = 24.dp
  val content: Dp = 16.dp
  val controlGap: Dp = 8.dp
}

object AfterchimeElevation {
  val flat: Dp = 0.dp
  val raised: Dp = 12.dp
}

object AfterchimeFocus {
  val outlineColor: Color = FossilMint
  val outlineWidth: Dp = 2.dp
}

object AfterchimeShape {
  val controlCorner: Dp = 12.dp
  val surfaceCorner: Dp = 20.dp
  val previewCorner: Dp = 24.dp
}

val AfterchimeShapes = Shapes(
  extraSmall = RoundedCornerShape(AfterchimeSpacing.controlGap),
  small = RoundedCornerShape(AfterchimeShape.controlCorner),
  medium = RoundedCornerShape(AfterchimeShape.surfaceCorner),
  large = RoundedCornerShape(AfterchimeShape.previewCorner),
)

object AfterchimeMotion {
  val standard = AfterchimeMotionSpec(revealMillis = 360, settleMillis = 180)
  val reduced = AfterchimeMotionSpec(revealMillis = 0, settleMillis = 0)
}

val LocalAfterchimeMotion = staticCompositionLocalOf { AfterchimeMotion.standard }
