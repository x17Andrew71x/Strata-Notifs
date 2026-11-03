package com.techfullymade.afterchime

import com.techfullymade.afterchime.settings.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeConfirmationPolicyTest {
  @Test
  fun `enabling consent and irreversible mutations require a native confirmation`() {
    assertEquals(
      NativeConfirmationKind.ENABLE_ONLINE_FEATURES,
      preferenceConfirmation("onlineFeaturesEnabled", true),
    )
    assertEquals(
      NativeConfirmationKind.ENABLE_PRODUCT_ANALYTICS,
      preferenceConfirmation("productAnalyticsEnabled", true),
    )
    assertEquals(
      NativeConfirmationKind.ENABLE_NOTIFICATION_AGGREGATES,
      preferenceConfirmation("notificationAggregateSharingEnabled", true),
    )
    assertEquals(NativeConfirmationKind.COMBINE_SPECIMENS, actionConfirmation("museum.combine"))
    assertEquals(
      NativeConfirmationKind.CONTRIBUTE_EXHIBIT_ENERGY,
      actionConfirmation("exhibits.contribute"),
    )
  }

  @Test
  fun `disabling consent and ordinary presentation actions do not require confirmation`() {
    assertNull(preferenceConfirmation("onlineFeaturesEnabled", false))
    assertNull(preferenceConfirmation("reduceMotionEnabled", true))
    assertNull(actionConfirmation("formation.reveal"))
    assertNull(actionConfirmation("museum.share"))
  }

  @Test
  fun `exhibit confirmation copy binds the exact permanent amount`() {
    assertEquals(
      "Contribute 18 energy?" to "This permanently sends exactly 18 energy to the Exhibit.",
      exhibitContributionConfirmationCopy(18),
    )
  }

  @Test
  fun `one confirmed online preference atomically enables or disables all online choices`() {
    val enabled = UserPreferences().withPreference("onlineFeaturesEnabled", true)
    assertTrue(enabled.onlineFeaturesEnabled)
    assertTrue(enabled.productAnalyticsEnabled)
    assertTrue(enabled.notificationAggregateSharingEnabled)

    val disabled = enabled.withPreference("onlineFeaturesEnabled", false)
    assertFalse(disabled.onlineFeaturesEnabled)
    assertFalse(disabled.productAnalyticsEnabled)
    assertFalse(disabled.notificationAggregateSharingEnabled)
  }
}
