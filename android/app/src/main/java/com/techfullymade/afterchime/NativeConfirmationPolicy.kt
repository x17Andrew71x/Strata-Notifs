package com.techfullymade.afterchime

internal enum class NativeConfirmationKind {
  ENABLE_ONLINE_FEATURES,
  ENABLE_PRODUCT_ANALYTICS,
  ENABLE_NOTIFICATION_AGGREGATES,
  COMBINE_SPECIMENS,
  CONTRIBUTE_EXHIBIT_ENERGY,
}

internal fun preferenceConfirmation(key: String, value: Boolean): NativeConfirmationKind? {
  if (!value) return null
  return when (key) {
    "onlineFeaturesEnabled" -> NativeConfirmationKind.ENABLE_ONLINE_FEATURES
    "productAnalyticsEnabled" -> NativeConfirmationKind.ENABLE_PRODUCT_ANALYTICS
    "notificationAggregateSharingEnabled" -> NativeConfirmationKind.ENABLE_NOTIFICATION_AGGREGATES
    else -> null
  }
}

internal fun actionConfirmation(type: String): NativeConfirmationKind? = when (type) {
  "museum.combine" -> NativeConfirmationKind.COMBINE_SPECIMENS
  "exhibits.contribute" -> NativeConfirmationKind.CONTRIBUTE_EXHIBIT_ENERGY
  else -> null
}
