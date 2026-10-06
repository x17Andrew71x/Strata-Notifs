package com.techfullymade.afterchime.data.catalog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.catalog.WorldSelectionResult
import com.techfullymade.afterchime.render.World
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DataStoreWorldCatalogTest {
  private lateinit var scope: CoroutineScope
  private lateinit var directory: File
  private lateinit var dataStore: DataStore<Preferences>

  @Before
  fun setUp() {
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    directory = Files.createTempDirectory("afterchime-world-catalog").toFile()
    dataStore = PreferenceDataStoreFactory.create(
      scope = scope,
      produceFile = { File(directory, "world-catalog.preferences_pb") },
    )
  }

  @After
  fun tearDown() {
    scope.cancel()
    directory.deleteRecursively()
  }

  @Test
  fun `fresh state owns and selects only the base world`() = runBlocking {
    assertEquals(DevelopmentWorldState(), DataStoreWorldCatalog(dataStore).state.first())
  }

  @Test
  fun `unowned selection is rejected without changing persisted state`() = runBlocking {
    val catalog = DataStoreWorldCatalog(dataStore)
    val before = catalog.state.first()

    assertEquals(WorldSelectionResult.UNOWNED_WORLD, catalog.select(World.DEEP_SPACE))
    assertEquals(before, DataStoreWorldCatalog(dataStore).state.first())
  }

  @Test
  fun `local development ownership and selection survive a new repository instance`() = runBlocking {
    val catalog = DataStoreWorldCatalog(dataStore)
    catalog.ownForDevelopment(World.DEEP_SPACE)
    assertEquals(WorldSelectionResult.SELECTED, catalog.select(World.DEEP_SPACE))

    assertEquals(
      DevelopmentWorldState(setOf(World.PRIMEVAL_STRATA, World.DEEP_SPACE), World.DEEP_SPACE),
      DataStoreWorldCatalog(dataStore).state.first(),
    )
  }

  @Test
  fun `reset restores base-only ownership and selection`() = runBlocking {
    val catalog = DataStoreWorldCatalog(dataStore)
    catalog.ownForDevelopment(World.DEEP_SPACE)
    catalog.select(World.DEEP_SPACE)
    catalog.resetDevelopmentOwnership()

    assertEquals(DevelopmentWorldState(), DataStoreWorldCatalog(dataStore).state.first())
  }

  @Test
  fun `unknown malformed and unowned selection values recover to safe defaults`() = runBlocking {
    val ownedKey = stringSetPreferencesKey("development_owned_worlds")
    val selectedKey = stringPreferencesKey("development_selected_world")
    val repository = DataStoreWorldCatalog(dataStore)

    dataStore.edit { preferences ->
      preferences[ownedKey] = setOf(World.PRIMEVAL_STRATA.name, "NOT_A_WORLD")
      preferences[selectedKey] = World.DEEP_SPACE.name
    }
    assertEquals(DevelopmentWorldState(), repository.state.first())

    dataStore.edit { preferences ->
      preferences[ownedKey] = setOf(World.PRIMEVAL_STRATA.name)
      preferences[selectedKey] = " malformed "
    }
    assertEquals(DevelopmentWorldState(), repository.state.first())

    dataStore.edit { preferences ->
      preferences[ownedKey] = setOf(World.PRIMEVAL_STRATA.name)
      preferences[selectedKey] = World.DEEP_SPACE.name
    }
    assertEquals(DevelopmentWorldState(), repository.state.first())
  }
}
