package com.techfullymade.afterchime

import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import com.techfullymade.afterchime.devfeedback.DevFeedbackApi
import com.techfullymade.afterchime.devfeedback.DevFeedbackItem
import com.techfullymade.afterchime.devfeedback.DevFeedbackState
import com.techfullymade.afterchime.devfeedback.captureDevFeedbackScreenshot
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val BRIDGE_VERSION = 2
private val noPayloadActions = setOf("devFeedback.items", "devFeedback.refresh")
private val payloadActions = setOf(
  "devFeedback.accept",
  "devFeedback.close",
  "devFeedback.submit",
  "devFeedback.amend",
)
private val captureActions = setOf("devFeedback.submit", "devFeedback.amend")

internal object EnabledDevelopmentFeedbackRequestPolicy : DevelopmentFeedbackRequestPolicy {
  override fun acceptsNoPayload(type: String): Boolean = type in noPayloadActions

  override fun acceptsPayload(type: String, payload: JSONObject): Boolean = when (type) {
    "devFeedback.accept", "devFeedback.close" ->
      payload.length() == 1 && validUuid(payload.opt("itemId"))
    "devFeedback.submit" -> validPayload(payload, amendment = false)
    "devFeedback.amend" -> validPayload(payload, amendment = true)
    else -> false
  }
}

internal fun createDevelopmentFeedback(
  dependencies: DevelopmentFeedbackDependencies,
): DevelopmentFeedback = AndroidDevelopmentFeedback(dependencies)

