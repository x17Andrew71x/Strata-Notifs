package com.techfullymade.afterchime.data.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.catalog.WorldSelectionResult
import com.techfullymade.afterchime.render.World
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore persistence for local development ownership and cosmetic world selection only. */
class DataStoreWorldCatalog(
  private val dataStore: DataStore<Preferences>,
) {
  val state: Flow<DevelopmentWorldState> = dataStore.data.map(::decode)

  suspend fun select(world: World): WorldSelectionResult {
    var result = WorldSelectionResult.UNOWNED_WORLD
    dataStore.edit { preferences ->
      val current = decode(preferences)
      if (world in current.ownedWorlds) {
        result = WorldSelectionResult.SELECTED
        write(preferences, current.copy(selectedWorld = world))
      }
    }
    return result
  }

  suspend fun ownForDevelopment(world: World) {
    dataStore.edit { preferences ->
      val current = decode(preferences)
      write(preferences, current.copy(ownedWorlds = current.ownedWorlds + world))
    }
  }

  suspend fun resetDevelopmentOwnership() {
    dataStore.edit { preferences -> write(preferences, DevelopmentWorldState()) }
  }

  private fun write(preferences: MutablePreferences, state: DevelopmentWorldState) {
    preferences[OWNED_WORLDS] = state.ownedWorlds.mapTo(mutableSetOf(), World::name)
    preferences[SELECTED_WORLD] = state.selectedWorld.name
  }

  private fun decode(preferences: Preferences): DevelopmentWorldState = runCatching {
    val storedOwned = preferences[OWNED_WORLDS]
    val storedSelected = preferences[SELECTED_WORLD]
    if (storedOwned == null && storedSelected == null) return@runCatching DevelopmentWorldState()
    if (storedOwned == null || storedSelected == null) return@runCatching DevelopmentWorldState()

    val owned = storedOwned.mapTo(mutableSetOf()) { World.valueOf(it) }
    val selected = World.valueOf(storedSelected)
    DevelopmentWorldState(ownedWorlds = owned, selectedWorld = selected)
  }.getOrDefault(DevelopmentWorldState())

  private companion object {
    val OWNED_WORLDS = stringSetPreferencesKey("development_owned_worlds")
    val SELECTED_WORLD = stringPreferencesKey("development_selected_world")
  }
}
