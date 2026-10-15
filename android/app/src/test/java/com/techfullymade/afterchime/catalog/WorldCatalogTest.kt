package com.techfullymade.afterchime.catalog

import com.techfullymade.afterchime.render.World
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldCatalogTest {
  @Test
  fun `catalogue represents every stable world in declaration order`() {
    assertEquals(World.entries, WorldCatalog.entries.map(WorldCatalogEntry::world))
    assertEquals(World.entries.size, WorldCatalog.entries.map(WorldCatalogEntry::world).distinct().size)
  }

  @Test
  fun `fresh development state owns and selects only base world`() {
    assertEquals(
      DevelopmentWorldState(setOf(World.PRIMEVAL_STRATA), World.PRIMEVAL_STRATA),
      DevelopmentWorldState(),
    )
  }

  @Test
  fun `base selection cannot create another ownership state`() {
    val initial = DevelopmentWorldState()
    val (result, outcome) = initial.select(World.PRIMEVAL_STRATA)

    assertEquals(WorldSelectionResult.SELECTED, outcome)
    assertEquals(initial, result)
    assertEquals(setOf(World.PRIMEVAL_STRATA), result.ownedWorlds)
  }

  @Test
  fun `development ownership and reset remain base only`() {
    val owned = DevelopmentWorldState().ownForDevelopment(World.PRIMEVAL_STRATA)
    assertEquals(setOf(World.PRIMEVAL_STRATA), owned.ownedWorlds)
    assertEquals(DevelopmentWorldState(), owned.resetDevelopmentOwnership())
  }
}