private class AndroidDevelopmentFeedback(
  private val dependencies: DevelopmentFeedbackDependencies,
) : DevelopmentFeedback,
  DevelopmentFeedbackRequestPolicy by EnabledDevelopmentFeedbackRequestPolicy {
  override val enabled: Boolean = true
  private val api = DevFeedbackApi(BuildConfig.API_BASE_URL, devFlavor = true)

  override fun handles(type: String): Boolean = type in noPayloadActions || type in payloadActions

  override fun decorateCapabilities(response: JSONObject): JSONObject = response
    .put("devFeedback", true)
    .put("appVersion", BuildConfig.VERSION_NAME)

  override fun handle(request: BridgeRequest, proxy: JavaScriptReplyProxy): Boolean {
    if (!handles(request.type)) return false
    if (request.type == "devFeedback.refresh") {
      postActionResult(request, dependencies.refreshShell(), proxy)
      return true
    }
    val screenshot = if (request.type in captureActions) {
      captureDevFeedbackScreenshot(dependencies.shell())
    } else {
      null
    }
    if (request.type in captureActions && screenshot == null) {
      postState(request, proxy, error = "capture_failed")
      return true
    }
    val draft = if (request.type in captureActions) {
      try {
        DevFeedbackApi.draftFrom(request.payload ?: return false)
      } catch (_: Exception) {
        postState(request, proxy, error = "request_invalid")
        return true
      }
    } else {
      null
    }
    dependencies.activity.lifecycleScope.launch {
      val token = dependencies.application.developmentFeedbackToken()
      if (token == null) {
        postState(request, proxy, error = "identity_unavailable")
        return@launch
      }
      val result = withContext(Dispatchers.IO) {
        when (request.type) {
          "devFeedback.items" -> api.list(token)
          "devFeedback.submit" -> api.submit(
            draft ?: return@withContext malformed(),
            screenshot ?: return@withContext malformed(),
            token,
          )
          "devFeedback.amend" -> api.amend(
            request.payload?.getString("itemId") ?: "",
            draft ?: return@withContext malformed(),
            screenshot ?: return@withContext malformed(),
            token,
          )
          "devFeedback.accept" -> api.accept(
            request.payload?.getString("itemId") ?: "",
            token,
          )
          "devFeedback.close" -> api.close(
            request.payload?.getString("itemId") ?: "",
            token,
          )
          else -> malformed()
        }
      }
      val state = result.value
      if (state == null) {
        postState(request, proxy, error = result.failure.toFeedbackError())
      } else {
        postState(request, proxy, state = state)
      }
    }
    return true
  }

  private fun malformed(): ApiResult<DevFeedbackState> = ApiResult.failure(ApiFailure.MalformedResponse)

  private fun postState(
    request: BridgeRequest,
    proxy: JavaScriptReplyProxy,
    state: DevFeedbackState? = null,
    error: String? = null,
  ) {
    val operation = when (request.type) {
      "devFeedback.submit" -> "submit"
      "devFeedback.amend" -> "amend"
      "devFeedback.accept" -> "accept"
      "devFeedback.close" -> "close"
      else -> "list"
    }
    proxy.postMessage(
      JSONObject()
        .put("version", BRIDGE_VERSION)
        .put("id", request.id)
        .put("type", "devFeedback.state")
        .put("operation", operation)
        .put("ok", state != null)
        .put("error", error ?: JSONObject.NULL)
        .put("item", state?.item?.toJson() ?: JSONObject.NULL)
        .put("items", JSONArray(state?.items.orEmpty().map(DevFeedbackItem::toJson)))
        .toString(),
    )
  }

  private fun postActionResult(
    request: BridgeRequest,
    ok: Boolean,
    proxy: JavaScriptReplyProxy,
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
}

private fun validPayload(payload: JSONObject, amendment: Boolean): Boolean {
  val keys = payload.keys().asSequence().toSet()
  val expected = buildSet {
    if (amendment) add("itemId")
    addAll(
      setOf(
        "idempotencyKey",
        "title",
        "description",
        "route",
        "shellRevision",
        "appVersion",
        "selection",
      ),
    )
  }
  if (
    keys != expected ||
    !validUuid(payload.opt("idempotencyKey")) ||
    (amendment && !validUuid(payload.opt("itemId")))
  ) return false
  if (
    !validText(payload.opt("title"), 1, 120) ||
    !validText(payload.opt("description"), 1, 4_000) ||
    !validText(payload.opt("route"), 1, 160) ||
    !validText(payload.opt("appVersion"), 1, 64)
  ) return false
  val revision = payload.opt("shellRevision")
  if (revision !is String || !Regex("[0-9a-f]{64}").matches(revision)) return false
  return validSelection(payload.optJSONObject("selection"))
}

private fun validSelection(selection: JSONObject?): Boolean {
  if (
    selection == null ||
    selection.keys().asSequence().toSet() != setOf(
      "kind",
      "rect",
      "viewportWidth",
      "viewportHeight",
      "devId",
      "tag",
      "role",
      "accessibleLabel",
      "visibleLabel",
      "domPath",
    )
  ) return false
  val kind = selection.opt("kind")
  if (kind !is String || kind !in setOf("element", "region")) return false
  if (
    !validInteger(selection.opt("viewportWidth"), 1, 16_384) ||
    !validInteger(selection.opt("viewportHeight"), 1, 16_384) ||
    !validRect(selection.optJSONObject("rect"))
  ) return false
  val fields = listOf(
    "devId" to 96,
    "tag" to 32,
    "role" to 64,
    "accessibleLabel" to 160,
    "visibleLabel" to 160,
    "domPath" to 512,
  )
  if (fields.any { (field, maximum) -> !validOptionalText(selection.opt(field), maximum) }) return false
  return if (kind == "region") {
    fields.all { (field, _) -> selection.getString(field).isEmpty() }
  } else {
    selection.getString("tag").isNotEmpty() && selection.getString("domPath").isNotEmpty()
  }
}

private fun validRect(rect: JSONObject?): Boolean =
  rect != null &&
    rect.keys().asSequence().toSet() == setOf("x", "y", "width", "height") &&
    validInteger(rect.opt("x"), 0, 16_384) &&
    validInteger(rect.opt("y"), 0, 16_384) &&
    validInteger(rect.opt("width"), 1, 16_384) &&
    validInteger(rect.opt("height"), 1, 16_384)

private fun validInteger(value: Any?, minimum: Int, maximum: Int): Boolean =
  value is Int && value in minimum..maximum

private fun validText(value: Any?, minimum: Int, maximum: Int): Boolean =
  value is String &&
    value == value.trim() &&
    value.length in minimum..maximum &&
    value.none { it.code < 32 || it.code == 127 }

private fun validOptionalText(value: Any?, maximum: Int): Boolean =
  value == "" || validText(value, 1, maximum)

private fun validUuid(value: Any?): Boolean =
  value is String &&
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
      .matches(value)

private fun DevFeedbackItem.toJson(): JSONObject = JSONObject()
  .put("id", id)
  .put("queuePosition", queuePosition)
  .put("revision", revision)
  .put("title", title)
  .put("description", description)
  .put("status", status)
  .put("note", note)
  .put("createdAt", createdAt)
  .put("updatedAt", updatedAt)

private fun ApiFailure?.toFeedbackError(): String = when (this) {
  ApiFailure.Unauthorized -> "unauthorized"
  ApiFailure.ConsentInactive -> "forbidden"
  ApiFailure.Conflict -> "conflict"
  ApiFailure.InvalidUrl -> "development_unavailable"
  ApiFailure.Network -> "network"
  ApiFailure.Http -> "server"
  ApiFailure.MalformedResponse, ApiFailure.UnsupportedSchemaVersion, null -> "response_invalid"
}
