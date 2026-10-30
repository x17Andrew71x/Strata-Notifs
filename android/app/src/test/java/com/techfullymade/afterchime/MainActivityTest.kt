package com.techfullymade.afterchime

import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.webkit.WebResourceRequest
import androidx.webkit.WebViewFeature
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.gameplay.FossilCatalog
import com.techfullymade.afterchime.gameplay.FossilCatalogManifest
import org.json.JSONObject
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
  fun `launcher uses safe-zone composition over full-bleed ground`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val icon = activity.getDrawable(R.mipmap.ic_afterchime)

    assertEquals(R.mipmap.ic_afterchime, activity.applicationInfo.icon)
    assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
    assertTrue(
      activity.resources.getIdentifier(
        "ic_afterchime_art",
        "drawable",
        activity.packageName,
      ) != 0,
    )
  }

  @Test
  fun `development world boundary follows flavour regardless of build type`() {
    assertTrue(isDevelopmentWorldsEnabled("dev"))
    assertFalse(isDevelopmentWorldsEnabled("prod"))
  }

  @Test
  fun `development feedback is compile-time gated to the non-production dev flavour`() {
    assertTrue(isDevelopmentFeedbackEnabled("dev", productionEnabled = false))
    assertFalse(isDevelopmentFeedbackEnabled("prod", productionEnabled = false))
    assertFalse(isDevelopmentFeedbackEnabled("dev", productionEnabled = true))
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
      isRemoteShellEnabled(
        configuredShell,
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
      )
    ) {
      BuildConfig.SHELL_URL
    } else {
      LOCAL_ENTRY_URL
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
    val dig = parseBridgeRequest(
      """{"version":2,"id":"req_dig","type":"excavation.dig","payload":{"tileIndex":24}}""",
    )
    assertEquals("excavation.dig", dig?.type)
    assertEquals(24, dig?.payload?.getInt("tileIndex"))
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"req_bad_dig","type":"excavation.dig","payload":{"tileIndex":25}}""",
      ),
    )
    assertEquals(
      "exhibits.refresh",
      parseBridgeRequest("""{"version":2,"id":"exhibits_refresh","type":"exhibits.refresh"}""")?.type,
    )
    val contribution = parseBridgeRequest(
      """{"version":2,"id":"exhibits_contribute","type":"exhibits.contribute","payload":{"exhibitId":"returning-light-2027","localDate":"2026-10-26","units":18}}""",
    )
    assertEquals("exhibits.contribute", contribution?.type)
    assertEquals(18, contribution?.payload?.getInt("units"))
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"bad_exhibits","type":"exhibits.contribute","payload":{"exhibitId":"returning-light-2027","localDate":"2026-10-26","units":0}}""",
      ),
    )
    val download = parseBridgeRequest(
      """{"version":2,"id":"exhibit_download","type":"exhibits.download","payload":{"imagePath":"/exhibits/fall-seasonal-special-2026-4k-eb20514a5829.webp"}}""",
    )
    assertEquals("exhibits.download", download?.type)
    assertNull(
      parseBridgeRequest(
        """{"version":2,"id":"bad_exhibit_download","type":"exhibits.download","payload":{"imagePath":"https://example.invalid/private.webp"}}""",
      ),
    )
    val catalogPayload = FossilCatalogManifest(
      revision = FossilCatalog.BUNDLED_REVISION,
      items = FossilCatalog.items,
    ).toJson()
    val catalog = parseBridgeRequest(
      JSONObject()
        .put("version", 2)
        .put("id", "req_catalog")
        .put("type", "catalog.update")
        .put("payload", catalogPayload)
        .toString(),
    )
    assertEquals("catalog.update", catalog?.type)
    assertEquals(17, catalog?.payload?.getJSONArray("items")?.length())
    catalogPayload.put("private", "text")
    assertNull(
      parseBridgeRequest(
        JSONObject()
          .put("version", 2)
          .put("id", "req_bad_catalog")
          .put("type", "catalog.update")
          .put("payload", catalogPayload)
          .toString(),
      ),
    )
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
  fun `development feedback bridge accepts only strict dev-only context`() {
    val payload = JSONObject()
      .put("idempotencyKey", "22222222-2222-4222-8222-222222222222")
      .put("title", "Tighten the action")
      .put("description", "Reduce the empty space above it.")
      .put("route", "today")
      .put("shellRevision", "a".repeat(64))
      .put("appVersion", "0.1.10-dev-debug")
      .put(
        "selection",
        JSONObject()
          .put("kind", "element")
          .put("rect", JSONObject().put("x", 10).put("y", 20).put("width", 120).put("height", 44))
          .put("viewportWidth", 360)
          .put("viewportHeight", 800)
          .put("devId", "excavation-action")
          .put("tag", "button")
          .put("role", "button")
          .put("accessibleLabel", "Excavate tile 1")
          .put("visibleLabel", "Excavate")
          .put("domPath", "[data-dev-id=excavation-action]"),
      )
    val request = JSONObject()
      .put("version", 2)
      .put("id", "feedback_submit")
      .put("type", "devFeedback.submit")
      .put("payload", payload)

    assertNull(parseBridgeRequest(request.toString()))
    assertEquals(
      "devFeedback.submit",
      parseBridgeRequest(request.toString(), developmentFeedback = EnabledDevelopmentFeedbackRequestPolicy)?.type,
    )
    payload.put("arbitraryState", JSONObject().put("private", true))
    assertNull(parseBridgeRequest(request.toString(), developmentFeedback = EnabledDevelopmentFeedbackRequestPolicy))
    assertEquals(
      "devFeedback.items",
      parseBridgeRequest(
        """{"version":2,"id":"feedback_items","type":"devFeedback.items"}""",
        developmentFeedback = EnabledDevelopmentFeedbackRequestPolicy,
      )?.type,
    )
    assertEquals(
      "devFeedback.refresh",
      parseBridgeRequest(
        """{"version":2,"id":"feedback_refresh","type":"devFeedback.refresh"}""",
        developmentFeedback = EnabledDevelopmentFeedbackRequestPolicy,
      )?.type,
    )
    assertEquals(
      "devFeedback.close",
      parseBridgeRequest(
        """{"version":2,"id":"feedback_close","type":"devFeedback.close","payload":{"itemId":"11111111-1111-4111-8111-111111111111"}}""",
        developmentFeedback = EnabledDevelopmentFeedbackRequestPolicy,
      )?.type,
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
    assertTrue(response.getBoolean("catalogUpdates"))
    assertTrue(response.getBoolean("exhibitDownloads"))
    assertEquals(
      BuildConfig.DEV_FEEDBACK_ENABLED,
      response.getBoolean("devFeedback"),
    )
    assertEquals(BuildConfig.VERSION_NAME, response.getString("appVersion"))
  }

  @Test
  fun `development refresh keeps the configured origin and adds cache bypass`() {
    val refreshed = developmentRefreshUrl(Uri.parse("https://shell.example/app"), 1234L)
    assertEquals("https", refreshed.scheme)
    assertEquals("shell.example", refreshed.host)
    assertEquals("/app", refreshed.path)
    assertEquals("1234", refreshed.getQueryParameter("dev-refresh"))
  }

  @Test
  fun `exhibit downloads stay on the configured HTTPS origin with a content-addressed filename`() {
    var capturedUri: Uri? = null
    var capturedFilename: String? = null
    val accepted = enqueueExhibitDownload(
      imagePath = "/exhibits/fall-seasonal-special-2026-4k-eb20514a5829.webp",
      remoteUri = Uri.parse("https://shell.example/app?stale=true"),
    ) { uri, filename ->
      capturedUri = uri
      capturedFilename = filename
      42L
    }

    assertTrue(accepted)
    assertEquals(
      "https://shell.example/exhibits/fall-seasonal-special-2026-4k-eb20514a5829.webp",
      capturedUri.toString(),
    )
    assertEquals(
      "fall-seasonal-special-2026-4k-eb20514a5829.webp",
      capturedFilename,
    )
    assertFalse(
      enqueueExhibitDownload(
        "https://example.invalid/private.webp",
        Uri.parse("https://shell.example/"),
      ) { _, _ -> 1L },
    )
    assertFalse(
      enqueueExhibitDownload(
        "/exhibits/fall-seasonal-special-2026-4k-eb20514a5829.webp",
        Uri.parse("https://shell-disabled.invalid/"),
      ) { _, _ -> 1L },
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
