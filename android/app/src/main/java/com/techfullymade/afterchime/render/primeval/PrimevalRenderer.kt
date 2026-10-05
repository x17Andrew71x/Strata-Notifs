package com.techfullymade.afterchime.render.primeval

import com.techfullymade.afterchime.render.NORMALIZED_SIZE
import com.techfullymade.afterchime.render.RenderModel
import com.techfullymade.afterchime.render.RenderPrimitive
import com.techfullymade.afterchime.render.RenderViewport
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderPlan
import com.techfullymade.afterchime.render.WorldRenderer
import com.techfullymade.afterchime.render.deterministicCoordinate
import com.techfullymade.afterchime.render.tierRadius

/** Free base world: layered stone with a compact fossil impression. */
class PrimevalRenderer : WorldRenderer {
  override val world = World.PRIMEVAL_STRATA

  override fun createPlan(
    model: RenderModel,
    viewport: RenderViewport,
  ): WorldRenderPlan {
    val bands = model.visual.strataCount
    val bandHeight = NORMALIZED_SIZE / bands
    val stonePalette = listOf(0xFF26332EL, 0xFF38463FL, 0xFF4A5A4DL, 0xFF303C36L)
    val primitives = buildList {
      repeat(bands) { index ->
        val top = index * bandHeight
        add(
          RenderPrimitive.Rect(
            left = 0,
            top = top,
            width = NORMALIZED_SIZE,
            height = if (index == bands - 1) NORMALIZED_SIZE - top else bandHeight,
            argb = stonePalette[(index + model.visual.hueDegrees / 90) % stonePalette.size],
          ),
        )
      }

      val centreX = 4_300 + deterministicCoordinate(model.identitySeed, 1) % 1_400
      val centreY = 4_300 + deterministicCoordinate(model.identitySeed, 2) % 1_400
      val fossilRadius = 1_400 + model.visual.reliefPercent * 10
      repeat(5) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = centreX + index * 260,
            centreY = centreY - index * 180,
            radius = fossilRadius - index * 210,
            argb = if (index % 2 == 0) 0xFF18231FL else 0xFFC9824AL,
          ),
        )
      }

      repeat(model.visual.inclusionDensityPercent / 20 + 1) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = deterministicCoordinate(model.identitySeed, index + 10),
            centreY = deterministicCoordinate(model.identitySeed, index + 20),
            radius = tierRadius(model.tier) / 5,
            argb = 0xFFD9B779L,
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
      backgroundArgb = 0xFF0B0D0CL,
      primitives = primitives,
    )
  }
}
