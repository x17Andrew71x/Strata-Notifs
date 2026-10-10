package com.techfullymade.afterchime.gameplay

import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FossilCatalogStoreTest {
  @Test
  fun `new hosted catalogue entries persist and participate in future local daily draws`() {
    val persistence = MemoryFossilCatalogPersistence()
    val store = FossilCatalogStore(persistence)
    val extra = futureFossil()
    val update = FossilCatalogManifest(
      revision = FossilCatalog.BUNDLED_REVISION + 1,
      items = FossilCatalog.items + extra,
    )

    assertTrue(store.apply(update.toJson()))
    assertEquals(extra, store.find(extra.id))
    assertEquals(18, store.items.size)
    assertNotNull(persistence.value)

    val reloaded = FossilCatalogStore(persistence)
    assertEquals(update.revision, reloaded.revision)
    assertEquals(extra, reloaded.find(extra.id))
    assertEquals(
      extra,
      reloaded.selectFor(LocalDate.parse("2026-10-11"), ByteArray(32) { it.toByte() }),
    )
  }

  @Test
  fun `catalogue updates reject rollback removal and identity mutation`() {
    val persistence = MemoryFossilCatalogPersistence()
    val store = FossilCatalogStore(persistence)
    val extra = futureFossil()
    assertTrue(
      store.apply(
        FossilCatalogManifest(
          revision = FossilCatalog.BUNDLED_REVISION + 1,
          items = FossilCatalog.items + extra,
        ).toJson(),
      ),
    )

    assertFalse(
      store.apply(
        FossilCatalogManifest(
          revision = FossilCatalog.BUNDLED_REVISION,
          items = FossilCatalog.items,
        ).toJson(),
      ),
    )
    assertFalse(
      store.apply(
        FossilCatalogManifest(
          revision = FossilCatalog.BUNDLED_REVISION + 2,
          items = FossilCatalog.items,
        ).toJson(),
      ),
    )
    assertFalse(
      store.apply(
        FossilCatalogManifest(
          revision = FossilCatalog.BUNDLED_REVISION + 2,
          items = FossilCatalog.items + extra.copy(tier = Tier.RARE),
        ).toJson(),
      ),
    )
    assertEquals(extra, store.find(extra.id))
  }

  @Test
  fun `catalogue updates reject out of range hosted weights`() {
    val store = FossilCatalogStore(MemoryFossilCatalogPersistence())
    val update = FossilCatalogManifest(
      revision = FossilCatalog.BUNDLED_REVISION + 1,
      items = FossilCatalog.items + futureFossil(),
    ).toJson()
    update.getJSONArray("items")
      .getJSONObject(FossilCatalog.items.size)
      .put("selectionWeight", Int.MAX_VALUE)

    assertFalse(store.apply(update))
    assertEquals(FossilCatalog.BUNDLED_REVISION, store.revision)
  }

  @Test
  fun `invalid persisted data fails closed to the bundled catalogue`() {
    val store = FossilCatalogStore(MemoryFossilCatalogPersistence("{broken"))

    assertEquals(FossilCatalog.BUNDLED_REVISION, store.revision)
    assertEquals(FossilCatalog.items, store.items)
  }

  private fun futureFossil() = FossilCatalogItem(
    id = "relic-future-trace-fossil",
    tier = Tier.UNCOMMON,
    selectionWeight = 1_000,
    family = Family.TRACE_PLATE,
    visual = VisualParameters(
      hueDegrees = 34,
      strataCount = 8,
      inclusionDensityPercent = 50,
      reliefPercent = 70,
      rotationDegrees = 0,
    ),
  )
}

private class MemoryFossilCatalogPersistence(
  initial: String? = null,
) : FossilCatalogPersistence {
  var value: String? = initial
    private set

  override fun read(): String? = value

  override fun write(value: String): Boolean {
    this.value = value
    return true
  }
}
