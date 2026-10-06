package com.techfullymade.afterchime.catalog

import com.techfullymade.afterchime.render.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
  fun `selection of an unowned world does not mutate state or grant ownership`() {
    val initial = DevelopmentWorldState()
    val (result, outcome) = initial.select(World.DEEP_SPACE)

    assertEquals(WorldSelectionResult.UNOWNED_WORLD, outcome)
    assertEquals(initial, result)
    assertSame(initial, result)
    assertEquals(setOf(World.PRIMEVAL_STRATA), result.ownedWorlds)
  }

  @Test
  fun `development ownership can be explicitly added and reset to base`() {
    val owned = DevelopmentWorldState().ownForDevelopment(World.DEEP_SPACE)
    assertEquals(setOf(World.PRIMEVAL_STRATA, World.DEEP_SPACE), owned.ownedWorlds)
    assertEquals(DevelopmentWorldState(), owned.resetDevelopmentOwnership())
  }
}
