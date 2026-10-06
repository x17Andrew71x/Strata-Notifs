package com.techfullymade.afterchime.network

import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

fun interface ConnectionFactory {
  fun open(url: URL): HttpURLConnection
}

class AfterchimeApi(
  baseUrl: String,
  private val devFlavor: Boolean,
  private val connectionFactory: ConnectionFactory = ConnectionFactory { url ->
    url.openConnection() as HttpURLConnection
  },
  private val connectTimeoutMillis: Int = 10_000,
  private val readTimeoutMillis: Int = 10_000,
) {
  private val base: URL? = validatedBaseUrl(baseUrl, devFlavor)

  init {
    require(connectTimeoutMillis in 1..15_000)
    require(readTimeoutMillis in 1..15_000)
  }

  fun register(request: RegistrationRequest, idempotencyKey: String): ApiResult<SessionResponse> =
    execute(
      path = "/v1/installations",
      method = "POST",
      body = ApiModels.encodeRegistration(request),
      token = null,
      headers = mapOf("Idempotency-Key" to idempotencyKey),
      successCodes = intArrayOf(200, 201),
      parser = ApiModels::parseSession,
    )

  fun refresh(request: RefreshRequest): ApiResult<SessionResponse> =
    execute(
      path = "/v1/sessions/refresh",
      method = "POST",
      body = ApiModels.encodeRefresh(request),
      token = null,
      headers = emptyMap(),
      successCodes = intArrayOf(200),
      parser = ApiModels::parseSession,
    )

  fun logout(request: LogoutRequest): ApiResult<Unit> =
    execute(
      path = "/v1/sessions",
      method = "DELETE",
      body = ApiModels.encodeLogout(request),
      token = null,
      headers = emptyMap(),
      successCodes = intArrayOf(204),
      parser = { Unit },
    )

  fun updateConsent(
    request: ConsentUpdate,
    bearerToken: String,
    idempotencyKey: String,
  ): ApiResult<ConsentResponse> =
    execute(
      path = "/v1/consents",
      method = "PUT",
      body = ApiModels.encodeConsent(request),
      token = bearerToken,
      headers = mapOf("Idempotency-Key" to idempotencyKey),
      successCodes = intArrayOf(200),
      parser = ApiModels::parseConsent,
    )

  fun submitAnalyticsBatch(body: String, bearerToken: String): ApiResult<AnalyticsBatchResult> =
    try {
      validateBatch(body)
      execute(
        path = "/v1/analytics/events:batch",
        method = "POST",
        body = body,
        token = bearerToken,
        headers = emptyMap(),
        successCodes = intArrayOf(202),
        parser = ApiModels::parseAnalyticsBatch,
      )
    } catch (_: UnsupportedVersionException) {
      ApiResult.failure(ApiFailure.UnsupportedSchemaVersion)
    } catch (_: Exception) {
      ApiResult.failure(ApiFailure.MalformedResponse)
    }

  fun submitDailyAggregate(body: String, bearerToken: String): ApiResult<DailyAggregateResult> =
    try {
      require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
      ApiModels.validateDailyAggregate(JSONObject(body))
      execute(
        path = "/v1/notification-aggregates/daily",
        method = "PUT",
        body = body,
        token = bearerToken,
        headers = emptyMap(),
        successCodes = intArrayOf(202),
        parser = ApiModels::parseDailyAggregateResult,
      )
    } catch (_: Exception) {
      ApiResult.failure(ApiFailure.MalformedResponse)
    }

  private fun validateBatch(text: String) {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
    val root = JSONObject(text)
    require(root.keys().asSequence().toSet() == setOf("events"))
    val events = root.getJSONArray("events")
    require(events.length() in 1..50)
    for (index in 0 until events.length()) {
      ApiModels.validateAnalyticsEnvelope(events.getJSONObject(index).toString())
    }
  }

  private fun <T> execute(
    path: String,
    method: String,
    body: String?,
    token: String?,
    headers: Map<String, String>,
    successCodes: IntArray,
    parser: (String) -> T,
  ): ApiResult<T> {
    val root = base ?: return ApiResult.failure(ApiFailure.InvalidUrl)
    if (token != null && !validHeaderValue(token)) return ApiResult.failure(ApiFailure.MalformedResponse)
    if (headers.any { (name, value) -> name != "Idempotency-Key" || !IDEMPOTENCY.matches(value) }) {
      return ApiResult.failure(ApiFailure.MalformedResponse)
    }
    val connection =
      try {
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
      connection.setRequestProperty("Content-Type", "application/json")
      if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
      headers.forEach(connection::setRequestProperty)
      if (body != null) {
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer -> writer.write(body) }
      }

      val status = connection.responseCode
      if (status in successCodes) {
        if (status == 204) {
          ApiResult.success(parser(""))
        } else {
          val response = readBoundedResponse(connection)
          try {
            ApiResult.success(parser(response))
          } catch (_: UnsupportedVersionException) {
            ApiResult.failure(ApiFailure.UnsupportedSchemaVersion)
          } catch (_: Exception) {
            ApiResult.failure(ApiFailure.MalformedResponse)
          }
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
        require(output.size() + count <= MAX_BODY_BYTES)
        output.write(buffer, 0, count)
      }
    }
    return output.toString(Charsets.UTF_8.name())
  }

  private fun validHeaderValue(value: String): Boolean =
    value.isNotBlank() && value.length <= 4_096 && value.none { character -> character <= ' ' || character == '\u007f' }

  companion object {
    private const val MAX_BODY_BYTES = 128 * 1_024
    private val IDEMPOTENCY =
      Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val LOOPBACK_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]", "::1")

    fun validatedBaseUrl(value: String, devFlavor: Boolean): URL? {
      if (value.isEmpty() || value != value.trim() || value.any { it <= '\u001f' || it == '\u007f' }) return null
      return try {
        val url = URL(value)
        val protocol = url.protocol.lowercase()
        val host = url.host.lowercase()
        val port = url.port
        if (
          protocol !in setOf("http", "https") ||
          host.isBlank() ||
          url.userInfo != null ||
          url.query != null ||
          url.ref != null ||
          url.path !in setOf("", "/") ||
          (port != -1 && port !in 1..65_535)
        ) {
          null
        } else {
          val permitted = protocol == "https" || (protocol == "http" && devFlavor && host in LOOPBACK_HOSTS)
          if (permitted) URL(value.trimEnd('/') + "/") else null
        }
      } catch (_: Exception) {
        null
      }
    }
  }
}
