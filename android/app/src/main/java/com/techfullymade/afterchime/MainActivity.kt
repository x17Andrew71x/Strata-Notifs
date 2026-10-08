package com.techfullymade.afterchime

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.catalog.WorldSelectionResult
import com.techfullymade.afterchime.data.catalog.DataStoreWorldCatalog
import com.techfullymade.afterchime.domain.CombineRequest
import com.techfullymade.afterchime.domain.CombineResult
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.sharing.ShareUseCase
import com.techfullymade.afterchime.ui.today.TodayUiState
import com.techfullymade.afterchime.ui.today.TodayViewModel
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val BRIDGE_NAME = "AfterchimeBridge"
private const val BRIDGE_VERSION = 2
private const val MAX_BRIDGE_REQUEST = 8 * 1024
internal const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
internal const val LOCAL_ENTRY_URL = "$LOCAL_ORIGIN/assets/web/index.html"
private const val REMOTE_READY_TIMEOUT_MS = 5_000L
private val Context.worldCatalogDataStore by preferencesDataStore(name = "world_catalog")
private val requestIdPattern = Regex("[A-Za-z0-9_-]{1,64}")
private val specimenIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
private val preferenceKeys = setOf(
  "onlineFeaturesEnabled",
  "productAnalyticsEnabled",
  "notificationAggregateSharingEnabled",
  "reduceMotionEnabled",
  "highContrastEnabled",
  "hapticsEnabled",
)

internal data class BridgeRequest(
  val id: String,
  val type: String,
  val payload: JSONObject? = null,
)

internal fun parseBridgeRequest(raw: String?): BridgeRequest? {
  if (raw == null || raw.length > MAX_BRIDGE_REQUEST) return null
  val request = try {
    JSONObject(raw)
  } catch (_: JSONException) {
    return null
  }
  if (request.opt("version") != BRIDGE_VERSION) return null
  val id = request.opt("id") as? String ?: return null
  val type = request.opt("type") as? String ?: return null
  if (!requestIdPattern.matches(id)) return null
  val payload = request.optJSONObject("payload")
  val noPayloadActions = setOf(
    "capabilities.get",
    "state.get",
    "notificationAccess.openSettings",
    "notificationAccess.openAppDetails",
    "onboarding.complete",
    "formation.reveal",
    "worlds.reset",
  )
  if (type in noPayloadActions) {
    return if (request.length() == 3 && payload == null) BridgeRequest(id, type) else null
  }
  if (request.length() != 4 || payload == null) return null
  val validPayload = when (type) {
    "preferences.update" ->
      payload.length() == 2 &&
        payload.opt("key") is String &&
        payload.optString("key") in preferenceKeys &&
        payload.opt("value") is Boolean

    "museum.lock" ->
      payload.length() == 2 &&
        validSpecimenId(payload.opt("specimenId")) &&
        payload.opt("locked") is Boolean

    "museum.share" -> payload.length() == 1 && validSpecimenId(payload.opt("specimenId"))
    "museum.combine" -> payload.length() == 1 && validCombineIds(payload.optJSONArray("specimenIds"))
    "worlds.select", "worlds.own" ->
      payload.length() == 1 &&
        payload.opt("world") is String &&
        World.entries.any { it.name == payload.optString("world") }

    else -> false
  }
  return if (validPayload) BridgeRequest(id, type, payload) else null
}

private fun validSpecimenId(value: Any?): Boolean = value is String && specimenIdPattern.matches(value)

