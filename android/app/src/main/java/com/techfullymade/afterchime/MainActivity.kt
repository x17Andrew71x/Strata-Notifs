package com.techfullymade.afterchime

import android.content.ActivityNotFoundException
import android.content.ComponentName
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
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.data.catalog.DataStoreWorldCatalog
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.settings.DataStoreUserPreferences
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.ui.AfterchimeApp
import com.techfullymade.afterchime.ui.museum.MuseumScreen
import com.techfullymade.afterchime.ui.museum.MuseumViewModel
import com.techfullymade.afterchime.ui.museum.MissingSpecimenDetailScreen
import com.techfullymade.afterchime.ui.museum.SpecimenDetailScreen
import com.techfullymade.afterchime.ui.theme.AfterchimeTheme
import org.json.JSONObject
import kotlinx.coroutines.launch

private const val BRIDGE_NAME = "AfterchimeBridge"
private const val BRIDGE_VERSION = 1
private const val MAX_BRIDGE_MESSAGE = 1024
internal const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
internal const val LOCAL_ENTRY_URL = "$LOCAL_ORIGIN/assets/web/index.html"
private const val REMOTE_READY_TIMEOUT_MS = 3_000L
private val Context.worldCatalogDataStore by preferencesDataStore(name = "world_catalog")

internal data class BridgeRequest(val id: String, val type: String)

internal fun parseBridgeRequest(raw: String?): BridgeRequest? {
  if (raw == null || raw.length > MAX_BRIDGE_MESSAGE) return null
  val request = try {
    JSONObject(raw)
  } catch (_: org.json.JSONException) {
    return null
  }
  if (request.length() != 3 || request.opt("version") != BRIDGE_VERSION) return null
  val id = request.opt("id") as? String ?: return null
  val type = request.opt("type") as? String ?: return null
  if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
  if (
    type != "capabilities.get" &&
    type != "notificationAccess.openSettings" &&
    type != "notificationAccess.openAppDetails"
  ) return null
  return BridgeRequest(id, type)
}

internal fun isAllowedShellUri(uri: Uri, remoteUri: Uri): Boolean =
  (uri.scheme == "https" && uri.host == remoteUri.host && uri.port == remoteUri.port) ||
    (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1)

internal fun shouldFallbackFromHttpError(
  isMainFrame: Boolean,
  statusCode: Int,
  usingLocalShell: Boolean,
): Boolean = isMainFrame && statusCode >= 400 && !usingLocalShell

internal fun isDevelopmentWorldsEnabled(flavor: String): Boolean = flavor == "dev"

class MainActivity : ComponentActivity() {
  private val museumViewModel: MuseumViewModel by viewModels {
    MuseumViewModel.factory((application as AfterchimeApplication).museumRepository)
  }
  private lateinit var shell: WebView
  private val userPreferences by lazy { DataStoreUserPreferences(applicationContext) }
  private val worldCatalog by lazy { DataStoreWorldCatalog(applicationContext.worldCatalogDataStore) }
  private val developmentWorldsEnabled get() = isDevelopmentWorldsEnabled(BuildConfig.FLAVOR)
  private lateinit var assetLoader: WebViewAssetLoader
  private var replyProxy: JavaScriptReplyProxy? = null
  private var usingLocalShell = false
  private var remoteShellReady = false
  private val remoteUri by lazy { Uri.parse(BuildConfig.SHELL_URL) }
  private val remoteReadyWatchdog = Runnable {
    if (!usingLocalShell && !remoteShellReady) loadBundledShell()
  }

