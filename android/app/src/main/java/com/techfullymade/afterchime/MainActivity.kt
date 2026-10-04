package com.techfullymade.afterchime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import org.json.JSONObject

private const val BRIDGE_NAME = "AfterchimeBridge"
private const val BRIDGE_VERSION = 1
private const val MAX_BRIDGE_MESSAGE = 1024
private const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
private const val REMOTE_READY_TIMEOUT_MS = 3_000L

class MainActivity : ComponentActivity() {
  private lateinit var shell: WebView
  private lateinit var assetLoader: WebViewAssetLoader
  private var replyProxy: JavaScriptReplyProxy? = null
  private var usingLocalShell = false
  private var remoteShellReady = false
  private val remoteUri by lazy { Uri.parse(BuildConfig.SHELL_URL) }
  private val remoteReadyWatchdog = Runnable {
    if (!usingLocalShell && !remoteShellReady) loadBundledShell()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    assetLoader = WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
      .build()
    shell = WebView(this)
    shell.layoutParams = ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    configureWebView()
    setContentView(shell)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      WebViewCompat.addWebMessageListener(
        shell,
        BRIDGE_NAME,
        setOf(remoteOriginRule(), LOCAL_ORIGIN),
      ) { _, message, sourceOrigin, isMainFrame, proxy ->
        if (!isMainFrame || !isAllowedOrigin(sourceOrigin)) return@addWebMessageListener
        replyProxy = proxy
        if (handleBridgeMessage(message.data, proxy)) {
          remoteShellReady = true
          shell.removeCallbacks(remoteReadyWatchdog)
        }
      }
    }
    loadRemoteOrBundled()
  }

  override fun onResume() {
    super.onResume()
    sendCapabilityState()
  }

  override fun onDestroy() {
    replyProxy = null
    shell.removeCallbacks(remoteReadyWatchdog)
    shell.stopLoading()
    shell.destroy()
    super.onDestroy()
  }

  private fun configureWebView() {
    shell.settings.apply {
      javaScriptEnabled = true
      // Required for the remote shell's service-worker and Cache Storage offline path.
      domStorageEnabled = true
      allowFileAccess = false
      allowContentAccess = false
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      javaScriptCanOpenWindowsAutomatically = false
      setSupportMultipleWindows(false)
      cacheMode = WebSettings.LOAD_DEFAULT
      safeBrowsingEnabled = true
    }
    shell.setDownloadListener { _, _, _, _, _ -> Unit }
    shell.webChromeClient = object : WebChromeClient() {
      override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
      ): Boolean = false
    }
    shell.webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        if (!usingLocalShell) {
          remoteShellReady = false
          shell.removeCallbacks(remoteReadyWatchdog)
        }
      }

      override fun onPageFinished(view: WebView, url: String?) {
        if (!usingLocalShell && isAllowedUrl(Uri.parse(url ?: ""))) {
          shell.removeCallbacks(remoteReadyWatchdog)
          shell.postDelayed(remoteReadyWatchdog, REMOTE_READY_TIMEOUT_MS)
        }
      }

      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        assetLoader.shouldInterceptRequest(request.url)?.let { return it }
        if (isAllowedUrl(request.url)) return null
        return WebResourceResponse(
          "text/plain",
          "UTF-8",
          403,
          "Blocked",
          emptyMap(),
          java.io.ByteArrayInputStream(ByteArray(0)),
        )
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return !isAllowedUrl(request.url)
        return !isAllowedUrl(request.url)
      }

      override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && !usingLocalShell) loadBundledShell()
      }

      override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
        handler.cancel()
        if (!usingLocalShell) loadBundledShell()
      }

      override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse,
      ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400 && !usingLocalShell) loadBundledShell()
      }
    }
  }

  private fun loadRemoteOrBundled() {
    if (remoteUri.host == "shell-disabled.invalid" || remoteUri.host == "production-disabled.invalid") {
      loadBundledShell()
    } else if (remoteUri.scheme == "https" && !remoteUri.host.isNullOrBlank() && remoteUri.userInfo == null) {
      usingLocalShell = false
      shell.loadUrl(BuildConfig.SHELL_URL)
    } else {
      loadBundledShell()
    }
  }

  private fun loadBundledShell() {
    if (usingLocalShell && shell.url?.startsWith(LOCAL_ORIGIN) == true) return
    shell.removeCallbacks(remoteReadyWatchdog)
    usingLocalShell = true
    shell.loadUrl("$LOCAL_ORIGIN/assets/web/index.html")
  }

  private fun remoteOriginRule(): String = "https://${remoteUri.host}${if (remoteUri.port != -1) ":${remoteUri.port}" else ""}"

  private fun isAllowedOrigin(origin: Uri): Boolean =
    origin.toString() == LOCAL_ORIGIN ||
      (origin.scheme == "https" && origin.host == remoteUri.host && origin.port == remoteUri.port)

  private fun isAllowedUrl(uri: Uri): Boolean =
    (uri.scheme == "https" && uri.host == remoteUri.host && uri.port == remoteUri.port) ||
      (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1)

  private fun handleBridgeMessage(raw: String?, proxy: JavaScriptReplyProxy): Boolean {
    if (raw == null || raw.length > MAX_BRIDGE_MESSAGE) return false
    val request = try {
      JSONObject(raw)
    } catch (_: org.json.JSONException) {
      return false
    }
    if (request.length() != 3 || request.opt("version") != BRIDGE_VERSION) return false
    val id = request.opt("id") as? String ?: return false
    val type = request.opt("type") as? String ?: return false
    if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return false
    val response = when (type) {
      "capabilities.get" -> capabilitiesResponse(id)
      "notificationAccess.openSettings" -> settingsResponse(id)
      else -> return false
    }
    proxy.postMessage(response.toString())
    return true
  }

  private fun capabilitiesResponse(id: String) = JSONObject()
    .put("version", BRIDGE_VERSION)
    .put("id", id)
    .put("type", "capabilities.state")
    .put("notificationAccess", notificationAccessEnabled())

  private fun settingsResponse(id: String): JSONObject {
    val opened = openNotificationAccessSettings()
    return JSONObject()
      .put("version", BRIDGE_VERSION)
      .put("id", id)
      .put("type", "action.result")
      .put("action", "notificationAccess.openSettings")
      .put("ok", opened)
  }

  private fun sendCapabilityState() {
    replyProxy?.postMessage(capabilitiesResponse("resume").toString())
  }

  private fun notificationAccessEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
    val component = android.content.ComponentName(this, StrataNotificationListenerService::class.java)
    return enabled.split(":").any { it == component.flattenToString() }
  }

  internal fun openNotificationAccessSettings(): Boolean {
    return try {
      startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      true
    } catch (_: ActivityNotFoundException) {
      try {
        startActivity(Intent(Settings.ACTION_SETTINGS))
        true
      } catch (_: ActivityNotFoundException) {
        false
      }
    }
  }
}
