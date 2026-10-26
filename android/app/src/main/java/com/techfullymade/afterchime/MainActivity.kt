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
import com.techfullymade.afterchime.analytics.AnalyticsEvent
import com.techfullymade.afterchime.analytics.LaunchType
import com.techfullymade.afterchime.capture.StrataNotificationListenerService
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.catalog.WorldSelectionResult
import com.techfullymade.afterchime.community.ExhibitSnapshot
import com.techfullymade.afterchime.community.ExhibitsSnapshot
import com.techfullymade.afterchime.community.PastExhibitSnapshot
import com.techfullymade.afterchime.data.catalog.DataStoreWorldCatalog
import com.techfullymade.afterchime.domain.CombineRequest
import com.techfullymade.afterchime.domain.CombineResult
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.gameplay.FossilCatalogManifest
import com.techfullymade.afterchime.gameplay.GameBalance
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.settings.UserPreferences
import com.techfullymade.afterchime.sharing.ShareUseCase
import com.techfullymade.afterchime.sharing.ShareOutcome
import com.techfullymade.afterchime.ui.today.TodayUiState
import com.techfullymade.afterchime.ui.today.TodayViewModel
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val BRIDGE_NAME = "AfterchimeBridge"
private const val BRIDGE_VERSION = 2
private const val MAX_BRIDGE_REQUEST = 128 * 1024
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
private val onlinePreferenceKeys = setOf(
  "onlineFeaturesEnabled",
  "productAnalyticsEnabled",
  "notificationAggregateSharingEnabled",
)

internal data class BridgeRequest(
  val id: String,
  val type: String,
  val payload: JSONObject? = null,
)

