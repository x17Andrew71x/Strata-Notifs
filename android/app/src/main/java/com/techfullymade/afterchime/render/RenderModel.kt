package com.techfullymade.afterchime.render

import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The four launch presentation worlds. A world changes appearance, never specimen value. */
enum class World(
  val accessibilityLabel: String,
) {
  PRIMEVAL_STRATA("Primeval Strata specimen"),
  DEEP_SPACE("Deep Space specimen"),
  BOTANICAL_ARCHIVE("Botanical Archive specimen"),
  THE_ABYSS("The Abyss specimen"),
}

/** Pixel target for a deterministic renderer plan. */
data class RenderViewport(
  val widthPx: Int,
  val heightPx: Int,
) {
  init {
    require(widthPx > 0)
    require(heightPx > 0)
  }

  companion object {
    val phone = RenderViewport(widthPx = 1080, heightPx = 1920)
    val tablet = RenderViewport(widthPx = 1600, heightPx = 2560)
    val export = RenderViewport(widthPx = 2048, heightPx = 2048)
    val goldenSizes = listOf(phone, tablet, export)
  }
}

/**
 * Platform-neutral renderer input derived solely from immutable specimen output.
 * The opaque seed is derived locally from renderer-safe output and is never an export identifier.
 */
data class RenderModel(
  val identitySeed: Long,
  val family: Family,
  val tier: Tier,
  val visual: VisualParameters,
)

/** Converts a sealed local specimen into the smallest safe rendering contract. */
fun MuseumSpecimen.toRenderModel(): RenderModel {
  val safeVisual = visual
  val canonicalOutput = listOf(
    generatorVersion,
    anchoredLocalDate,
    family.name,
    tier.name,
    safeVisual.hueDegrees,
    safeVisual.strataCount,
    safeVisual.inclusionDensityPercent,
    safeVisual.reliefPercent,
    safeVisual.rotationDegrees,
  ).joinToString(separator = "|")
  val digest = MessageDigest.getInstance("SHA-256").digest(canonicalOutput.toByteArray(Charsets.UTF_8))

  return RenderModel(
    identitySeed = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long,
    family = family,
    tier = tier,
    visual = safeVisual,
  )
}

/** Structural golden plan using normalised coordinates, rendered by Android without source data. */
data class WorldRenderPlan(
  val world: World,
  val identitySeed: Long,
  val family: Family,
  val tier: Tier,
  val viewport: RenderViewport,
  val backgroundArgb: Long,
  val primitives: List<RenderPrimitive>,
) {
  init {
    require(primitives.isNotEmpty())
  }

  fun fingerprint(): String = sha256Hex(
    buildString {
      append(world.name)
      append('|')
      append(identitySeed)
      append('|')
      append(family.name)
      append('|')
      append(tier.name)
      append('|')
      append(viewport.widthPx)
      append('x')
      append(viewport.heightPx)
      append('|')
      append(backgroundArgb)
      primitives.forEach {
        append('|')
        append(it.canonicalForm())
      }
    },
  )
}

sealed interface RenderPrimitive {
  fun canonicalForm(): String

  data class Rect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val argb: Long,
  ) : RenderPrimitive {
    init {
      require(left in 0..NORMALIZED_SIZE && top in 0..NORMALIZED_SIZE)
      require(width in 1..NORMALIZED_SIZE && height in 1..NORMALIZED_SIZE)
      require(left + width <= NORMALIZED_SIZE && top + height <= NORMALIZED_SIZE)
    }

    override fun canonicalForm(): String = "r:$left,$top,$width,$height,$argb"
  }

  data class Circle(
    val centreX: Int,
    val centreY: Int,
    val radius: Int,
    val argb: Long,
  ) : RenderPrimitive {
    init {
      require(centreX in 0..NORMALIZED_SIZE && centreY in 0..NORMALIZED_SIZE)
      require(radius in 1..NORMALIZED_SIZE)
    }

    override fun canonicalForm(): String = "c:$centreX,$centreY,$radius,$argb"
  }

  data class Line(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val width: Int,
    val argb: Long,
  ) : RenderPrimitive {
    init {
      require(startX in 0..NORMALIZED_SIZE && startY in 0..NORMALIZED_SIZE)
      require(endX in 0..NORMALIZED_SIZE && endY in 0..NORMALIZED_SIZE)
      require(width in 1..NORMALIZED_SIZE)
    }

    override fun canonicalForm(): String = "l:$startX,$startY,$endX,$endY,$width,$argb"
  }
}

internal const val NORMALIZED_SIZE = 10_000

internal fun deterministicCoordinate(seed: Long, index: Int): Int {
  val mixed = seed xor (index.toLong() * 0x9E3779B97F4A7C15UL.toLong())
  return (mixed and Long.MAX_VALUE).rem(NORMALIZED_SIZE.toLong()).toInt()
}

internal fun tierRadius(tier: Tier): Int = when (tier) {
  Tier.COMMON -> 380
  Tier.UNCOMMON -> 520
  Tier.RARE -> 660
  Tier.EXCEPTIONAL -> 820
  Tier.SINGULAR -> 1_000
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8))
  .joinToString(separator = "") { byte -> "%02x".format(byte) }
