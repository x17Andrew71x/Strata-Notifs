package com.techfullymade.afterchime.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing

@Composable
fun MoreScreen(
  preferences: UserPreferences,
  onEnableNotificationAccess: () -> Unit,
  onPreferencesChanged: (UserPreferences) -> Unit,
  onOpenWorlds: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("root-content-more")
      .verticalScroll(rememberScrollState())
      .padding(horizontal = AfterchimeSpacing.screenHorizontal, vertical = AfterchimeSpacing.screenVertical),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    TextButton(onClick = onOpenWorlds, modifier = Modifier.testTag("more-worlds")) {
      Text(stringResource(R.string.worlds_open))
    }
    TextButton(onClick = onEnableNotificationAccess, modifier = Modifier.testTag("notification-access")) {
      Text(stringResource(R.string.enable_notification_access))
    }
    PreferenceSwitch("online-features", R.string.online_features_label, preferences.onlineFeaturesEnabled, true) {
      onPreferencesChanged(preferences.copy(onlineFeaturesEnabled = it, productAnalyticsEnabled = if (it) preferences.productAnalyticsEnabled else false, notificationAggregateSharingEnabled = if (it) preferences.notificationAggregateSharingEnabled else false))
    }
    PreferenceSwitch("product-analytics", R.string.analytics_label, preferences.productAnalyticsEnabled, preferences.onlineFeaturesEnabled) {
      onPreferencesChanged(preferences.copy(productAnalyticsEnabled = it))
    }
    PreferenceSwitch("aggregate-sharing", R.string.aggregate_sharing_label, preferences.notificationAggregateSharingEnabled, preferences.onlineFeaturesEnabled) {
      onPreferencesChanged(preferences.copy(notificationAggregateSharingEnabled = it))
    }
    PreferenceSwitch("reduce-motion", R.string.reduce_motion_label, preferences.reduceMotionEnabled) {
      onPreferencesChanged(preferences.copy(reduceMotionEnabled = it))
    }
    PreferenceSwitch("high-contrast", R.string.high_contrast_label, preferences.highContrastEnabled) {
      onPreferencesChanged(preferences.copy(highContrastEnabled = it))
    }
    PreferenceSwitch("haptics", R.string.haptics_label, preferences.hapticsEnabled) {
      onPreferencesChanged(preferences.copy(hapticsEnabled = it))
    }
  }
}

@Composable
private fun PreferenceSwitch(tag: String, labelId: Int, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
  val label = stringResource(labelId)
  Row(
    modifier = Modifier.fillMaxWidth().testTag("setting-row-$tag").padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      enabled = enabled,
      modifier = Modifier.testTag("setting-$tag").semantics { contentDescription = label },
    )
  }
}
