package com.techfullymade.afterchime.network

import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

sealed interface ApiFailure {
  data object InvalidUrl : ApiFailure
  data object UnsupportedSchemaVersion : ApiFailure
  data object MalformedResponse : ApiFailure
  data object Unauthorized : ApiFailure
  data object ConsentInactive : ApiFailure
  data object Conflict : ApiFailure
  data object Network : ApiFailure
  data object Http : ApiFailure
}

data class ApiResult<out T>(val value: T? = null, val failure: ApiFailure? = null) {
  init {
    require((value == null) xor (failure == null))
  }

  companion object {
    fun <T> success(value: T): ApiResult<T> = ApiResult(value = value)

    fun <T> failure(failure: ApiFailure): ApiResult<T> = ApiResult(failure = failure)
  }
}

data class SessionResponse(
  val accessToken: String,
  val expiresInSeconds: Int,
  val installationId: String,
  val refreshToken: String,
  val tokenType: String,
)

data class RefreshRequest(val refreshToken: String)

data class LogoutRequest(val refreshToken: String)

data class RegistrationRequest(
  val appVersion: String,
  val androidApiLevel: Int,
  val deviceClass: String,
)

data class ConsentState(
  val granted: Boolean,
  val scope: String,
  val scopeVersion: String,
)

data class ConsentUpdate(
  val granted: Boolean,
  val scope: String,
  val scopeVersion: String,
)

data class ConsentResponse(val consents: List<ConsentState>)

data class AnalyticsItemResult(val eventId: String, val status: String)

data class AnalyticsBatchResult(val results: List<AnalyticsItemResult>)

data class DailyAggregateResult(
  val localDate: String,
  val revision: Int,
  val status: String,
)

data class ErrorResponse(val error: String)

