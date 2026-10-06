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
  val onboardingComplete: Boolean = false,
  val onlineFeaturesEnabled: Boolean = false,
  val productAnalyticsEnabled: Boolean = false,
  val notificationAggregateSharingEnabled: Boolean = false,
  val reduceMotionEnabled: Boolean = false,
  val highContrastEnabled: Boolean = false,
  val hapticsEnabled: Boolean = true,
)

/** DataStore-backed local preference boundary; callers must explicitly set every optional consent. */
class DataStoreUserPreferences(
  private val dataStore: DataStore<Preferences>,
) {
  constructor(context: Context) : this(context.afterchimeUserPreferencesDataStore)

  val values: Flow<UserPreferences> = dataStore.data.map { preferences ->
    UserPreferences(
      onboardingComplete = preferences[ONBOARDING_COMPLETE_KEY] ?: false,
      onlineFeaturesEnabled = preferences[ONLINE_FEATURES_ENABLED_KEY] ?: false,
      productAnalyticsEnabled = (preferences[PRODUCT_ANALYTICS_ENABLED_KEY] ?: false) && (preferences[ONLINE_FEATURES_ENABLED_KEY] ?: false),
      notificationAggregateSharingEnabled = (preferences[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] ?: false) && (preferences[ONLINE_FEATURES_ENABLED_KEY] ?: false),
      reduceMotionEnabled = preferences[REDUCE_MOTION_ENABLED_KEY] ?: false,
      highContrastEnabled = preferences[HIGH_CONTRAST_ENABLED_KEY] ?: false,
      hapticsEnabled = preferences[HAPTICS_ENABLED_KEY] ?: true,
    )
  }

  suspend fun update(transform: (UserPreferences) -> UserPreferences) {
    dataStore.edit { stored ->
      val updated = transform(
        UserPreferences(
          onboardingComplete = stored[ONBOARDING_COMPLETE_KEY] ?: false,
          onlineFeaturesEnabled = stored[ONLINE_FEATURES_ENABLED_KEY] ?: false,
          productAnalyticsEnabled = (stored[PRODUCT_ANALYTICS_ENABLED_KEY] ?: false) && (stored[ONLINE_FEATURES_ENABLED_KEY] ?: false),
          notificationAggregateSharingEnabled = (stored[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] ?: false) && (stored[ONLINE_FEATURES_ENABLED_KEY] ?: false),
          reduceMotionEnabled = stored[REDUCE_MOTION_ENABLED_KEY] ?: false,
          highContrastEnabled = stored[HIGH_CONTRAST_ENABLED_KEY] ?: false,
          hapticsEnabled = stored[HAPTICS_ENABLED_KEY] ?: true,
        ),
      )
      stored[ONBOARDING_COMPLETE_KEY] = updated.onboardingComplete
      stored[ONLINE_FEATURES_ENABLED_KEY] = updated.onlineFeaturesEnabled
      stored[PRODUCT_ANALYTICS_ENABLED_KEY] = updated.onlineFeaturesEnabled && updated.productAnalyticsEnabled
      stored[NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY] = updated.onlineFeaturesEnabled && updated.notificationAggregateSharingEnabled
      stored[REDUCE_MOTION_ENABLED_KEY] = updated.reduceMotionEnabled
      stored[HIGH_CONTRAST_ENABLED_KEY] = updated.highContrastEnabled
      stored[HAPTICS_ENABLED_KEY] = updated.hapticsEnabled
    }
  }

  private companion object {
    val ONBOARDING_COMPLETE_KEY = booleanPreferencesKey("onboarding_complete")
    val ONLINE_FEATURES_ENABLED_KEY = booleanPreferencesKey("online_features_enabled")
    val PRODUCT_ANALYTICS_ENABLED_KEY = booleanPreferencesKey("product_analytics_enabled")
    val NOTIFICATION_AGGREGATE_SHARING_ENABLED_KEY = booleanPreferencesKey("notification_aggregate_sharing_enabled")
    val REDUCE_MOTION_ENABLED_KEY = booleanPreferencesKey("reduce_motion_enabled")
    val HIGH_CONTRAST_ENABLED_KEY = booleanPreferencesKey("high_contrast_enabled")
    val HAPTICS_ENABLED_KEY = booleanPreferencesKey("haptics_enabled")
  }
}

private val Context.afterchimeUserPreferencesDataStore by preferencesDataStore(name = "afterchime_user_preferences")