internal fun parseBridgeRequest(
  raw: String?,
  developmentFeedback: DevelopmentFeedbackRequestPolicy = DisabledDevelopmentFeedback,
): BridgeRequest? {
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
  val noPayloadActions = mutableSetOf(
    "capabilities.get",
    "state.get",
    "notificationAccess.openSettings",
    "notificationAccess.openAppDetails",
    "onboarding.complete",
    "formation.reveal",
    "exhibits.refresh",
    "worlds.reset",
  )
  if (type in noPayloadActions || developmentFeedback.acceptsNoPayload(type)) {
    return if (request.length() == 3 && payload == null) BridgeRequest(id, type) else null
  }
  if (request.length() != 4 || payload == null) return null
  val validPayload = when (type) {
    "catalog.update" -> FossilCatalogManifest.parse(payload) != null
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
    "excavation.dig" ->
      payload.length() == 1 &&
        payload.opt("tileIndex") is Int &&
        payload.getInt("tileIndex") in 0 until GameBalance.EXCAVATION_TILE_COUNT
    "exhibits.contribute" ->
      payload.length() == 3 &&
        payload.opt("exhibitId") is String &&
        payload.getString("exhibitId").matches(Regex("^[a-z0-9-]{1,80}$")) &&
        payload.opt("localDate") is String &&
        runCatching { java.time.LocalDate.parse(payload.getString("localDate")) }.isSuccess &&
        payload.opt("units") is Int &&
        payload.getInt("units") in 1..100_000
    "worlds.select", "worlds.own" ->
      payload.length() == 1 &&
        payload.opt("world") is String &&
        World.entries.any { it.name == payload.optString("world") }

    else -> developmentFeedback.acceptsPayload(type, payload)
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

internal fun isDevelopmentFeedbackEnabled(flavor: String, productionEnabled: Boolean): Boolean =
  flavor == "dev" && !productionEnabled

internal fun developmentRefreshUrl(remoteUri: Uri, nonce: Long): Uri =
  remoteUri.buildUpon().appendQueryParameter("dev-refresh", nonce.toString()).build()

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
  private val developmentFeedback by lazy {
    createDevelopmentFeedback(
      DevelopmentFeedbackDependencies(
        activity = this,
        application = applicationState,
        shell = { shell },
        refreshShell = ::refreshRemoteShell,
      ),
    )
  }
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
      .addPathHandler("/worlds/", WebViewAssetLoader.AssetsPathHandler(this))
      .addPathHandler("/exhibits/", WebViewAssetLoader.AssetsPathHandler(this))
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
    lifecycleScope.launch {
      applicationState.recordProductUse(AnalyticsEvent.AppOpened(LaunchType.COLD), "today")
    }
  }

  override fun onResume() {
    super.onResume()
    todayViewModel.refreshDate()
    sendCapabilityState("resume")
    sendShellState("resume_state")
    lifecycleScope.launch { refreshExhibits() }
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
          applicationState.exhibitsRepository.state,
        ) { preferences, worldState, todayState, specimens, exhibits ->
          latestSpecimens = specimens
          buildShellState(preferences, worldState, todayState, specimens, exhibits)
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
    val request = parseBridgeRequest(raw, developmentFeedback) ?: return false
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
      "catalog.update" -> postActionResult(
        request,
        applicationState.updateFossilCatalog(request.payload ?: return false),
        proxy,
      )
      "preferences.update" -> updatePreference(request, proxy)
      "formation.reveal" -> {
        val accepted = todayViewModel.uiState.value.primaryAction.name == "REVEAL"
        if (accepted) {
          todayViewModel.reveal()
          lifecycleScope.launch {
            applicationState.recordProductUse(AnalyticsEvent.SpecimenRevealStarted, "today")
          }
        }
        postActionResult(request, accepted, proxy)
      }
      "excavation.dig" -> {
        val accepted = todayViewModel.dig(request.payload?.getInt("tileIndex") ?: -1)
        postActionResult(request, accepted, proxy)
      }
      "exhibits.refresh" -> lifecycleScope.launch {
        postActionResult(request, refreshExhibits(), proxy)
      }
      "exhibits.contribute" -> contributeToExhibit(request, proxy)
      "museum.lock" -> updateSpecimenLock(request, proxy)
      "museum.share" -> shareSpecimen(request, proxy)
      "museum.combine" -> combineSpecimens(request, proxy)
      "worlds.select" -> selectWorld(request, proxy)
      "worlds.own" -> ownWorld(request, proxy)
      "worlds.reset" -> resetWorlds(request, proxy)
      else -> if (!developmentFeedback.handle(request, proxy)) return false
    }
    return true
  }

  private suspend fun refreshExhibits(): Boolean {
    val preferences = userPreferences.values.first()
    val today = todayViewModel.uiState.value
    return applicationState.exhibitsRepository.refresh(
      localDate = today.snapshot.localDate.toString(),
      surplusEnergy = today.exhibitSurplusEnergy(),
      onlineEnabled = preferences.onlineFeaturesEnabled,
    )
  }

  private fun contributeToExhibit(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val payload = request.payload ?: return
    val today = todayViewModel.uiState.value
    val localDate = today.snapshot.localDate.toString()
    val snapshot = applicationState.exhibitsRepository.state.value
    val accepted =
      payload.getString("localDate") == localDate &&
        payload.getInt("units") == snapshot.bankedEnergy &&
        snapshot.bankedEnergy > 0
    if (!accepted) {
      postActionResult(request, false, proxy)
      return
    }
    lifecycleScope.launch {
      postActionResult(
        request,
        applicationState.exhibitsRepository.contribute(
          exhibitId = payload.getString("exhibitId"),
          localDate = localDate,
          surplusEnergy = today.exhibitSurplusEnergy(),
        ),
        proxy,
      )
    }
  }

  private fun updatePreference(request: BridgeRequest, proxy: JavaScriptReplyProxy) {
    val payload = request.payload ?: return
    val key = payload.getString("key")
    val value = payload.getBoolean("value")
    lifecycleScope.launch {
      userPreferences.update { current -> current.withPreference(key, value) }
      if (key in onlinePreferenceKeys) applicationState.scheduleOnlineSync()
      val event = when (key) {
        "productAnalyticsEnabled" -> AnalyticsEvent.AnalyticsConsentChanged(value, 1)
        "notificationAggregateSharingEnabled" ->
          AnalyticsEvent.NotificationAggregateConsentChanged(value, 1)
        else -> AnalyticsEvent.SettingChanged
      }
      applicationState.recordProductUse(event, "settings")
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
      val accepted = result !is com.techfullymade.afterchime.domain.SpecimenLockResult.NotFound
      if (accepted) {
        applicationState.recordProductUse(
          if (payload.getBoolean("locked")) AnalyticsEvent.SpecimenLocked else AnalyticsEvent.SpecimenUnlocked,
          "museum",
        )
      }
      postActionResult(request, accepted, proxy)
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
      val outcome = ShareUseCase(this).share(specimen, world)
      lifecycleScope.launch {
        applicationState.recordProductUse(AnalyticsEvent.ShareStarted, "museum")
        applicationState.recordProductUse(
          if (outcome == ShareOutcome.Shared) AnalyticsEvent.ShareCompleted else AnalyticsEvent.ShareFailed,
          "museum",
        )
      }
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
      val accepted = result is CombineResult.Combined || result is CombineResult.AlreadyCombined
      if (accepted) applicationState.recordProductUse(AnalyticsEvent.CombineCompleted, "museum")
      postActionResult(request, accepted, proxy)
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
    proxy: JavaScriptReplyProxy? = replyProxy,
  ) {
    proxy ?: return
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

  internal fun capabilitiesResponse(id: String): JSONObject = developmentFeedback.decorateCapabilities(
    JSONObject()
      .put("version", BRIDGE_VERSION)
      .put("id", id)
      .put("type", "capabilities.state")
      .put("bridgeVersion", BRIDGE_VERSION)
      .put("notificationAccess", notificationAccessEnabled())
      .put("appDetailsAction", true)
      .put("catalogUpdates", true),
  )

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
    exhibits: ExhibitsSnapshot,
  ): JSONObject = JSONObject()
    .put("preferences", preferences.toJson())
    .put("today", todayState.toJson())
    .put("museum", JSONObject().put("specimens", JSONArray(specimens.map(MuseumSpecimen::toJson))))
    .put("exhibits", exhibits.toJson())
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

  internal fun refreshRemoteShell(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
    if (!isRemoteShellEnabled(remoteUri, bridgeSupported = true)) return false
    shell.removeCallbacks(remoteReadyWatchdog)
    usingLocalShell = false
    remoteShellReady = false
    shell.loadUrl(
      developmentRefreshUrl(remoteUri, nowEpochMillis).toString(),
      mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache"),
    )
    return true
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

private fun ExhibitsSnapshot.toJson(): JSONObject = JSONObject()
  .put("status", status.name)
  .put("bankedEnergy", bankedEnergy)
  .put("contributionInFlight", contributionInFlight)
  .put("featured", featured?.toJson() ?: JSONObject.NULL)
  .put("archive", archive?.toJson() ?: JSONObject.NULL)
  .put("past", JSONArray(past.map(PastExhibitSnapshot::toJson)))

private fun ExhibitSnapshot.toJson(): JSONObject = JSONObject()
  .put("id", id)
  .put("title", title)
  .put("seasonLabel", seasonLabel)
  .put("imagePath", imagePath)
  .put("progressBasisPoints", progressBasisPoints)
  .put("personalContribution", personalContribution)
  .put("personalMinimum", personalMinimum)
  .put("daysRemaining", daysRemaining ?: JSONObject.NULL)
  .put("communityComplete", communityComplete)
  .put("qualified", qualified)
  .put("unlocked", unlocked)
  .put("closed", closed)

private fun PastExhibitSnapshot.toJson(): JSONObject = JSONObject()
  .put("id", id)
  .put("title", title)
  .put("seasonLabel", seasonLabel)
  .put("imagePath", imagePath)
  .put("unlocked", unlocked)
  .put("lockedReason", lockedReason ?: JSONObject.NULL)

private fun TodayUiState.exhibitSurplusEnergy(): Int =
  snapshot.excavation?.takeIf { it.completed }?.energyAvailable?.coerceAtLeast(0) ?: 0

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
  .put("excavation", snapshot.excavation?.toJson() ?: JSONObject.NULL)
  .put("revealInFlight", revealInFlight)
  .put("digInFlight", digInFlight)
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
  .put("catalogItemId", catalogItemId ?: JSONObject.NULL)
  .put(
    "visual",
    JSONObject()
      .put("hueDegrees", visual.hueDegrees)
      .put("strataCount", visual.strataCount)
      .put("inclusionDensityPercent", visual.inclusionDensityPercent)
      .put("reliefPercent", visual.reliefPercent)
      .put("rotationDegrees", visual.rotationDegrees),
  )

private fun com.techfullymade.afterchime.domain.DailyExcavationSnapshot.toJson(): JSONObject =
  JSONObject()
    .put("artifactId", artifactId)
    .put("capturedNotificationCount", capturedNotificationCount)
    .put("eligibleNotificationCount", eligibleNotificationCount)
    .put("energyPerNotification", energyPerNotification)
    .put("energyEarned", energyEarned)
    .put("energySpent", energySpent)
    .put("energyAvailable", energyAvailable)
    .put("tileEnergyCost", tileEnergyCost)
    .put("gridColumns", gridColumns)
    .put("gridRows", gridRows)
    .put("dugTiles", JSONArray(dugTiles.sorted()))
    .put("completedAtEpochMillis", completedAtEpochMillis ?: JSONObject.NULL)