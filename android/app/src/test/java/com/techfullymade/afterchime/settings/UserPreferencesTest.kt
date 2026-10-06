package com.techfullymade.afterchime.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

class UserPreferencesTest {
  private lateinit var scope: CoroutineScope
  private lateinit var directory: java.io.File
  private lateinit var dataStore: DataStore<Preferences>

  @Before
  fun setUp() {
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    directory = Files.createTempDirectory("afterchime-user-preferences").toFile()
    dataStore = PreferenceDataStoreFactory.create(
      scope = scope,
      produceFile = { File(directory, "user-preferences.preferences_pb") },
    )
  }

  @After
  fun tearDown() {
    scope.cancel()
    directory.deleteRecursively()
  }

  @Test
  fun `new preferences keep onboarding incomplete optional consent disabled and haptics enabled`() = runBlocking {
    assertEquals(UserPreferences(), DataStoreUserPreferences(dataStore).values.first())
  }

  @Test
  fun `updates persist onboarding consent and presentation settings`() = runBlocking {
    val preferences = DataStoreUserPreferences(dataStore)
    preferences.update {
      it.copy(onboardingComplete = true, onlineFeaturesEnabled = true, productAnalyticsEnabled = true,
        notificationAggregateSharingEnabled = true, reduceMotionEnabled = true, highContrastEnabled = true, hapticsEnabled = false)
    }
    assertEquals(UserPreferences(true, true, true, true, true, true, false), DataStoreUserPreferences(dataStore).values.first())
  }

  @Test
  fun `dependent consent cannot be enabled without online features`() = runBlocking {
    DataStoreUserPreferences(dataStore).update {
      it.copy(productAnalyticsEnabled = true, notificationAggregateSharingEnabled = true)
    }
    assertEquals(UserPreferences(), DataStoreUserPreferences(dataStore).values.first())
  }
}
