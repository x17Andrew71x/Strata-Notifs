package com.techfullymade.afterchime.gameplay

/**
 * Central tuning values for the daily excavation loop.
 *
 * Keep gameplay numbers here so feasibility changes do not require hunting through persistence,
 * bridge, or UI code.
 */
object GameBalance {
  const val ENERGY_PER_ELIGIBLE_NOTIFICATION = 1
  const val ENERGY_PER_TILE = 3
  const val EXCAVATION_GRID_COLUMNS = 5
  const val EXCAVATION_GRID_ROWS = 5
  const val EXCAVATION_TILE_COUNT = EXCAVATION_GRID_COLUMNS * EXCAVATION_GRID_ROWS
  const val EXCAVATION_TOTAL_ENERGY = EXCAVATION_TILE_COUNT * ENERGY_PER_TILE
  const val SOURCE_NOTIFICATION_COOLDOWN_MINUTES = 5L
  const val MILLIS_PER_MINUTE = 60_000L
  const val SOURCE_NOTIFICATION_COOLDOWN_MILLIS =
    SOURCE_NOTIFICATION_COOLDOWN_MINUTES * MILLIS_PER_MINUTE
  const val CATALOG_GENERATOR_VERSION = 2
}
