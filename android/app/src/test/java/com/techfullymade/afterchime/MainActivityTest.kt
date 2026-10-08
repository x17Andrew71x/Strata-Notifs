package com.techfullymade.afterchime

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.webkit.WebResourceRequest
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
  fun `development world boundary follows flavour regardless of build type`() {
    assertTrue(isDevelopmentWorldsEnabled("dev"))
    assertFalse(isDevelopmentWorldsEnabled("prod"))
  }

  @Test
  fun `remote shell requires the bounded bridge and an enabled HTTPS origin`() {
    assertTrue(isRemoteShellEnabled(Uri.parse("https://shell.example/"), bridgeSupported = true))
    assertFalse(isRemoteShellEnabled(Uri.parse("https://shell.example/"), bridgeSupported = false))
    assertFalse(isRemoteShellEnabled(Uri.parse("http://shell.example/"), bridgeSupported = true))
    assertFalse(isRemoteShellEnabled(Uri.parse("https://shell-disabled.invalid/"), bridgeSupported = true))
    assertFalse(isRemoteShellEnabled(Uri.parse("https://production-disabled.invalid/"), bridgeSupported = true))
    assertFalse(isRemoteShellEnabled(Uri.parse("https://user@shell.example/"), bridgeSupported = true))
  }

  @Test
  fun `activity is a hardened remote shell with bundled fallback`() {
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
  fun `shell blocks off-origin requests and permits configured and bundled origins`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val webView = activity.shellForTesting()

    val blocked = webView.webViewClient.shouldInterceptRequest(
      webView,
      request("https://example.invalid/private.js"),
    )
    assertEquals(403, blocked?.statusCode)
    assertNull(webView.webViewClient.shouldInterceptRequest(webView, request(BuildConfig.SHELL_URL)))
    assertTrue(isAllowedShellUri(Uri.parse(LOCAL_ENTRY_URL), Uri.parse(BuildConfig.SHELL_URL)))
    assertFalse(isAllowedShellUri(Uri.parse("https://example.invalid/"), Uri.parse(BuildConfig.SHELL_URL)))
  }

  @Test
  fun `remote main-frame HTTP errors trigger bundled fallback policy`() {
    assertTrue(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 503, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = false, statusCode = 503, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 399, usingLocalShell = false))
    assertFalse(shouldFallbackFromHttpError(isMainFrame = true, statusCode = 503, usingLocalShell = true))
  }

  @Test
  fun `v2 bridge accepts only bounded shell actions and exact payloads`() {
    assertNull(parseBridgeRequest(null))
    assertNull(parseBridgeRequest("{"))
    assertNull(parseBridgeRequest("""{"version":1,"id":"req","type":"state.get"}"""))
    assertNull(parseBridgeRequest("""{"version":2,"id":"req","type":"notification.read"}"""))
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"req","type":"state.get","payload":{"private":"text"}}""",
      ),
    )
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"req","type":"preferences.update","payload":{"key":"unknown","value":true}}""",
      ),
    )
    assertEquals(
      BridgeRequest("req_1", "state.get"),
      parseBridgeRequest("""{"version":2,"id":"req_1","type":"state.get"}"""),
    )
    val preference = parseBridgeRequest(
      """{"version":2,"id":"req_2","type":"preferences.update","payload":{"key":"reduceMotionEnabled","value":true}}""",
    )
    assertEquals("preferences.update", preference?.type)
    assertEquals("reduceMotionEnabled", preference?.payload?.getString("key"))
    assertTrue(preference?.payload?.getBoolean("value") == true)
    val combine = parseBridgeRequest(
      """{"version":2,"id":"req_3","type":"museum.combine","payload":{"specimenIds":["one","two","three"]}}""",
    )
    assertEquals("museum.combine", combine?.type)
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"req_4","type":"museum.combine","payload":{"specimenIds":["one","one","three"]}}""",
      ),
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
  fun `capability response advertises bridge v2 and app info recovery`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val response = activity.capabilitiesResponse("req_capabilities")

    assertEquals(2, response.getInt("version"))
    assertEquals(2, response.getInt("bridgeVersion"))
    assertEquals("capabilities.state", response.getString("type"))
    assertTrue(response.getBoolean("appDetailsAction"))
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
  @Suppress("DEPRECATION")
  fun `Android 12 listener starts closed and sensitive notification types remain disabled`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val serviceInfo = activity.packageManager.getServiceInfo(
      ComponentName(activity, StrataNotificationListenerService::class.java),
      PackageManager.GET_META_DATA,
    )

    assertEquals(31, activity.applicationInfo.minSdkVersion)
    assertEquals("", serviceInfo.metaData.getString(NotificationListenerService.META_DATA_DEFAULT_FILTER_TYPES))
    val disabledTypes = serviceInfo.metaData
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
    assertFalse(disabledTypes.orEmpty().contains(NotificationListenerService.FLAG_FILTER_TYPE_ALERTING))
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