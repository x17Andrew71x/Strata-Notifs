package com.techfullymade.afterchime.render

import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererGoldenTest {
  @Test
  fun `launch-world plans match deterministic structural goldens at every supported size`() {
    val model = specimen().toRenderModel()
    val actual = World.entries.flatMap { world ->
      RenderViewport.goldenSizes.map { viewport ->
        (world to viewport) to WorldRenderers.forWorld(world).createPlan(model, viewport).fingerprint()
      }
    }.toMap()

    assertEquals(goldens, actual)
  }

  @Test
  fun `launch renderer retains specimen identity and tier`() {
    val model = specimen().toRenderModel()
    val plans = World.entries.map { world ->
      WorldRenderers.forWorld(world).createPlan(model, RenderViewport.phone)
    }

    assertTrue(plans.all { it.identitySeed == model.identitySeed })
    assertTrue(plans.all { it.family == model.family && it.tier == model.tier })
    assertEquals(World.entries.size, plans.map(WorldRenderPlan::fingerprint).toSet().size)
  }

  @Test
  fun `render model retains only renderer-safe generated output`() {
    val fields = RenderModel::class.java.declaredFields
      .filterNot { it.isSynthetic || it.name == "\$stable" }
      .map { it.name }
      .toSet()

    assertEquals(setOf("identitySeed", "family", "tier", "visual"), fields)
    assertFalse(fields.any { it.contains("package", ignoreCase = true) })
    assertFalse(fields.any { it.contains("token", ignoreCase = true) })
    assertFalse(fields.any { it.contains("count", ignoreCase = true) })
  }

  private fun specimen() = MuseumSpecimen(
    id = "specimen-2026-10-03",
    anchoredLocalDate = LocalDate.of(2026, 10, 3),
    generatorVersion = 1,
    createdAtEpochMillis = 1_791_000_000_000L,
    revealedAtEpochMillis = 1_791_000_100_000L,
    family = Family.AMMONITE,
    tier = Tier.RARE,
    visual = VisualParameters(
      hueDegrees = 32,
      strataCount = 7,
      inclusionDensityPercent = 30,
      reliefPercent = 42,
      rotationDegrees = 14,
    ),
  )

  private companion object {
    val goldens = mapOf(
      (World.PRIMEVAL_STRATA to RenderViewport.phone) to "0e0489f19f3702df23df82fc1fe96384976df83e698d86080b0590e12f719cc9",
      (World.PRIMEVAL_STRATA to RenderViewport.tablet) to "918d79824362d44a485d15c00196f8c9ebb823ef2ef31829000be9ab9b636775",
      (World.PRIMEVAL_STRATA to RenderViewport.export) to "3aa65f7c69b8938c1a467c7ea7fd39c1464525983bfa916d58448b7c32ca13e7",
    )
  }
}
