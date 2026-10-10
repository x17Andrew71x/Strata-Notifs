package com.techfullymade.afterchime.gameplay

import com.techfullymade.afterchime.generation.Tier
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FossilCatalogTest {
  @Test
  fun `approved catalogue has ten common five uncommon and two rare fossils`() {
    assertEquals(17, FossilCatalog.items.size)
    assertEquals(10, FossilCatalog.items.count { item -> item.tier == Tier.COMMON })
    assertEquals(5, FossilCatalog.items.count { item -> item.tier == Tier.UNCOMMON })
    assertEquals(2, FossilCatalog.items.count { item -> item.tier == Tier.RARE })
    assertEquals(17, FossilCatalog.items.map { item -> item.id }.toSet().size)
  }

  @Test
  fun `tier weights total eighty six twelve and two percent`() {
    assertEquals(1_000, FossilCatalog.totalSelectionWeight)
    assertEquals(
      mapOf(Tier.COMMON to 860, Tier.UNCOMMON to 120, Tier.RARE to 20),
      FossilCatalog.items.groupBy { item -> item.tier }
        .mapValues { (_, items) -> items.sumOf { item -> item.selectionWeight } },
    )
    assertEquals(setOf(86), FossilCatalog.items.filter { item -> item.tier == Tier.COMMON }.map { item -> item.selectionWeight }.toSet())
    assertEquals(setOf(24), FossilCatalog.items.filter { item -> item.tier == Tier.UNCOMMON }.map { item -> item.selectionWeight }.toSet())
    assertEquals(setOf(10), FossilCatalog.items.filter { item -> item.tier == Tier.RARE }.map { item -> item.selectionWeight }.toSet())
  }

  @Test
  fun `weighted ticket boundaries select the configured tiers`() {
    assertEquals("relic-dactylioceras-ammonite", FossilCatalog.selectByTicket(0).id)
    assertEquals("relic-echinocorys-echinoid", FossilCatalog.selectByTicket(859).id)
    assertEquals("relic-articulated-trilobite", FossilCatalog.selectByTicket(860).id)
    assertEquals("relic-insect-amber", FossilCatalog.selectByTicket(979).id)
    assertEquals("relic-dinosaur-embryo-egg", FossilCatalog.selectByTicket(980).id)
    assertEquals("relic-archaeopteryx-slab", FossilCatalog.selectByTicket(999).id)
  }

  @Test
  fun `retired fictional ids resolve to canonical fossils for unfinished and museum records`() {
    assertEquals(
      "relic-dactylioceras-ammonite",
      FossilCatalog.find("relic-fossil-choir")?.id,
    )
    assertEquals(
      "relic-domal-stromatolite",
      FossilCatalog.itemForSpecimenId("excavation-20261009-relic-lunar-ash")?.id,
    )
    assertEquals(
      "relic-dinosaur-embryo-egg",
      FossilCatalog.itemForSpecimenId("excavation-20261009-relic-abyssal-glass")?.id,
    )
  }

  @Test
  fun `daily selection is stable for a date and secret but date bound`() {
    val secret = ByteArray(32) { index -> index.toByte() }
    val first = FossilCatalog.selectFor(LocalDate.parse("2026-10-09"), secret)
    val repeat = FossilCatalog.selectFor(LocalDate.parse("2026-10-09"), secret)
    val later = FossilCatalog.selectFor(LocalDate.parse("2026-10-10"), secret)

    assertEquals(first, repeat)
    assertNotEquals(
      FossilCatalog.specimenId(LocalDate.parse("2026-10-09"), first),
      FossilCatalog.specimenId(LocalDate.parse("2026-10-10"), later),
    )
  }
}