private fun validCombineIds(value: JSONArray?): Boolean {
  if (value == null || value.length() != 3) return false
  val ids = (0 until value.length()).map { index -> value.opt(index) }
  return ids.all(::validSpecimenId) && ids.toSet().size == 3
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

internal fun isRemoteShellEnabled(remoteUri: Uri, bridgeSupported: Boolean): Boolean =
  bridgeSupported &&
    remoteUri.host != "shell-disabled.invalid" &&
    remoteUri.host != "production-disabled.invalid" &&
    remoteUri.scheme == "https" &&
    !remoteUri.host.isNullOrBlank() &&
    remoteUri.userInfo == null

class MainActivity : ComponentActivity() {
  private val applicationState get() = application as AfterchimeApplication
  private val todayViewModel: TodayViewModel by viewModels {
    TodayViewModel.factory(applicationState.formationRepository)
  }
  private val userPreferences by lazy { applicationState.userPreferences }
  private val worldCatalog by lazy { DataStoreWorldCatalog(applicationContext.worldCatalogDataStore) }
  private val developmentWorldsEnabled get() = isDevelopmentWorldsEnabled(BuildConfig.FLAVOR)
  private lateinit var shell: WebView
  private lateinit var assetLoader: WebViewAssetLoader
  private var replyProxy: JavaScriptReplyProxy? = null
  private var latestShellState: JSONObject? = null
  private var latestSpecimens: List<MuseumSpecimen> = emptyList()
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
    shell = WebView(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      )
    }
    configureWebView()
    setContentView(shell)
    installBackNavigation()
    val bridgeSupported = installBridge()
    observeShellState()
    loadRemoteOrBundled(bridgeSupported)
  }

  override fun onResume() {
    super.onResume()
    sendCapabilityState("resume")
    sendShellState("resume_state")
  }

  override fun onDestroy() {
    replyProxy = null
    shell.removeCallbacks(remoteReadyWatchdog)
    shell.stopLoading()
    shell.destroy()
    super.onDestroy()
  }

  private fun installBackNavigation() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (shell.canGoBack()) shell.goBack() else finish()
        }
      },
    )
  }

  private fun installBridge(): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      return false
    }
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
    return true
  }

  private fun observeShellState() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(
          userPreferences.values,
          worldCatalog.state,
          todayViewModel.uiState,
          applicationState.museumRepository.specimens,
        ) { preferences, worldState, todayState, specimens ->
          latestSpecimens = specimens
          buildShellState(preferences, worldState, todayState, specimens)
        }.collect { state ->
          latestShellState = state
          sendShellState("state")
        }
      }
    }
  }

  private fun configureWebView() {
    shell.settings.apply {
      javaScriptEnabled = true
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
          ByteArrayInputStream(ByteArray(0)),
        )
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isAllowedUrl(request.url)

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
        errorResponse: WebResourceResponse,
      ) {
        if (shouldFallbackFromHttpError(request.isForMainFrame, errorResponse.statusCode, usingLocalShell)) {
          loadBundledShell()
        }
      }
    }
  }

  private fun loadRemoteOrBundled(bridgeSupported: Boolean) {
    if (isRemoteShellEnabled(remoteUri, bridgeSupported)) {
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

  private fun remoteOriginRule(): String =
    "https://${remoteUri.host}${if (remoteUri.port != -1) ":${remoteUri.port}" else ""}"

  private fun isAllowedOrigin(origin: Uri): Boolean =
    (origin.scheme == "https" && origin.host == "appassets.androidplatform.net" && origin.port == -1) ||
      (origin.scheme == "https" && origin.host == remoteUri.host && origin.port == remoteUri.port)

  private fun isAllowedUrl(uri: Uri): Boolean = isAllowedShellUri(uri, remoteUri)

  private fun handleBridgeMessage(raw: String?, proxy: JavaScriptReplyProxy): Boolean {
    val request = parseBridgeRequest(raw) ?: return false
    when (request.type) {
      "capabilities.get" -> proxy.postMessage(capabilitiesResponse(request.id).toString())
      "state.get" -> sendShellState(request.id, proxy)
      "notificationAccess.openSettings" -> postActionResult(
        request,
        openNotificationAccessSettings(),
        proxy,
      )
      "notificationAccess.openAppDetails" -> postActionResult(
        request,
        openAppDetailsSettings(),
        proxy,
      )
      "onboarding.complete" -> lifecycleScope.launch {
        userPreferences.update { it.copy(onboardingComplete = true) }
        postActionResult(request, true, proxy)
      }
      "preferences.update" -> updatePreference(request, proxy)
      "formation.reveal" -> {
        val accepted = todayViewModel.uiState.value.primaryAction.name == "REVEAL"
        if (accepted) todayViewModel.reveal()
        postActionResult(request, accepted, proxy)
      }
      "museum.lock" -> updateSpecimenLock(request, proxy)
      "museum.share" -> shareSpecimen(request, proxy)
      "museum.combine" -> combineSpecimens(request, proxy)
      "worlds.select" -> selectWorld(request, proxy)
      "worlds.own" -> ownWorld(request, proxy)
      "worlds.reset" -> resetWorlds(request, proxy)
      else -> return false
    }
    return true
  }

  private fun updatePreference(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val payload = request.payload ?: return
    val key = payload.getString("key")
    val value = payload.getBoolean("value")
    lifecycleScope.launch {
      userPreferences.update { current -> current.withPreference(key, value) }
      postActionResult(request, true, proxy)
    }
  }

  private fun updateSpecimenLock(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val payload = request.payload ?: return
    lifecycleScope.launch {
      val result = applicationState.museumRepository.setLocked(
        payload.getString("specimenId"),
        payload.getBoolean("locked"),
      )
      postActionResult(
        request,
        result !is com.techfullymade.afterchime.domain.SpecimenLockResult.NotFound,
        proxy,
      )
    }
  }

  private fun shareSpecimen(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val specimenId = request.payload?.getString("specimenId") ?: return
    val specimen = latestSpecimens.firstOrNull { it.id == specimenId && it.revealedAtEpochMillis != null }
    val accepted = specimen != null
    if (specimen != null) {
      val world = latestShellState?.getJSONObject("worlds")?.getString("selected")
        ?.let(World::valueOf)
        ?: World.PRIMEVAL_STRATA
      ShareUseCase(this).share(specimen, world)
    }
    postActionResult(request, accepted, proxy)
  }

  private fun combineSpecimens(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val ids = request.payload?.getJSONArray("specimenIds") ?: return
    val inputIds = (0 until ids.length()).map(ids::getString)
    lifecycleScope.launch {
      val result = applicationState.museumRepository.combine(
        CombineRequest(
          mutationId = UUID.randomUUID().toString(),
          outputItemId = UUID.randomUUID().toString(),
          inputItemIds = inputIds,
          createdAtEpochMillis = System.currentTimeMillis(),
        ),
      )
      postActionResult(
        request,
        result is CombineResult.Combined || result is CombineResult.AlreadyCombined,
        proxy,
      )
    }
  }

  private fun selectWorld(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    if (!developmentWorldsEnabled) {
      postActionResult(request, false, proxy)
      return
    }
    val world = World.valueOf(request.payload?.getString("world") ?: return)
    lifecycleScope.launch {
      postActionResult(request, worldCatalog.select(world) == WorldSelectionResult.SELECTED, proxy)
    }
  }

  private fun ownWorld(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    if (!developmentWorldsEnabled) {
      postActionResult(request, false, proxy)
      return
    }
    val world = World.valueOf(request.payload?.getString("world") ?: return)
    lifecycleScope.launch {
      worldCatalog.ownForDevelopment(world)
      postActionResult(request, true, proxy)
    }
  }

  private fun resetWorlds(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    if (!developmentWorldsEnabled) {
      postActionResult(request, false, proxy)
      return
    }
    lifecycleScope.launch {
      worldCatalog.resetDevelopmentOwnership()
      postActionResult(request, true, proxy)
    }
  }

  private fun postActionResult(
    request: BridgeRequest,
    ok: Boolean,
    proxy: JavaScriptReplyProxy = replyProxy ?: return,
  ) {
    proxy.postMessage(
      JSONObject()
        .put("version", BRIDGE_VERSION)
        .put("id", request.id)
        .put("type", "action.result")
        .put("action", request.type)
        .put("ok", ok)
        .toString(),
    )
  }

  internal fun capabilitiesResponse(id: String): JSONObject = JSONObject()
    .put("version", BRIDGE_VERSION)
    .put("id", id)
    .put("type", "capabilities.state")
    .put("bridgeVersion", BRIDGE_VERSION)
    .put("notificationAccess", notificationAccessEnabled())
    .put("appDetailsAction", true)

  private fun sendCapabilityState(id: String) {
    replyProxy?.postMessage(capabilitiesResponse(id).toString())
  }

  private fun sendShellState(id: String, proxy: JavaScriptReplyProxy? = replyProxy) {
    val state = latestShellState ?: return
    proxy?.postMessage(
      JSONObject(state.toString())
        .put("version", BRIDGE_VERSION)
        .put("id", id)
        .put("type", "shell.state")
        .put("notificationAccess", notificationAccessEnabled())
        .toString(),
    )
  }

  private fun buildShellState(
    preferences: UserPreferences,
    worldState: DevelopmentWorldState,
    todayState: TodayUiState,
    specimens: List<MuseumSpecimen>,
  ): JSONObject = JSONObject()
    .put("preferences", preferences.toJson())
    .put("today", todayState.toJson())
    .put("museum", JSONObject().put("specimens", JSONArray(specimens.map(MuseumSpecimen::toJson))))
    .put(
      "worlds",
      JSONObject()
        .put("selected", worldState.selectedWorld.name)
        .put("owned", JSONArray(worldState.ownedWorlds.map(World::name).sorted()))
        .put("available", JSONArray(World.entries.map(World::name)))
        .put("developmentControlsEnabled", developmentWorldsEnabled),
    )

  private fun notificationAccessEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
    val component = ComponentName(this, StrataNotificationListenerService::class.java)
    return enabled.split(":").any { it == component.flattenToString() }
  }

  internal fun openNotificationAccessSettings(): Boolean = try {
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

  internal fun openAppDetailsSettings(): Boolean = try {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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

private fun UserPreferences.withPreference(key: String, value: Boolean): UserPreferences = when (key) {
  "onlineFeaturesEnabled" -> copy(
    onlineFeaturesEnabled = value,
    productAnalyticsEnabled = if (value) productAnalyticsEnabled else false,
    notificationAggregateSharingEnabled = if (value) notificationAggregateSharingEnabled else false,
  )
  "productAnalyticsEnabled" -> copy(productAnalyticsEnabled = onlineFeaturesEnabled && value)
  "notificationAggregateSharingEnabled" -> copy(
    notificationAggregateSharingEnabled = onlineFeaturesEnabled && value,
  )
  "reduceMotionEnabled" -> copy(reduceMotionEnabled = value)
  "highContrastEnabled" -> copy(highContrastEnabled = value)
  "hapticsEnabled" -> copy(hapticsEnabled = value)
  else -> this
}

private fun UserPreferences.toJson(): JSONObject = JSONObject()
  .put("onboardingComplete", onboardingComplete)
  .put("onlineFeaturesEnabled", onlineFeaturesEnabled)
  .put("productAnalyticsEnabled", productAnalyticsEnabled)
  .put("notificationAggregateSharingEnabled", notificationAggregateSharingEnabled)
  .put("reduceMotionEnabled", reduceMotionEnabled)
  .put("highContrastEnabled", highContrastEnabled)
  .put("hapticsEnabled", hapticsEnabled)

private fun TodayUiState.toJson(): JSONObject = JSONObject()
  .put("localDate", snapshot.localDate.toString())
  .put("observation", snapshot.observation.javaClass.simpleName)
  .put(
    "layers",
    JSONArray(
      snapshot.layers.map { layer ->
        JSONObject()
          .put("localHour", layer.localHour)
          .put("category", layer.category.name)
          .put("sourceColourRgb", layer.sourceColourRgb)
      },
    ),
  )
  .put("specimen", specimen?.toJson() ?: JSONObject.NULL)
  .put("revealInFlight", revealInFlight)
  .put("primaryAction", primaryAction.name)

private fun MuseumSpecimen.toJson(): JSONObject = JSONObject()
  .put("id", id)
  .put("anchoredLocalDate", anchoredLocalDate?.toString() ?: JSONObject.NULL)
  .put("generatorVersion", generatorVersion)
  .put("createdAtEpochMillis", createdAtEpochMillis)
  .put("revealedAtEpochMillis", revealedAtEpochMillis ?: JSONObject.NULL)
  .put("isLocked", isLocked)
  .put("collectibleState", collectibleState.name)
  .put("provenanceCount", provenanceCount)
  .put("family", family.name)
  .put("tier", tier.name)
  .put(
    "visual",
    JSONObject()
      .put("hueDegrees", visual.hueDegrees)
      .put("strataCount", visual.strataCount)
      .put("inclusionDensityPercent", visual.inclusionDensityPercent)
      .put("reliefPercent", visual.reliefPercent)
      .put("rotationDegrees", visual.rotationDegrees),
  )