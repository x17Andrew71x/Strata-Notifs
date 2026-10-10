package com.techfullymade.afterchime.gameplay

import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One individually tunable entry in the local Relic Vault draw. */
data class FossilCatalogItem(
  val id: String,
  val tier: Tier,
  val selectionWeight: Int,
  val family: Family,
  val visual: VisualParameters,
) {
  init {
    require(id.matches(ARTIFACT_ID_PATTERN))
    require(selectionWeight > 0)
  }

  private companion object {
    val ARTIFACT_ID_PATTERN = Regex("[a-z0-9-]{1,64}")
  }
}

object FossilCatalog {
  /**
   * Research-backed initial tuning: 86% common, 12% uncommon, 2% rare.
   * Equal weights within each tier keep the approved launch set unbiased.
   */
  val items: List<FossilCatalogItem> = listOf(
    fossil("relic-dactylioceras-ammonite", Tier.COMMON, 86, Family.AMMONITE, 32, 8, 30, 50, 0),
    fossil("relic-belemnite-rostra", Tier.COMMON, 86, Family.BELEMNITE, 36, 6, 28, 44, 8),
    fossil("relic-spiriferid-brachiopod", Tier.COMMON, 86, Family.BRACHIOPOD, 30, 7, 32, 48, 0),
    fossil("relic-gryphaea-oyster", Tier.COMMON, 86, Family.BIVALVE, 28, 6, 34, 52, 14),
    fossil("relic-crinoid-columnals", Tier.COMMON, 86, Family.CRINOID, 34, 9, 38, 55, 0),
    fossil("relic-rugose-horn-coral", Tier.COMMON, 86, Family.CORAL, 26, 8, 36, 58, 22),
    fossil("relic-lamniform-shark-tooth", Tier.COMMON, 86, Family.SHARK_TOOTH, 24, 5, 30, 50, 0),
    fossil("relic-carbonised-fern-frond", Tier.COMMON, 86, Family.FERN_IMPRINT, 30, 10, 42, 46, 0),
    fossil("relic-domal-stromatolite", Tier.COMMON, 86, Family.STROMATOLITE, 32, 12, 40, 54, 0),
    fossil("relic-echinocorys-echinoid", Tier.COMMON, 86, Family.ECHINOID, 38, 8, 34, 52, 0),
    fossil("relic-articulated-trilobite", Tier.UNCOMMON, 24, Family.TRILOBITE, 28, 10, 52, 70, 0),
    fossil("relic-articulated-fossil-fish", Tier.UNCOMMON, 24, Family.FISH, 30, 12, 56, 74, 0),
    fossil("relic-complete-starfish", Tier.UNCOMMON, 24, Family.STARFISH, 34, 10, 58, 72, 0),
    fossil("relic-articulated-fossil-crab", Tier.UNCOMMON, 24, Family.CRAB, 28, 11, 60, 76, 0),
    fossil("relic-insect-amber", Tier.UNCOMMON, 24, Family.AMBER, 38, 9, 62, 70, 0),
    fossil("relic-dinosaur-embryo-egg", Tier.RARE, 10, Family.DINOSAUR_EMBRYO, 24, 13, 72, 88, 0),
    fossil("relic-archaeopteryx-slab", Tier.RARE, 10, Family.ARCHAEOPTERYX, 32, 14, 76, 92, 0),
  )

  private val itemById = items.associateBy(FossilCatalogItem::id)
  private val legacyAliases = mapOf(
    "relic-fossil-choir" to "relic-dactylioceras-ammonite",
    "relic-lunar-ash" to "relic-domal-stromatolite",
    "relic-abyssal-glass" to "relic-dinosaur-embryo-egg",
  )

  val totalSelectionWeight: Int = items.sumOf(FossilCatalogItem::selectionWeight)

  init {
    require(items.map(FossilCatalogItem::id).toSet().size == items.size)
    require(totalSelectionWeight > 0)
    require(legacyAliases.keys.none(itemById::containsKey))
    require(legacyAliases.values.all(itemById::containsKey))
  }

  fun find(id: String): FossilCatalogItem? = itemById[id] ?: legacyAliases[id]?.let(itemById::get)

  fun selectByTicket(ticket: Int): FossilCatalogItem {
    require(ticket in 0 until totalSelectionWeight)
    var remaining = ticket
    for (item in items) {
      if (remaining < item.selectionWeight) return item
      remaining -= item.selectionWeight
    }
    error("Catalog ticket escaped the configured weight total")
  }

  fun selectFor(localDate: LocalDate, localSecret: ByteArray): FossilCatalogItem {
    require(localSecret.size == LOCAL_SECRET_BYTES)
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(localSecret, "HmacSHA256"))
    val entropy = mac.doFinal(
      DAILY_FOSSIL_DOMAIN + localDate.toString().toByteArray(UTF_8),
    )
    val unsignedWord = ((entropy[0].toLong() and BYTE_MASK) shl 24) or
      ((entropy[1].toLong() and BYTE_MASK) shl 16) or
      ((entropy[2].toLong() and BYTE_MASK) shl 8) or
      (entropy[3].toLong() and BYTE_MASK)
    return selectByTicket((unsignedWord % totalSelectionWeight).toInt())
  }

  fun specimenId(localDate: LocalDate, item: FossilCatalogItem): String =
    "excavation-${localDate.toString().replace("-", "")}-${item.id}"

  fun itemForSpecimenId(specimenId: String): FossilCatalogItem? =
    items.firstOrNull { item -> specimenId.endsWith("-${item.id}") }
      ?: legacyAliases.entries
        .firstOrNull { alias -> specimenId.endsWith("-${alias.key}") }
        ?.value
        ?.let(itemById::get)

  private fun fossil(
    id: String,
    tier: Tier,
    selectionWeight: Int,
    family: Family,
    hueDegrees: Int,
    strataCount: Int,
    inclusionDensityPercent: Int,
    reliefPercent: Int,
    rotationDegrees: Int,
  ): FossilCatalogItem = FossilCatalogItem(
    id = id,
    tier = tier,
    selectionWeight = selectionWeight,
    family = family,
    visual = VisualParameters(
      hueDegrees = hueDegrees,
      strataCount = strataCount,
      inclusionDensityPercent = inclusionDensityPercent,
      reliefPercent = reliefPercent,
      rotationDegrees = rotationDegrees,
    ),
  )

  private const val LOCAL_SECRET_BYTES = 32
  private const val BYTE_MASK = 0xffL
  private val DAILY_FOSSIL_DOMAIN = "afterchime:daily-fossil:v2\u0000".toByteArray(UTF_8)
}
