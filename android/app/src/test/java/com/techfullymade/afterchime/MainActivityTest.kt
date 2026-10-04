package com.techfullymade.afterchime

import android.provider.Settings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
  @Test
  fun `hosts a hardened WebView instead of the native onboarding screen`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
    val webView = content.getChildAt(0) as WebView
    assertFalse(webView.settings.allowFileAccess)
    assertFalse(webView.settings.allowContentAccess)
    assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
    assertTrue(webView.settings.domStorageEnabled)
    assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
    assertEquals(
      "https://appassets.androidplatform.net/assets/web/index.html",
      shadowOf(webView).lastLoadedUrl,
    )
  }

  @Test
  fun `notification settings action opens Android listener settings`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertTrue(activity.openNotificationAccessSettings())
    val startedIntent = shadowOf(activity).nextStartedActivity
    assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, startedIntent?.action)
  }

  @Test
  fun `approved onboarding copy stays plain game language`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val resources = activity.resources
    assertEquals("Begin your collection", resources.getString(R.string.today_title))
    assertEquals(
      "Turn the rhythm of your day into one-of-a-kind specimens.",
      resources.getString(R.string.today_subtitle),
    )
    assertEquals("Start collecting", resources.getString(R.string.enable_notification_access))
    val bundledPage = activity.assets.open("web/index.html").bufferedReader().use { it.readText() }
    assertFalse(bundledPage.contains("strata", ignoreCase = true))
  }
}
