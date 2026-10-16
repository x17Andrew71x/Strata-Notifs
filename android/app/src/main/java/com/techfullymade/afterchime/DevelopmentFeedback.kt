package com.techfullymade.afterchime

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONObject

internal interface DevelopmentFeedbackRequestPolicy {
  fun acceptsNoPayload(type: String): Boolean

  fun acceptsPayload(type: String, payload: JSONObject): Boolean
}

internal interface DevelopmentFeedback : DevelopmentFeedbackRequestPolicy {
  val enabled: Boolean

  fun handles(type: String): Boolean

  fun handle(request: BridgeRequest, proxy: JavaScriptReplyProxy): Boolean

  fun decorateCapabilities(response: JSONObject): JSONObject
}

internal object DisabledDevelopmentFeedback : DevelopmentFeedback {
  override val enabled: Boolean = false

  override fun acceptsNoPayload(type: String): Boolean = false

  override fun acceptsPayload(type: String, payload: JSONObject): Boolean = false

  override fun handles(type: String): Boolean = false

  override fun handle(request: BridgeRequest, proxy: JavaScriptReplyProxy): Boolean = false

  override fun decorateCapabilities(response: JSONObject): JSONObject = response
}

internal data class DevelopmentFeedbackDependencies(
  val activity: MainActivity,
  val application: AfterchimeApplication,
  val shell: () -> WebView,
  val refreshShell: () -> Boolean,
)