  internal fun shellForTesting(): WebView = shell

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
    setContent {
      val museumState by museumViewModel.uiState.collectAsState()
      val preferences by userPreferences.values.collectAsState(initial = null)
      val worldState by worldCatalog.state.collectAsState(initial = DevelopmentWorldState())
      val preferenceScope = rememberCoroutineScope()
      preferences?.let { loadedPreferences ->
        AfterchimeTheme(
          reduceMotion = loadedPreferences.reduceMotionEnabled,
          highContrast = loadedPreferences.highContrastEnabled,
        ) {
          AfterchimeApp(
            onEnableNotificationAccess = { openNotificationAccessSettings() },
            preferences = loadedPreferences,
            onPreferencesChanged = { updated -> preferenceScope.launch { userPreferences.update { updated } } },
            onOnboardingComplete = {
              preferenceScope.launch { userPreferences.update { it.copy(onboardingComplete = true) } }
            },
            worldState = worldState,
            developmentControlsEnabled = developmentWorldsEnabled,
            onOwnWorldForDevelopment = { world ->
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.ownForDevelopment(world) }
            },
            onSelectWorld = { world ->
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.select(world) }
            },
            onResetDevelopmentWorlds = {
              if (developmentWorldsEnabled) preferenceScope.launch { worldCatalog.resetDevelopmentOwnership() }
            },
            todayContent = {
              AndroidView(
                factory = { shell },
                modifier = Modifier.fillMaxSize(),
              )
            },
            museumContent = { openSpecimen ->
              MuseumScreen(
                state = museumState,
                world = if (developmentWorldsEnabled) worldState.selectedWorld else World.PRIMEVAL_STRATA,
                onTierFilterSelected = museumViewModel::selectTierFilter,
                onSpecimenSelected = { specimenId ->
                  museumViewModel.selectSpecimen(specimenId)
                  openSpecimen(specimenId)
                },
                onCombineSpecimenSelected = museumViewModel::toggleCombineSpecimen,
                onCancelCombine = museumViewModel::cancelCombine,
                onReviewCombine = museumViewModel::reviewCombine,
                onDismissCombineConfirmation = museumViewModel::dismissCombineConfirmation,
                onConfirmCombine = museumViewModel::confirmCombine,
              )
            },
            museumDetailContent = { specimenId, onBack ->
              val specimen = museumState.specimens.firstOrNull { candidate ->
                candidate.id == specimenId && candidate.revealedAtEpochMillis != null
              }
              if (specimen == null) {
                MissingSpecimenDetailScreen(onBack = onBack)
              } else {
                SpecimenDetailScreen(
                  specimen = specimen,
                  world = if (developmentWorldsEnabled) worldState.selectedWorld else World.PRIMEVAL_STRATA,
                  onBack = onBack,
                  onSetLocked = { locked -> museumViewModel.setSpecimenLocked(specimen.id, locked) },
                  onBeginCombine = {
                    museumViewModel.beginCombine(specimen.id)
                    onBack()
                  },
                )
              }
            },
          )
        }
      }
    }
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
        if (shouldFallbackFromHttpError(request.isForMainFrame, errorResponse.statusCode, usingLocalShell)) {
          loadBundledShell()
        }
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
    shell.loadUrl(LOCAL_ENTRY_URL)
  }

  private fun remoteOriginRule(): String = "https://${remoteUri.host}${if (remoteUri.port != -1) ":${remoteUri.port}" else ""}"

  private fun isAllowedOrigin(origin: Uri): Boolean =
    origin.toString() == LOCAL_ORIGIN ||
      (origin.scheme == "https" && origin.host == remoteUri.host && origin.port == remoteUri.port)

  private fun isAllowedUrl(uri: Uri): Boolean = isAllowedShellUri(uri, remoteUri)

  private fun handleBridgeMessage(raw: String?, proxy: JavaScriptReplyProxy): Boolean {
    val request = parseBridgeRequest(raw) ?: return false
    val response = when (request.type) {
      "capabilities.get" -> capabilitiesResponse(request.id)
      "notificationAccess.openSettings" -> settingsResponse(request.id)
      "notificationAccess.openAppDetails" -> appDetailsResponse(request.id)
      else -> return false
    }
    proxy.postMessage(response.toString())
    return true
  }

  internal fun capabilitiesResponse(id: String) = JSONObject()
    .put("version", BRIDGE_VERSION)
    .put("id", id)
    .put("type", "capabilities.state")
    .put("notificationAccess", notificationAccessEnabled())
    .put("appDetailsAction", true)

  private fun settingsResponse(id: String): JSONObject {
    val opened = openNotificationAccessSettings()
    return JSONObject()
      .put("version", BRIDGE_VERSION)
      .put("id", id)
      .put("type", "action.result")
      .put("action", "notificationAccess.openSettings")
      .put("ok", opened)
  }

  private fun appDetailsResponse(id: String): JSONObject {
    val opened = openAppDetailsSettings()
    return JSONObject()
      .put("version", BRIDGE_VERSION)
      .put("id", id)
      .put("type", "action.result")
      .put("action", "notificationAccess.openAppDetails")
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
      startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
          Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
          ComponentName(this, StrataNotificationListenerService::class.java).flattenToString(),
        ),
      )
      true
    } catch (_: ActivityNotFoundException) {
      try {
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

  internal fun openAppDetailsSettings(): Boolean {
    return try {
      startActivity(
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.parse("package:$packageName"),
        ),
      )
      true
    } catch (_: ActivityNotFoundException) {
      try {
        startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        true
      } catch (_: ActivityNotFoundException) {
        false
      }
    }
  }
}
