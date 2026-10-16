package com.techfullymade.afterchime.devfeedback


import com.techfullymade.afterchime.network.AfterchimeApi
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.ConnectionFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal data class DevFeedbackDraft(
  val idempotencyKey: String,
  val title: String,
  val description: String,
  val route: String,
  val shellRevision: String,
  val appVersion: String,
  val selection: JSONObject,
)

internal data class DevFeedbackItem(
  val id: String,
  val queuePosition: Long,
  val revision: Int,
  val title: String,
  val description: String,
  val status: String,
  val note: String,
  val createdAt: String,
  val updatedAt: String,
)

internal data class DevFeedbackState(
  val item: DevFeedbackItem?,
  val items: List<DevFeedbackItem>,
)

internal class DevFeedbackApi(
  baseUrl: String,
  devFlavor: Boolean,
  private val connectionFactory: ConnectionFactory = ConnectionFactory { url ->
    url.openConnection() as HttpURLConnection
  },
  private val connectTimeoutMillis: Int = 10_000,
  private val readTimeoutMillis: Int = 15_000,
) {
  private val base: URL? = AfterchimeApi.validatedBaseUrl(baseUrl, devFlavor)

  init {
    require(connectTimeoutMillis in 1..15_000)
    require(readTimeoutMillis in 1..15_000)
  }

  fun list(bearerToken: String): ApiResult<DevFeedbackState> =
    execute("/v1/dev-feedback/items", "GET", null, bearerToken, null, intArrayOf(200))

  fun submit(
    draft: DevFeedbackDraft,
    screenshot: DevFeedbackScreenshot,
    bearerToken: String,
  ): ApiResult<DevFeedbackState> =
    execute(
      "/v1/dev-feedback/items",
      "POST",
      encodeDraft(draft, screenshot),
      bearerToken,
      draft.idempotencyKey,
      intArrayOf(200, 201),
    )

  fun amend(
    itemId: String,
    draft: DevFeedbackDraft,
    screenshot: DevFeedbackScreenshot,
    bearerToken: String,
  ): ApiResult<DevFeedbackState> {
    if (!UUID.matches(itemId)) return ApiResult.failure(ApiFailure.MalformedResponse)
    return execute(
      "/v1/dev-feedback/items/$itemId/amendments",
      "POST",
      encodeDraft(draft, screenshot),
      bearerToken,
      draft.idempotencyKey,
      intArrayOf(200, 201),
    )
  }

  fun accept(itemId: String, bearerToken: String): ApiResult<DevFeedbackState> {
    if (!UUID.matches(itemId)) return ApiResult.failure(ApiFailure.MalformedResponse)
    return execute(
      "/v1/dev-feedback/items/$itemId/accept",
      "POST",
      "{}",
      bearerToken,
      null,
      intArrayOf(200),
    )
  }

  private fun execute(
    path: String,
    method: String,
    body: String?,
    bearerToken: String,
    idempotencyKey: String?,
    successCodes: IntArray,
  ): ApiResult<DevFeedbackState> {
    val root = base ?: return ApiResult.failure(ApiFailure.InvalidUrl)
    if (!validHeaderValue(bearerToken)) return ApiResult.failure(ApiFailure.MalformedResponse)
    if (idempotencyKey != null && !UUID.matches(idempotencyKey)) {
      return ApiResult.failure(ApiFailure.MalformedResponse)
    }
    if (body != null && body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
      return ApiResult.failure(ApiFailure.MalformedResponse)
    }
    val connection = try {
      connectionFactory.open(URL(root, path))
    } catch (_: Exception) {
      return ApiResult.failure(ApiFailure.Network)
    }
    return try {
      connection.connectTimeout = connectTimeoutMillis
      connection.readTimeout = readTimeoutMillis
      connection.instanceFollowRedirects = false
      connection.requestMethod = method
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $bearerToken")
      if (body != null) connection.setRequestProperty("Content-Type", "application/json")
      if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey)
      if (body != null) {
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer -> writer.write(body) }
      }
      val status = connection.responseCode
      if (status in successCodes) {
        try {
          ApiResult.success(parseState(readBoundedResponse(connection)))
        } catch (_: Exception) {
          ApiResult.failure(ApiFailure.MalformedResponse)
        }
      } else {
        when (status) {
          401 -> ApiResult.failure(ApiFailure.Unauthorized)
          403 -> ApiResult.failure(ApiFailure.ConsentInactive)
          409 -> ApiResult.failure(ApiFailure.Conflict)
          else -> ApiResult.failure(ApiFailure.Http)
        }
      }
    } catch (_: Exception) {
      ApiResult.failure(ApiFailure.Network)
    } finally {
      connection.disconnect()
    }
  }

  private fun readBoundedResponse(connection: HttpURLConnection): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    connection.inputStream.use { input ->
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_RESPONSE_BYTES)
        output.write(buffer, 0, count)
      }
    }
    return output.toString(Charsets.UTF_8.name())
  }

  private fun validHeaderValue(value: String): Boolean =
    value.isNotBlank() && value.length <= 4_096 && value.none { it <= ' ' || it == '\u007f' }

  internal companion object {
    private const val MAX_REQUEST_BYTES = 1_100 * 1_024
    private const val MAX_RESPONSE_BYTES = 256 * 1_024
    private val UUID =
      Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val COMMIT = Regex("^[0-9a-f]{64}$")
    private val STATUSES = setOf("queued", "in_progress", "blocked", "ready_for_qa", "accepted")

    fun draftFrom(payload: JSONObject): DevFeedbackDraft = DevFeedbackDraft(
      idempotencyKey = payload.getString("idempotencyKey"),
      title = payload.getString("title"),
      description = payload.getString("description"),
      route = payload.getString("route"),
      shellRevision = payload.getString("shellRevision"),
      appVersion = payload.getString("appVersion"),
      selection = JSONObject(payload.getJSONObject("selection").toString()),
    )

    fun encodeDraft(draft: DevFeedbackDraft, screenshot: DevFeedbackScreenshot): String {
      require(UUID.matches(draft.idempotencyKey))
      require(COMMIT.matches(draft.shellRevision))
      return JSONObject()
        .put("title", draft.title)
        .put("description", draft.description)
        .put(
          "context",
          JSONObject()
            .put("route", draft.route)
            .put("shellRevision", draft.shellRevision)
            .put("appVersion", draft.appVersion)
            .put("selection", draft.selection),
        )
        .put(
          "screenshot",
          JSONObject()
            .put("mediaType", screenshot.mediaType)
            .put("contentBase64", Base64.getEncoder().encodeToString(screenshot.bytes))
            .put("sha256", screenshot.sha256)
            .put("width", screenshot.width)
            .put("height", screenshot.height),
        )
        .toString()
    }

    fun parseState(text: String): DevFeedbackState {
      val root = JSONObject(text).strict("item", "items")
      val item = root.opt("item").let { value ->
        if (value == null || value == JSONObject.NULL) null else parseItem(value as JSONObject)
      }
      val array = root.getJSONArray("items")
      require(array.length() <= 200)
      val items = (0 until array.length()).map { index -> parseItem(array.getJSONObject(index)) }
      require(items.zipWithNext().all { (first, second) -> first.queuePosition < second.queuePosition })
      return DevFeedbackState(item, items)
    }

    private fun parseItem(value: JSONObject): DevFeedbackItem {
      value.strict(
        "id",
        "queuePosition",
        "revision",
        "title",
        "description",
        "status",
        "note",
        "createdAt",
        "updatedAt",
      )
      val id = value.getString("id").also { require(UUID.matches(it)) }
      val queuePosition = value.getLong("queuePosition").also { require(it >= 1) }
      val revision = value.getInt("revision").also { require(it in 1..10_000) }
      val title = value.getString("title").also { require(it == it.trim() && it.length in 1..120) }
      val description = value.getString("description").also { require(it == it.trim() && it.length in 1..4_000) }
      val status = value.getString("status").also { require(it in STATUSES) }
      val note = value.getString("note").also { require(it.length <= 600) }
      val createdAt = value.getString("createdAt").also { Instant.parse(it) }
      val updatedAt = value.getString("updatedAt").also { Instant.parse(it) }
      return DevFeedbackItem(id, queuePosition, revision, title, description, status, note, createdAt, updatedAt)
    }

    private fun JSONObject.strict(vararg keys: String): JSONObject {
      require(this.keys().asSequence().toSet() == keys.toSet())
      return this
    }
  }
}
