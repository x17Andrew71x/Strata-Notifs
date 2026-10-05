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
  fun `all worlds retain one specimen identity and tier while rendering distinct plans`() {
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
      (World.DEEP_SPACE to RenderViewport.phone) to "63edb23294cfceb89dee0b5142f05c40cf15db711160dfd28efab42c87d252ad",
      (World.DEEP_SPACE to RenderViewport.tablet) to "7b1e8dcf940c29379b64982d7ada93ca334ece9ef86572db2ec04a7b4237d1ed",
      (World.DEEP_SPACE to RenderViewport.export) to "0f56462d91153c40637b22a0f05434d290b92c015bf2d7b412f319df30690e6b",
      (World.BOTANICAL_ARCHIVE to RenderViewport.phone) to "8edfe4150004fce18209da1cab378af544d684ee199deba95778ae288f04e2ba",
      (World.BOTANICAL_ARCHIVE to RenderViewport.tablet) to "5f00c9b822a900b09bd34c2497f8a49b10c6f5c400ddb8a1a2255be3d93f6514",
      (World.BOTANICAL_ARCHIVE to RenderViewport.export) to "f2a11e59aa07ffecc9f2fc4d9de57931a5d6a1a49b1af4f2876c10b424a54eb0",
      (World.THE_ABYSS to RenderViewport.phone) to "04330891f90da61c2cee98a8ca6886915b4ee12165a67f659f149dd760ed9dc0",
      (World.THE_ABYSS to RenderViewport.tablet) to "689fc5b2f1d96ad7eb2cfda6c9b1958344b4ce45345657bd300d389fb2f72528",
      (World.THE_ABYSS to RenderViewport.export) to "3fdb9b3cab80a0b38db6ac02205be82ae08b61b1c5c708c7c73407ded5367edb",
    )
  }
}
