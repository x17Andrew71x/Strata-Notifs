package com.techfullymade.afterchime.render.space

import com.techfullymade.afterchime.render.NORMALIZED_SIZE
import com.techfullymade.afterchime.render.RenderModel
import com.techfullymade.afterchime.render.RenderPrimitive
import com.techfullymade.afterchime.render.RenderViewport
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderPlan
import com.techfullymade.afterchime.render.WorldRenderer
import com.techfullymade.afterchime.render.deterministicCoordinate
import com.techfullymade.afterchime.render.tierRadius

/** Paid cosmetic world: a sparse stellar field with one orbiting specimen. */
class DeepSpaceRenderer : WorldRenderer {
  override val world = World.DEEP_SPACE

  override fun createPlan(
    model: RenderModel,
    viewport: RenderViewport,
  ): WorldRenderPlan {
    val primitives = buildList {
      repeat(model.visual.strataCount + 4) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = deterministicCoordinate(model.identitySeed, index + 30),
            centreY = deterministicCoordinate(model.identitySeed, index + 50),
            radius = 35 + index % 3 * 25,
            argb = if (index % 3 == 0) 0xFFD9B779L else 0xFFEAE7DEL,
          ),
        )
      }

      val planetX = 4_000 + deterministicCoordinate(model.identitySeed, 3) % 2_000
      val planetY = 4_000 + deterministicCoordinate(model.identitySeed, 4) % 2_000
      add(
        RenderPrimitive.Circle(
          centreX = planetX,
          centreY = planetY,
          radius = 1_450 + model.visual.reliefPercent * 8,
          argb = 0xFF273A65L,
        ),
      )
      add(
        RenderPrimitive.Circle(
          centreX = planetX - 360,
          centreY = planetY - 260,
          radius = 860 + tierRadius(model.tier) / 3,
          argb = 0xFF65C6B4L,
        ),
      )
      repeat(model.visual.inclusionDensityPercent / 25 + 2) { index ->
        val startX = deterministicCoordinate(model.identitySeed, index + 70)
        val startY = deterministicCoordinate(model.identitySeed, index + 90)
        add(
          RenderPrimitive.Line(
            startX = startX,
            startY = startY,
            endX = (startX + 1_000 + index * 180).coerceAtMost(NORMALIZED_SIZE),
            endY = (startY + 400).coerceAtMost(NORMALIZED_SIZE),
            width = 45 + index * 10,
            argb = 0xFF7E9DDEL,
          ),
        )
      }
    }

    return WorldRenderPlan(
      world = world,
      identitySeed = model.identitySeed,
      family = model.family,
      tier = model.tier,
      viewport = viewport,
      backgroundArgb = 0xFF080B18L,
      primitives = primitives,
    )
  }
}
