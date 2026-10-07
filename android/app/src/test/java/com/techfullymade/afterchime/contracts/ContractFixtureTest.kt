package com.techfullymade.afterchime.contracts

import com.techfullymade.afterchime.analytics.AnalyticsEvent
import com.techfullymade.afterchime.analytics.encode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractFixtureTest {
  private val expectedEventNames = setOf(
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
    "screen_viewed",
    "tab_selected",
    "help_opened",
    "setting_changed",
    "share_started",
    "share_completed",
    "share_failed",
    "formation_viewed",
    "day_sealed",
    "specimen_generated",
    "specimen_reveal_started",
    "specimen_revealed",
    "specimen_locked",
    "specimen_unlocked",
    "weekly_diorama_created",
    "museum_viewed",
    "museum_filter_changed",
    "specimen_detail_viewed",
    "combine_previewed",
    "combine_completed",
    "combine_cancelled",
    "community_art_viewed",
    "world_selector_opened",
    "world_previewed",
    "store_viewed",
    "product_viewed",
    "checkout_started",
    "checkout_result",
    "entitlements_restored",
    "owned_world_applied",
  )

  @Test
  fun `valid shared fixtures conform to the closed v1 contract`() {
    val fixtures = fixtures("valid")

    assertEquals(expectedEventNames, fixtures.map { JSONObject(it.readText()).getString("event_name") }.toSet())
    assertEquals(expectedEventNames.size, fixtures.size)
    fixtures.forEach { fixture ->
      assertTrue("valid fixture rejected: ${fixture.getName()}", isValid(fixture))
    }
  }

  @Test
  fun `invalid shared fixtures are rejected by the closed v1 contract`() {
    val fixtures = fixtures("invalid")

    assertEquals(4, fixtures.size)
    fixtures.forEach { fixture ->
      assertTrue("invalid fixture accepted: ${fixture.getName()}", !isValid(fixture))
    }
  }

  @Test
  fun `museum commerce and world events encode canonical empty facts`() {
    val events =
      listOf(
        AnalyticsEvent.MuseumViewed to "museum_viewed",
        AnalyticsEvent.MuseumFilterChanged to "museum_filter_changed",
        AnalyticsEvent.SpecimenDetailViewed to "specimen_detail_viewed",
        AnalyticsEvent.CombinePreviewed to "combine_previewed",
        AnalyticsEvent.CombineCompleted to "combine_completed",
        AnalyticsEvent.CombineCancelled to "combine_cancelled",
        AnalyticsEvent.CommunityArtViewed to "community_art_viewed",
        AnalyticsEvent.WorldSelectorOpened to "world_selector_opened",
        AnalyticsEvent.WorldPreviewed to "world_previewed",
        AnalyticsEvent.StoreViewed to "store_viewed",
        AnalyticsEvent.ProductViewed to "product_viewed",
        AnalyticsEvent.CheckoutStarted to "checkout_started",
        AnalyticsEvent.CheckoutResult to "checkout_result",
        AnalyticsEvent.EntitlementsRestored to "entitlements_restored",
        AnalyticsEvent.OwnedWorldApplied to "owned_world_applied",
      )

    assertEquals(15, events.size)
    events.forEach { (event, expectedName) ->
      val encoded = event.encode()
      assertEquals(expectedName, encoded.name)
      assertEquals(emptySet<String>(), encoded.properties.keysAsSet())
    }
  }

  private fun fixtures(kind: String): List<File> {
    val workingDirectory = System.getProperty("user.dir") ?: throw AssertionError("working directory is unavailable")
    var candidate = File(workingDirectory).getCanonicalFile()
    while (true) {
      val directory = File(candidate, "contracts/fixtures/$kind")
      if (directory.isDirectory()) {
        return directory.listFiles { file -> file.isFile() && file.getName().endsWith(".json") }
          ?.sortedBy { file -> file.getName() }
          ?: throw AssertionError("unable to list shared $kind fixtures")
      }
      candidate = candidate.getParentFile() ?: throw AssertionError("shared fixture directory was not found")
    }
  }

  private fun isValid(fixture: File): Boolean = runCatching {
    validate(JSONObject(fixture.readText()))
  }.isSuccess

  private fun validate(event: JSONObject) {
    val allowedEnvelopeFields = setOf(
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
    require(event.keysAsSet().all(allowedEnvelopeFields::contains))

    uuid(event.string("event_id"))
    val eventName = event.string("event_name")
    require(eventName in expectedEventNames)
    require(event.integer("schema_version") == 1)
    uuid(event.string("installation_id"))
    event.optionalString("user_id")?.let(::uuid)
    event.optionalString("session_id")?.let(::uuid)
    instant(event.string("occurred_at"))
    event.optionalString("received_at")?.let(::instant)
    LocalDate.parse(event.string("local_date"))
    require(event.integer("timezone_offset_minutes") in -840..840)
    require(semver.matches(event.string("app_version")) && event.string("app_version").length <= 48)
    require(event.integer("version_code") in 1..Int.MAX_VALUE)
    require(event.string("build_channel") in setOf("dev", "prod"))
    require(event.integer("android_api_level") in 26..100)
    require(event.string("device_class") in setOf("phone", "tablet", "foldable"))
    require(locale.matches(event.string("locale")) && event.string("locale").length <= 16)
    event.optionalString("screen_name")?.let { screen ->
      require(screen in setOf("onboarding", "privacy", "settings", "today", "museum", "community", "more"))
    }
    require(event.integer("consent_scope_version") in 1..1000)

    validateProperties(eventName, event.objectValue("properties"))
  }

  private fun validateProperties(eventName: String, properties: JSONObject) {
    when (eventName) {
      "installation_created" -> {
        properties.exactly("entry_point")
        require(properties.string("entry_point") in setOf("first_run", "online_mode_enabled"))
      }
      "app_opened" -> {
        properties.exactly("launch_type")
        require(properties.string("launch_type") in setOf("cold", "warm"))
      }
      "session_started" -> {
        properties.exactly("entry_point")
        require(properties.string("entry_point") in setOf("cold_start", "foreground_return"))
      }
      "session_ended", "app_backgrounded" -> {
        properties.exactly("duration_seconds")
        require(properties.integer("duration_seconds") in 0..86400)
      }
      "app_updated" -> {
        properties.exactly("previous_app_version")
        val version = properties.string("previous_app_version")
        require(semver.matches(version) && version.length <= 48)
      }
      "auth_session_created" -> {
        properties.exactly("auth_method")
        require(properties.string("auth_method") == "anonymous")
      }
      "auth_session_refreshed" -> {
        properties.exactly("outcome")
        require(properties.string("outcome") == "rotated")
      }
      "online_mode_disabled" -> {
        properties.exactly("reason")
        require(properties.string("reason") == "user_request")
      }
      "onboarding_started" -> {
        properties.exactly("entry_point")
        require(properties.string("entry_point") in setOf("first_run", "resume"))
      }
      "onboarding_step_viewed" -> {
        properties.exactly("step", "step_index")
        require(properties.string("step") in setOf("welcome", "privacy", "notification_access", "analytics_consent", "ready"))
        require(properties.integer("step_index") in 1..5)
      }
      "onboarding_completed" -> {
        properties.exactly("duration_seconds")
        require(properties.integer("duration_seconds") in 0..3600)
      }
      "notification_access_prompted" -> {
        properties.exactly("entry_point")
        require(properties.string("entry_point") in setOf("onboarding", "settings"))
      }
      "notification_access_result" -> {
        properties.exactly("result")
        require(properties.string("result") in setOf("granted", "denied", "unavailable"))
      }
      "analytics_consent_changed", "notification_aggregate_consent_changed" -> {
        properties.exactly("enabled", "consent_version")
        properties.boolean("enabled")
        require(properties.integer("consent_version") in 1..1000)
      }
      "screen_viewed", "tab_selected", "help_opened", "setting_changed",
      "share_started", "share_completed", "share_failed", "formation_viewed",
      "day_sealed", "specimen_generated", "specimen_reveal_started",
      "specimen_revealed", "specimen_locked", "specimen_unlocked",
      "weekly_diorama_created", "museum_viewed", "museum_filter_changed",
      "specimen_detail_viewed", "combine_previewed", "combine_completed",
      "combine_cancelled", "community_art_viewed", "world_selector_opened",
      "world_previewed", "store_viewed", "product_viewed", "checkout_started",
      "checkout_result", "entitlements_restored", "owned_world_applied" -> properties.exactly()
    }
  }

  private fun JSONObject.exactly(vararg names: String) {
    require(keysAsSet() == names.toSet())
  }

  private fun JSONObject.keysAsSet(): Set<String> {
    val keys = keys()
    val names = mutableSetOf<String>()
    while (keys.hasNext()) {
      names += keys.next()
    }
    return names
  }

  private fun JSONObject.string(name: String): String {
    val value = get(name)
    require(value is String)
    return value
  }

  private fun JSONObject.optionalString(name: String): String? = if (has(name)) string(name) else null

  private fun JSONObject.integer(name: String): Int {
    val value = get(name)
    require(value is Number)
    val double = value.toDouble()
    require(double.isFinite() && double == kotlin.math.floor(double))
    require(double in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
    return double.toInt()
  }

  private fun JSONObject.boolean(name: String): Boolean {
    val value = get(name)
    require(value is Boolean)
    return value
  }

  private fun JSONObject.objectValue(name: String): JSONObject {
    val value = get(name)
    require(value is JSONObject)
    return value
  }

  private fun uuid(value: String) {
    UUID.fromString(value)
  }

  private fun instant(value: String) {
    Instant.parse(value)
  }

  private companion object {
    val semver = Regex("^(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    val locale = Regex("^[a-z]{2,3}(?:-[A-Z]{2})?$")
  }
}