object ApiModels {
  private val uuidPattern =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
  private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
  private val scopeVersionPattern = Regex("^[A-Za-z0-9._-]{1,32}$")
  private val registrationVersionPattern = Regex("^[A-Za-z0-9.+_-]+$")
  private val semanticVersionPattern =
    Regex("^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
  private val localePattern = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
  private val scopes = setOf("essential_online", "product_analytics", "notification_aggregates")
  private val categories =
    setOf(
      "alarm",
      "call",
      "email",
      "event",
      "message",
      "navigation",
      "other",
      "progress",
      "reminder",
      "social",
      "transport",
      "workout",
    )
  private val eventNames =
    setOf(
      "installation_created",
      "app_opened",
      "session_started",
      "session_ended",
      "app_backgrounded",
      "app_updated",
      "auth_session_created",
      "auth_session_refreshed",
      "online_mode_disabled",
      "onboarding_started",
      "onboarding_step_viewed",
      "onboarding_completed",
      "notification_access_prompted",
      "notification_access_result",
      "analytics_consent_changed",
      "notification_aggregate_consent_changed",
    )
  private val envelopeFields =
    setOf(
      "event_id",
      "event_name",
      "schema_version",
      "user_id",
      "installation_id",
      "session_id",
      "occurred_at",
      "received_at",
      "local_date",
      "timezone_offset_minutes",
      "app_version",
      "version_code",
      "build_channel",
      "android_api_level",
      "device_class",
      "locale",
      "screen_name",
      "consent_scope_version",
      "properties",
    )
  private val requiredEnvelopeFields =
    setOf(
      "event_id",
      "event_name",
      "schema_version",
      "installation_id",
      "occurred_at",
      "local_date",
      "timezone_offset_minutes",
      "app_version",
      "version_code",
      "build_channel",
      "android_api_level",
      "device_class",
      "locale",
      "consent_scope_version",
      "properties",
    )

  fun parseSession(text: String): SessionResponse {
    val root =
      JSONObject(text).strict(
        "accessToken",
        "expiresInSeconds",
        "installationId",
        "refreshToken",
        "tokenType",
      )
    val accessValue = root.string("accessToken").also { require(it.isNotBlank() && it.length <= 4096) }
    val expiresInSeconds = root.integer("expiresInSeconds").also { require(it == 900) }
    val installationId = root.string("installationId").also(::requireUuid)
    val refreshValue = root.string("refreshToken").also { require(tokenPattern.matches(it)) }
    val tokenType = root.string("tokenType").also { require(it == "Bearer") }
    return SessionResponse(accessValue, expiresInSeconds, installationId, refreshValue, tokenType)
  }

  fun parseConsent(text: String): ConsentResponse {
    val root = JSONObject(text).strict("consents")
    val values =
      root.getJSONArray("consents").objects { item ->
        item.strict("granted", "scope", "scopeVersion")
        ConsentState(
          granted = item.boolean("granted"),
          scope = item.string("scope").also { require(it in scopes) },
          scopeVersion = item.string("scopeVersion").also { require(scopeVersionPattern.matches(it)) },
        )
      }
    require(values.map { it.scope }.distinct().size == values.size)
    return ConsentResponse(values)
  }

  fun parseAnalyticsBatch(text: String): AnalyticsBatchResult {
    val root = JSONObject(text).strict("results")
    val results =
      root.getJSONArray("results").objects { item ->
        item.strict("eventId", "status")
        AnalyticsItemResult(
          eventId = item.string("eventId").also(::requireUuid),
          status = item.string("status").also { require(it in setOf("accepted", "duplicate", "rejected")) },
        )
      }
    return AnalyticsBatchResult(results)
  }

  fun parseDailyAggregateResult(text: String): DailyAggregateResult {
    val root = JSONObject(text).strict("result")
    val result = root.objectValue("result").strict("localDate", "revision", "status")
    val localDate = result.string("localDate").also { LocalDate.parse(it) }
    val revision = result.integer("revision").also { require(it > 0) }
    val status = result.string("status").also { require(it in setOf("accepted", "duplicate")) }
    return DailyAggregateResult(localDate, revision, status)
  }

  fun parseError(text: String, allowed: Set<String>): ErrorResponse {
    val root = JSONObject(text).strict("error")
    return ErrorResponse(root.string("error").also { require(it in allowed) })
  }

  fun encodeRefresh(value: RefreshRequest): String =
    JSONObject()
      .put("refreshToken", value.refreshToken.also { require(tokenPattern.matches(it)) })
      .toString()

  fun encodeLogout(value: LogoutRequest): String = encodeRefresh(RefreshRequest(value.refreshToken))

  fun encodeRegistration(value: RegistrationRequest): String {
    require(value.appVersion.length in 1..48 && registrationVersionPattern.matches(value.appVersion))
    require(value.androidApiLevel in 26..100)
    require(value.deviceClass in setOf("phone", "tablet", "foldable"))
    return JSONObject()
      .put("appVersion", value.appVersion)
      .put(
        "device",
        JSONObject()
          .put("androidApiLevel", value.androidApiLevel)
          .put("deviceClass", value.deviceClass),
      ).toString()
  }

  fun encodeConsent(value: ConsentUpdate): String {
    require(value.scope in scopes)
    require(scopeVersionPattern.matches(value.scopeVersion))
    return JSONObject()
      .put("granted", value.granted)
      .put("scope", value.scope)
      .put("scopeVersion", value.scopeVersion)
      .toString()
  }

  fun validateAnalyticsEnvelope(text: String) {
    val event = JSONObject(text)
    val fields = event.keys().asSequence().toSet()
    require(fields.all { it in envelopeFields })
    require(fields.containsAll(requiredEnvelopeFields))

    requireUuid(event.string("event_id"))
    val eventName = event.string("event_name").also { require(it in eventNames) }
    val schemaVersion = event.integer("schema_version")
    if (schemaVersion != 1) throw UnsupportedVersionException()
    event.optionalString("user_id")?.let(::requireUuid)
    requireUuid(event.string("installation_id"))
    event.optionalString("session_id")?.let(::requireUuid)
    Instant.parse(event.string("occurred_at"))
    event.optionalString("received_at")?.let(Instant::parse)
    LocalDate.parse(event.string("local_date"))
    require(event.integer("timezone_offset_minutes") in -840..840)
    requireSemanticVersion(event.string("app_version"))
    require(event.integer("version_code") > 0)
    require(event.string("build_channel") in setOf("dev", "prod"))
    require(event.integer("android_api_level") in 26..100)
    require(event.string("device_class") in setOf("phone", "tablet", "foldable"))
    require(event.string("locale").let { it.length <= 16 && localePattern.matches(it) })
    event.optionalString("screen_name")?.let {
      require(it in setOf("onboarding", "privacy", "settings", "today", "museum", "community", "more"))
    }
    require(event.integer("consent_scope_version") in 1..1000)
    validateEventProperties(eventName, event.objectValue("properties"))
  }

  fun validateDailyAggregate(json: JSONObject) {
    json.strict(
      "category_counts",
      "consent_scope_version",
      "eligible_count",
      "game_pressure",
      "hourly_buckets",
      "local_date",
      "observation_completeness",
      "revision",
      "rules_version",
      "timezone_offset_minutes",
    )
    val categoryCounts = json.objectValue("category_counts")
    val categoryNames = categoryCounts.keys().asSequence().toSet()
    require(categoryNames.all { it in categories })
    val categoryTotal = categoryNames.sumOf { categoryCounts.integer(it).also { count -> require(count in 0..100_000) } }
    val eligibleCount = json.integer("eligible_count").also { require(it in 0..100_000) }
    require(categoryTotal == eligibleCount)

    require(json.integer("game_pressure") in 0..100)
    require(json.integer("observation_completeness") in 0..100)
    require(json.integer("revision") > 0)
    require(json.integer("rules_version") > 0)
    require(json.integer("timezone_offset_minutes") in -840..840)
    require(json.string("consent_scope_version").let { it.isNotBlank() && it.length <= 32 })
    LocalDate.parse(json.string("local_date"))

    val hourlyBuckets = json.getJSONArray("hourly_buckets")
    require(hourlyBuckets.length() <= 24)
    val hours = mutableSetOf<Int>()
    var hourlyTotal = 0
    for (index in 0 until hourlyBuckets.length()) {
      val bucket = hourlyBuckets.getJSONObject(index).strict("count", "hour")
      val hour = bucket.integer("hour").also { require(it in 0..23 && hours.add(it)) }
      require(hour in 0..23)
      hourlyTotal += bucket.integer("count").also { require(it in 0..100_000) }
    }
    require(hourlyTotal == eligibleCount)
  }

  private fun validateEventProperties(eventName: String, properties: JSONObject) {
    when (eventName) {
      "installation_created" -> {
        properties.strict("entry_point")
        require(properties.string("entry_point") in setOf("first_run", "online_mode_enabled"))
      }
      "app_opened" -> {
        properties.strict("launch_type")
        require(properties.string("launch_type") in setOf("cold", "warm"))
      }
      "session_started" -> {
        properties.strict("entry_point")
        require(properties.string("entry_point") in setOf("cold_start", "foreground_return"))
      }
      "session_ended", "app_backgrounded" -> {
        properties.strict("duration_seconds")
        require(properties.integer("duration_seconds") in 0..86_400)
      }
      "app_updated" -> {
        properties.strict("previous_app_version")
        requireSemanticVersion(properties.string("previous_app_version"))
      }
      "auth_session_created" -> {
        properties.strict("auth_method")
        require(properties.string("auth_method") == "anonymous")
      }
      "auth_session_refreshed" -> {
        properties.strict("outcome")
        require(properties.string("outcome") == "rotated")
      }
      "online_mode_disabled" -> {
        properties.strict("reason")
        require(properties.string("reason") == "user_request")
      }
      "onboarding_started" -> {
        properties.strict("entry_point")
        require(properties.string("entry_point") in setOf("first_run", "resume"))
      }
      "onboarding_step_viewed" -> {
        properties.strict("step", "step_index")
        require(
          properties.string("step") in
            setOf("welcome", "privacy", "notification_access", "analytics_consent", "ready"),
        )
        require(properties.integer("step_index") in 1..5)
      }
      "onboarding_completed" -> {
        properties.strict("duration_seconds")
        require(properties.integer("duration_seconds") in 0..3_600)
      }
      "notification_access_prompted" -> {
        properties.strict("entry_point")
        require(properties.string("entry_point") in setOf("onboarding", "settings"))
      }
      "notification_access_result" -> {
        properties.strict("result")
        require(properties.string("result") in setOf("granted", "denied", "unavailable"))
      }
      "analytics_consent_changed", "notification_aggregate_consent_changed" -> {
        properties.strict("enabled", "consent_version")
        properties.boolean("enabled")
        require(properties.integer("consent_version") in 1..1000)
      }
      else -> error("unsupported event")
    }
  }

  private fun requireSemanticVersion(value: String) {
    require(value.length <= 48 && semanticVersionPattern.matches(value))
  }

  private fun requireUuid(value: String) {
    require(uuidPattern.matches(value))
  }

  private fun JSONObject.strict(vararg names: String): JSONObject {
    require(keys().asSequence().toSet() == names.toSet())
    return this
  }

  private fun JSONObject.string(name: String): String {
    val value = get(name)
    require(value is String)
    return value
  }

  private fun JSONObject.optionalString(name: String): String? = if (has(name)) string(name) else null

  private fun JSONObject.boolean(name: String): Boolean {
    val value = get(name)
    require(value is Boolean)
    return value
  }

  private fun JSONObject.integer(name: String): Int {
    val value = get(name)
    require(value is Number)
    val double = value.toDouble()
    require(double.isFinite() && double == kotlin.math.floor(double))
    require(double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
    return double.toInt()
  }

  private fun JSONObject.objectValue(name: String): JSONObject {
    val value = get(name)
    require(value is JSONObject)
    return value
  }

  private inline fun <T> JSONArray.objects(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> block(getJSONObject(index)) }
}

class UnsupportedVersionException : IllegalArgumentException()
