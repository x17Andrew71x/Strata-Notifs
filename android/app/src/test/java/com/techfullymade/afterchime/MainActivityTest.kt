package com.techfullymade.afterchime

import android.net.Uri
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
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
  fun `hosts a hardened WebView within the native app shell and starts from the configured shell or bundled disabled fallback`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val webView = findWebView(activity.findViewById(android.R.id.content))
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
    val webView = findWebView(activity.findViewById(android.R.id.content))

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
  }

  @Test
  fun `notification settings action opens Android listener settings`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertTrue(activity.openNotificationAccessSettings())
    val startedIntent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
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

  private fun findWebView(view: android.view.View): WebView = when (view) {
    is WebView -> view
    is android.view.ViewGroup -> {
      requireNotNull((0 until view.childCount).firstNotNullOfOrNull { index ->
        findWebViewOrNull(view.getChildAt(index))
      })
    }
    else -> error("WebView not found in native app shell")
  }

  private fun findWebViewOrNull(view: android.view.View): WebView? = when (view) {
    is WebView -> view
    is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { index ->
      findWebViewOrNull(view.getChildAt(index))
    }
    else -> null
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
