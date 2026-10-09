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
   * Initial deliberately generous tuning: 60% common, 30% medium, 10% rare.
   * Every future item owns its own weight; review the complete total whenever the list changes.
   */
  val items: List<FossilCatalogItem> = listOf(
    FossilCatalogItem(
      id = "relic-lunar-ash",
      tier = Tier.COMMON,
      selectionWeight = 60,
      family = Family.METEORITE_FRAGMENT,
      visual = VisualParameters(
        hueDegrees = 35,
        strataCount = 6,
        inclusionDensityPercent = 24,
        reliefPercent = 42,
        rotationDegrees = 18,
      ),
    ),
    FossilCatalogItem(
      id = "relic-fossil-choir",
      tier = Tier.UNCOMMON,
      selectionWeight = 30,
      family = Family.AMMONITE,
      visual = VisualParameters(
        hueDegrees = 34,
        strataCount = 9,
        inclusionDensityPercent = 52,
        reliefPercent = 68,
        rotationDegrees = 8,
      ),
    ),
    FossilCatalogItem(
      id = "relic-abyssal-glass",
      tier = Tier.RARE,
      selectionWeight = 10,
      family = Family.AMMONITE,
      visual = VisualParameters(
        hueDegrees = 198,
        strataCount = 12,
        inclusionDensityPercent = 78,
        reliefPercent = 88,
        rotationDegrees = 23,
      ),
    ),
  )

  val totalSelectionWeight: Int = items.sumOf(FossilCatalogItem::selectionWeight)

  init {
    require(items.map(FossilCatalogItem::id).toSet().size == items.size)
    require(totalSelectionWeight > 0)
  }

  fun find(id: String): FossilCatalogItem? = items.firstOrNull { item -> item.id == id }

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

  private const val LOCAL_SECRET_BYTES = 32
  private const val BYTE_MASK = 0xffL
  private val DAILY_FOSSIL_DOMAIN = "afterchime:daily-fossil:v1\u0000".toByteArray(UTF_8)
}
