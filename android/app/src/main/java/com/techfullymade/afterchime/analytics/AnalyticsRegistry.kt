package com.techfullymade.afterchime.analytics

import com.techfullymade.afterchime.network.ApiModels
import org.json.JSONObject

/** Serializes only closed event variants and rejects any envelope outside shared-v1. */
object AnalyticsRegistry {
  const val SCHEMA_VERSION = 1

  fun envelope(event: AnalyticsEvent, context: AnalyticsContext, schemaVersion: Int = SCHEMA_VERSION): String {
    require(schemaVersion == SCHEMA_VERSION)
    val encoded = event.encode()
    val root = JSONObject()
      .put("event_id", context.eventId)
      .put("event_name", encoded.name)
      .put("schema_version", schemaVersion)
      .put("installation_id", context.installationId)
      .put("occurred_at", context.occurredAt)
      .put("local_date", context.localDate)
      .put("timezone_offset_minutes", context.timezoneOffsetMinutes)
      .put("app_version", context.appVersion)
      .put("version_code", context.versionCode)
      .put("build_channel", context.buildChannel)
      .put("android_api_level", context.androidApiLevel)
      .put("device_class", context.deviceClass)
      .put("locale", context.locale)
      .put("consent_scope_version", context.consentScopeVersion)
      .put("properties", encoded.properties)
    context.userId?.let { root.put("user_id", it) }
    context.sessionId?.let { root.put("session_id", it) }
    context.receivedAt?.let { root.put("received_at", it) }
    context.screenName?.let { root.put("screen_name", it) }
    val result = root.toString()
    ApiModels.validateAnalyticsEnvelope(result)
    return result
  }
}
