package com.techfullymade.afterchime.gameplay

import com.techfullymade.afterchime.generation.Tier
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FossilCatalogTest {
  @Test
  fun `initial weights are individually tunable and total one hundred`() {
    assertEquals(
      listOf(
        Triple("relic-lunar-ash", Tier.COMMON, 60),
        Triple("relic-fossil-choir", Tier.UNCOMMON, 30),
        Triple("relic-abyssal-glass", Tier.RARE, 10),
      ),
      FossilCatalog.items.map { item -> Triple(item.id, item.tier, item.selectionWeight) },
    )
    assertEquals(100, FossilCatalog.totalSelectionWeight)
  }

  @Test
  fun `weighted ticket boundaries select the configured item`() {
    assertEquals("relic-lunar-ash", FossilCatalog.selectByTicket(0).id)
    assertEquals("relic-lunar-ash", FossilCatalog.selectByTicket(59).id)
    assertEquals("relic-fossil-choir", FossilCatalog.selectByTicket(60).id)
    assertEquals("relic-fossil-choir", FossilCatalog.selectByTicket(89).id)
    assertEquals("relic-abyssal-glass", FossilCatalog.selectByTicket(90).id)
    assertEquals("relic-abyssal-glass", FossilCatalog.selectByTicket(99).id)
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
