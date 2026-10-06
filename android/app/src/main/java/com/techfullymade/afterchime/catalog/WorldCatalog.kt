package com.techfullymade.afterchime.catalog

import com.techfullymade.afterchime.render.World

/** One locally available presentation world, identified by the existing render enum. */
data class WorldCatalogEntry(
  val world: World,
)

/** Offline catalogue for the worlds already declared by the renderer. */
object WorldCatalog {
  val entries: List<WorldCatalogEntry> = World.entries.map(::WorldCatalogEntry)
}

/** Local development-only ownership and cosmetic selection; not a purchase entitlement. */
data class DevelopmentWorldState(
  val ownedWorlds: Set<World> = setOf(World.PRIMEVAL_STRATA),
  val selectedWorld: World = World.PRIMEVAL_STRATA,
) {
  init {
    require(World.PRIMEVAL_STRATA in ownedWorlds)
    require(selectedWorld in ownedWorlds)
  }
}

/** Result of a local selection request. Rejection never changes ownership or selection. */
enum class WorldSelectionResult {
  SELECTED,
  UNOWNED_WORLD,
}

/** Explicit local-only state operations used by development and future presentation code. */
fun DevelopmentWorldState.select(world: World): Pair<DevelopmentWorldState, WorldSelectionResult> =
  if (world in ownedWorlds) {
    copy(selectedWorld = world) to WorldSelectionResult.SELECTED
  } else {
    this to WorldSelectionResult.UNOWNED_WORLD
  }

fun DevelopmentWorldState.ownForDevelopment(world: World): DevelopmentWorldState =
  copy(ownedWorlds = ownedWorlds + world)

fun DevelopmentWorldState.resetDevelopmentOwnership(): DevelopmentWorldState =
  DevelopmentWorldState()
