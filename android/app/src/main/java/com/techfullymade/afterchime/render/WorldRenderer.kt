package com.techfullymade.afterchime.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.techfullymade.afterchime.render.abyss.AbyssRenderer
import com.techfullymade.afterchime.render.botanical.BotanicalRenderer
import com.techfullymade.afterchime.render.primeval.PrimevalRenderer
import com.techfullymade.afterchime.render.space.DeepSpaceRenderer
import kotlin.math.roundToInt

/** A renderer produces a deterministic, bounded visual plan for one cosmetic world. */
interface WorldRenderer {
  val world: World

  fun createPlan(
    model: RenderModel,
    viewport: RenderViewport,
  ): WorldRenderPlan

  @Composable
  fun Render(
    model: RenderModel,
    modifier: Modifier = Modifier,
  ) {
    Canvas(
      modifier = modifier
        .clipToBounds()
        .semantics { contentDescription = world.accessibilityLabel },
    ) {
      val viewport = RenderViewport(
        widthPx = size.width.roundToInt().coerceAtLeast(1),
        heightPx = size.height.roundToInt().coerceAtLeast(1),
      )
      drawPlan(createPlan(model, viewport))
    }
  }
}

/** Fixed local renderer catalogue. Entitlements choose worlds elsewhere; this never mutates inventory. */
object WorldRenderers {
  private val renderers = listOf(
    PrimevalRenderer(),
    DeepSpaceRenderer(),
    BotanicalRenderer(),
    AbyssRenderer(),
  )

  fun forWorld(world: World): WorldRenderer = renderers.first { it.world == world }
}

private fun DrawScope.drawPlan(plan: WorldRenderPlan) {
  drawRect(color = Color(plan.backgroundArgb))
  plan.primitives.forEach { primitive ->
    when (primitive) {
      is RenderPrimitive.Rect -> drawRect(
        color = Color(primitive.argb),
        topLeft = Offset(
          x = scaleX(primitive.left),
          y = scaleY(primitive.top),
        ),
        size = Size(
          width = scaleX(primitive.width),
          height = scaleY(primitive.height),
        ),
      )

      is RenderPrimitive.Circle -> drawCircle(
        color = Color(primitive.argb),
        radius = scaleRadius(primitive.radius),
        center = Offset(
          x = scaleX(primitive.centreX),
          y = scaleY(primitive.centreY),
        ),
      )

      is RenderPrimitive.Line -> drawLine(
        color = Color(primitive.argb),
        start = Offset(
          x = scaleX(primitive.startX),
          y = scaleY(primitive.startY),
        ),
        end = Offset(
          x = scaleX(primitive.endX),
          y = scaleY(primitive.endY),
        ),
        strokeWidth = scaleRadius(primitive.width),
      )
    }
  }
}

private fun DrawScope.scaleX(value: Int): Float = size.width * value / NORMALIZED_SIZE

private fun DrawScope.scaleY(value: Int): Float = size.height * value / NORMALIZED_SIZE

private fun DrawScope.scaleRadius(value: Int): Float = size.minDimension * value / NORMALIZED_SIZE
