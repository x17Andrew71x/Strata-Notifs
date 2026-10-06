package com.techfullymade.afterchime

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
  @Test
  fun `initialises a hardened WebView shell and starts from the configured shell or bundled disabled fallback`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val webView = activity.shellForTesting()
    assertFalse(webView.settings.allowFileAccess)
    assertFalse(webView.settings.allowContentAccess)
    assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
    assertTrue(webView.settings.domStorageEnabled)
    assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
    val configuredShell = Uri.parse(BuildConfig.SHELL_URL)
    val expectedEntry = if (
      configuredShell.host == "shell-disabled.invalid" ||
        configuredShell.host == "production-disabled.invalid"
    ) {
      LOCAL_ENTRY_URL
    } else {
      BuildConfig.SHELL_URL
    }
    assertEquals(expectedEntry, shadowOf(webView).lastLoadedUrl)
  }

  @Test
  fun `blocks off-origin requests and permits only configured shell origins`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val webView = activity.shellForTesting()

    val blocked = webView.webViewClient.shouldInterceptRequest(
      webView,
      request("https://example.invalid/private.js"),
    )
    assertEquals(403, blocked?.statusCode)
    assertNull(
      webView.webViewClient.shouldInterceptRequest(webView, request(BuildConfig.SHELL_URL)),
    )
    assertTrue(isAllowedShellUri(Uri.parse(LOCAL_ENTRY_URL), Uri.parse(BuildConfig.SHELL_URL)))
    assertFalse(
      isAllowedShellUri(Uri.parse("https://example.invalid/"), Uri.parse(BuildConfig.SHELL_URL)),
    )
  }

  @Test
  fun `remote main-frame HTTP errors trigger the bundled fallback policy`() {
    assertTrue(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 503, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = false, statusCode = 503, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 399, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 503, usingLocalShell = true))
  }

  @Test
  fun `native bridge parser rejects malformed unknown and expanded messages`() {
    assertNull(parseBridgeRequest(null))
    assertNull(parseBridgeRequest("{"))
    assertNull(parseBridgeRequest("""{"version":1,"id":"req","type":"notification.read"}"""))
    assertNull(
      parseBridgeRequest(
        """{"version":1,"id":"req","type":"capabilities.get","body":"private"}""",
      ),
    )
    assertEquals(
      BridgeRequest("req_1", "capabilities.get"),
      parseBridgeRequest("""{"version":1,"id":"req_1","type":"capabilities.get"}"""),
    )
    assertEquals(
      BridgeRequest("req_2", "notificationAccess.openAppDetails"),
      parseBridgeRequest("""{"version":1,"id":"req_2","type":"notificationAccess.openAppDetails"}"""),
    )
  }

  @Test
  fun `notification settings action opens this listeners app and type filters`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertTrue(activity.openNotificationAccessSettings())
    val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, startedIntent?.action)
    assertEquals(
      ComponentName(activity, StrataNotificationListenerService::class.java).flattenToString(),
      startedIntent?.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
    )
  }

  @Test
  @Suppress("DEPRECATION")
  fun `Android 12 listener starts closed and permanently disables sensitive notification types`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val serviceInfo = activity.packageManager.getServiceInfo(
      ComponentName(activity, StrataNotificationListenerService::class.java),
      PackageManager.GET_META_DATA,
    )

    assertEquals(31, activity.applicationInfo.minSdkVersion)
    assertEquals(
      "",
      serviceInfo.metaData.getString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES),
    )
    val disabledTypes =
      serviceInfo.metaData
        .getString(NotificationListenerService.META_DATA_DISABLED_FILTER_TYPES)
        ?.split(",")
        ?.map(String::toInt)
        ?.toSet()
    assertEquals(
      setOf(
        NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS,
        NotificationListenerService.FLAG_FILTER_TYPE_SILENT,
        NotificationListenerService.FLAG_FILTER_TYPE_ONGOING,
      ),
      disabledTypes,
    )
    assertFalse(
      disabledTypes.orEmpty().contains(NotificationListenerService.FLAG_FILTER_TYPE_ALERTING),
    )
  }

  @Test
  fun `restricted setting recovery opens this apps info page`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertTrue(activity.openAppDetailsSettings())
    val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, startedIntent?.action)
    assertEquals("package:${activity.packageName}", startedIntent?.data?.toString())
  }

  @Test
  fun `capability response advertises the app info recovery action`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val response = activity.capabilitiesResponse("req_capabilities")
    assertEquals("capabilities.state", response.getString("type"))
    assertTrue(response.getBoolean("appDetailsAction"))
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
    val bundledScript = activity.assets.open("web/assets/app.js").bufferedReader().use { it.readText() }
    assertTrue(bundledPage.contains("Android only offers one broad notification-access switch"))
    assertTrue(bundledPage.contains("fresh installs start with no notification types selected"))
    assertTrue(bundledPage.contains("financial, password, authenticator, and VPN apps"))
    assertTrue(bundledPage.contains("Choose apps &amp; start"))
    assertTrue(bundledScript.contains("Choose included apps"))
    assertTrue(bundledPage.contains("Allow restricted settings"))
    assertTrue(bundledPage.contains("Open app info"))
    assertTrue(bundledScript.contains("notificationAccess.openAppDetails"))
    assertTrue(bundledScript.contains("message.appDetailsAction !== true"))
    assertFalse(bundledPage.contains("strata", ignoreCase = true))
  }


  private fun request(url: String, mainFrame: Boolean = false): WebResourceRequest =
    object : WebResourceRequest {
      override fun getUrl(): Uri = Uri.parse(url)
      override fun isForMainFrame(): Boolean = mainFrame
      override fun isRedirect(): Boolean = false
      override fun hasGesture(): Boolean = false
      override fun getMethod(): String = "GET"
      override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
