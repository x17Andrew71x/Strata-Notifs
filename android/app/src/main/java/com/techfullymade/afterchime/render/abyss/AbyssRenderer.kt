package com.techfullymade.afterchime.render.abyss

import com.techfullymade.afterchime.render.NORMALIZED_SIZE
import com.techfullymade.afterchime.render.RenderModel
import com.techfullymade.afterchime.render.RenderPrimitive
import com.techfullymade.afterchime.render.RenderViewport
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderPlan
import com.techfullymade.afterchime.render.WorldRenderer
import com.techfullymade.afterchime.render.deterministicCoordinate
import com.techfullymade.afterchime.render.tierRadius

/** Paid cosmetic world: trench currents, shell forms and restrained bioluminescence. */
class AbyssRenderer : WorldRenderer {
  override val world = World.THE_ABYSS

  override fun createPlan(
    model: RenderModel,
    viewport: RenderViewport,
  ): WorldRenderPlan {
    val primitives = buildList {
      repeat(model.visual.strataCount.coerceAtMost(10)) { index ->
        val y = 900 + index * 920
        val offset = deterministicCoordinate(model.identitySeed, index + 150) % 1_400
        add(
          RenderPrimitive.Line(
            startX = 0,
            startY = y,
            endX = NORMALIZED_SIZE,
            endY = (y + offset - 700).coerceIn(0, NORMALIZED_SIZE),
            width = 110 + index * 8,
            argb = if (index % 2 == 0) 0xFF16465CL else 0xFF0E3248L,
          ),
        )
      }

      val shellX = 4_200 + deterministicCoordinate(model.identitySeed, 7) % 1_600
      val shellY = 4_200 + deterministicCoordinate(model.identitySeed, 8) % 1_600
      repeat(4) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = shellX + index * 330,
            centreY = shellY - index * 230,
            radius = 1_250 - index * 190 + tierRadius(model.tier) / 5,
            argb = if (index % 2 == 0) 0xFF2FA6A3L else 0xFF1B6977L,
          ),
        )
      }
      repeat(model.visual.inclusionDensityPercent / 20 + 2) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = deterministicCoordinate(model.identitySeed, index + 180),
            centreY = deterministicCoordinate(model.identitySeed, index + 210),
            radius = 70 + index * 25,
            argb = 0xFF9BE5D7L,
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
      backgroundArgb = 0xFF071B2AL,
      primitives = primitives,
    )
  }
}
