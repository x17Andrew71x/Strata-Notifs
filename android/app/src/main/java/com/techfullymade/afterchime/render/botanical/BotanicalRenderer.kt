package com.techfullymade.afterchime.render.botanical

import com.techfullymade.afterchime.render.RenderModel
import com.techfullymade.afterchime.render.RenderPrimitive
import com.techfullymade.afterchime.render.RenderViewport
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderPlan
import com.techfullymade.afterchime.render.WorldRenderer
import com.techfullymade.afterchime.render.deterministicCoordinate
import com.techfullymade.afterchime.render.tierRadius

/** Paid cosmetic world: a pressed stem with leaves and seed-like inclusions. */
class BotanicalRenderer : WorldRenderer {
  override val world = World.BOTANICAL_ARCHIVE

  override fun createPlan(
    model: RenderModel,
    viewport: RenderViewport,
  ): WorldRenderPlan {
    val primitives = buildList {
      val stemX = 4_400 + deterministicCoordinate(model.identitySeed, 5) % 1_200
      add(
        RenderPrimitive.Line(
          startX = stemX,
          startY = 8_800,
          endX = stemX + model.visual.rotationDegrees % 800 - 400,
          endY = 1_000,
          width = 130 + model.visual.reliefPercent * 2,
          argb = 0xFF8DAA71L,
        ),
      )
      repeat(model.visual.strataCount.coerceAtMost(10)) { index ->
        val branchY = 1_600 + index * 760
        val direction = if (index % 2 == 0) 1 else -1
        val leafX = (stemX + direction * (1_000 + index % 3 * 300)).coerceIn(600, 9_400)
        add(
          RenderPrimitive.Line(
            startX = stemX,
            startY = branchY + 260,
            endX = leafX,
            endY = branchY,
            width = 80,
            argb = 0xFF567148L,
          ),
        )
        add(
          RenderPrimitive.Circle(
            centreX = leafX,
            centreY = branchY,
            radius = 460 + tierRadius(model.tier) / 3,
            argb = if (index % 2 == 0) 0xFF99B878L else 0xFFC9827DL,
          ),
        )
      }
      repeat(model.visual.inclusionDensityPercent / 25 + 1) { index ->
        add(
          RenderPrimitive.Circle(
            centreX = deterministicCoordinate(model.identitySeed, index + 110),
            centreY = deterministicCoordinate(model.identitySeed, index + 130),
            radius = 120 + index * 20,
            argb = 0xFFE4C78BL,
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
      backgroundArgb = 0xFF172016L,
      primitives = primitives,
    )
  }
}
