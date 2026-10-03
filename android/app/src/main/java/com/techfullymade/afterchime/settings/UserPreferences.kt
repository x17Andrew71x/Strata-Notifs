package com.techfullymade.afterchime.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Device-local controls. All online collection and optional consent starts disabled. */
data class UserPreferences(
  val onlineFeaturesEnabled: Boolean = false,
  val productAnalyticsEnabled: Boolean = false,
  val notificationAggregateSharingEnabled: Boolean = false,
  val reduceMotionEnabled: Boolean = false,
  val hapticsEnabled: Boolean = true,
)

/** DataStore-backed local preference boundary; callers must explicitly set every optional consent. */
class DataStoreUserPreferences(
  private val dataStore: DataStore<Preferences>,
) {
  constructor(context: Context) : this(context.afterchimeUserPreferencesDataStore)

  val values: Flow<UserPreferences> = dataStore.data.map { preferences ->
    UserPreferences(
      onlineFeaturesEnabled = preferences[ONLINE_FEATURES_ENABLED_KEY] ?: false,
      productAnalyticsEnabled = preferences[PRODUCT_ANALYTICS_ENABLED_KEY] ?: false,
      notificationAggregateSharingEnabled = preferences[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] ?: false,
      reduceMotionEnabled = preferences[REDUCE_MOTION_ENABLED_KEY] ?: false,
      hapticsEnabled = preferences[HAPTICS_ENABLED_KEY] ?: true,
    )
  }

  suspend fun update(transform: (UserPreferences) -> UserPreferences) {
    dataStore.edit { stored ->
      val updated = transform(
        UserPreferences(
          onlineFeaturesEnabled = stored[ONLINE_FEATURES_ENABLED_KEY] ?: false,
          productAnalyticsEnabled = stored[PRODUCT_ANALYTICS_ENABLED_KEY] ?: false,
          notificationAggregateSharingEnabled = stored[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] ?: false,
          reduceMotionEnabled = stored[REDUCE_MOTION_ENABLED_KEY] ?: false,
          hapticsEnabled = stored[HAPTICS_ENABLED_KEY] ?: true,
        ),
      )
      stored[ONLINE_FEATURES_ENABLED_KEY] = updated.onlineFeaturesEnabled
      stored[PRODUCT_ANALYTICS_ENABLED_KEY] = updated.productAnalyticsEnabled
      stored[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] = updated.notificationAggregateSharingEnabled
      stored[REDUCE_MOTION_ENABLED_KEY] = updated.reduceMotionEnabled
      stored[HAPTICS_ENABLED_KEY] = updated.hapticsEnabled
    }
  }

  private companion object {
    val ONLINE_FEATURES_ENABLED_KEY = booleanPreferencesKey("online_features_enabled")
    val PRODUCT_ANALYTICS_ENABLED_KEY = booleanPreferencesKey("product_analytics_enabled")
    val NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY = booleanPreferencesKey("notification_aggregate_sharing_enabled")
    val REDUCE_MOTION_ENABLED_KEY = booleanPreferencesKey("reduce_motion_enabled")
    val HAPTICS_ENABLED_KEY = booleanPreferencesKey("haptics_enabled")
  }
}

private val Context.afterchimeUserPreferencesDataStore by preferencesDataStore(name = "afterchime_user_preferences")
