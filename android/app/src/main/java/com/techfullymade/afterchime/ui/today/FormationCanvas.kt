package com.techfullymade.afterchime.ui.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.FormationLayer
import com.techfullymade.afterchime.ui.theme.Basalt
import com.techfullymade.afterchime.ui.theme.Sand

/** Draws only coarse local layers. No count, source token, package identity or notification content enters it. */
@Composable
fun FormationCanvas(
  layers: List<FormationLayer>,
  motionEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val settling = remember { Animatable(0.72f) }
  LaunchedEffect(motionEnabled) {
    if (!motionEnabled) {
      settling.snapTo(0.72f)
    } else {
      settling.animateTo(0.96f, tween(durationMillis = 650))
    }
  }
  val description = stringResource(R.string.formation_canvas_description)
  val motionTag = if (motionEnabled) "formation-motion-active" else "formation-motion-paused"
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(240.dp)
      .testTag(motionTag),
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(240.dp)
        .clipToBounds()
        .testTag("formation-canvas")
        .semantics { contentDescription = description },
    ) {
      val palette = listOf(
        Color(0xFF26332E),
        Color(0xFF38463F),
        Color(0xFF4A5A4D),
        Color(0xFF303C36),
        Color(0xFF566251),
        Color(0xFF222B27),
      )
      val bandHeight = size.height / palette.size
      palette.forEachIndexed { index, colour ->
        drawRect(
          color = lerp(Basalt, colour, settling.value),
          topLeft = Offset(0f, index * bandHeight),
          size = Size(size.width, bandHeight + 1f),
        )
      }
      layers
        .sortedWith(compareBy(FormationLayer::localHour, FormationLayer::sourceColourRgb))
        .take(MAX_RENDERED_LAYERS)
        .forEachIndexed { index, layer ->
          val hourPosition = (layer.localHour + 0.5f) / HOURS_IN_DAY
          val categoryOffset = ((layer.category.ordinal % 5) - 2) * size.height * 0.025f
          val centre = Offset(
            x = hourPosition * size.width,
            y = size.height * 0.52f + categoryOffset,
          )
          val colour = Color(0xFF000000L or layer.sourceColourRgb.toLong())
          val radius = size.minDimension * (0.024f + (index % 3) * 0.006f)
          drawCircle(color = colour.copy(alpha = 0.72f), radius = radius, center = centre)
        }
      drawCircle(
        color = Sand.copy(alpha = settling.value * 0.32f),
        radius = size.minDimension * 0.16f,
        center = center,
      )
    }
  }
}

private const val HOURS_IN_DAY = 24f
private const val MAX_RENDERED_LAYERS = 128
