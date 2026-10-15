package com.techfullymade.afterchime.analytics

import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import com.techfullymade.afterchime.identity.CredentialSession
import com.techfullymade.afterchime.identity.IdentityFailure
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.settings.UserPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProductUseReporterTest {
  @Test
  fun `disabled preferences do not read identity or write an event`() = runBlocking {
    var identityReads = 0
    var writes = 0
    val reporter = ProductUseReporter(
      preferences = { UserPreferences() },
      identity = AnalyticsIdentityReader { identityReads += 1; IdentityResult.Success(SESSION) },
      recorder = AnalyticsRecorder(
        writer = AnalyticsOutboxWriter { writes += 1; 1L },
        cipher = AnalyticsEnvelopeCipher { it.copyOf() },
      ),
      contextFactory = AnalyticsContextFactory { _, _ -> CONTEXT },
    )

    assertEquals(
      AnalyticsRecordResult.CONSENT_DISABLED,
      reporter.record(AnalyticsEvent.AppOpened(LaunchType.COLD), "today"),
    )
    assertEquals(0, identityReads)
    assertEquals(0, writes)
  }

  @Test
  fun `enabled preferences record a validated encrypted outbox envelope for existing installation`() = runBlocking {
    var captured: AnalyticsOutboxEntity? = null
    val factory = AndroidAnalyticsContextFactory(
      clock = Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"), ZoneOffset.UTC),
      timeZone = ZoneId.of("America/New_York"),
      appVersion = "0.1.8-dev-debug",
      versionCode = 1008,
      buildChannel = "dev",
      androidApiLevel = 35,
      deviceClass = "phone",
      locale = { Locale.US },
    )
    val reporter = ProductUseReporter(
      preferences = { UserPreferences(onlineFeaturesEnabled = true, productAnalyticsEnabled = true) },
      identity = AnalyticsIdentityReader { IdentityResult.Success(SESSION) },
      recorder = AnalyticsRecorder(
        writer = AnalyticsOutboxWriter { captured = it; 1L },
        cipher = AnalyticsEnvelopeCipher { plaintext -> plaintext.copyOf() },
        nowMillis = { 7L },
      ),
      contextFactory = factory,
    )

    assertEquals(
      AnalyticsRecordResult.RECORDED,
      reporter.record(AnalyticsEvent.SettingChanged, "settings"),
    )
    val entity = requireNotNull(captured)
    val event = JSONObject(entity.ciphertext.toString(Charsets.UTF_8))
    assertEquals(entity.eventId, event.getString("event_id"))
    assertEquals(SESSION.installationId, event.getString("installation_id"))
    assertEquals("setting_changed", event.getString("event_name"))
    assertEquals("2026-10-14", event.getString("local_date"))
    assertEquals(-240, event.getInt("timezone_offset_minutes"))
    assertEquals("en-US", event.getString("locale"))
    assertEquals("settings", event.getString("screen_name"))
    assertEquals(1, event.getInt("consent_scope_version"))
    assertFalse(event.has("user_id"))
    assertFalse(entity.ciphertext.toString(Charsets.UTF_8).contains("notification"))
  }

  @Test
  fun `missing or unavailable identity records nothing`() = runBlocking {
    var writes = 0
    val missing = reporter(AnalyticsIdentityReader { IdentityResult.Success(null) }) { writes += 1 }
    val unavailable = reporter(
      AnalyticsIdentityReader {
        IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Network))
      },
    ) { writes += 1 }

    assertEquals(AnalyticsRecordResult.UNAVAILABLE, missing.record(AnalyticsEvent.HelpOpened, "more"))
    assertEquals(AnalyticsRecordResult.UNAVAILABLE, unavailable.record(AnalyticsEvent.HelpOpened, "more"))
    assertEquals(0, writes)
  }

  @Test
  fun `context factory normalizes locale and rejects unknown screen`() = runBlocking {
    assertEquals("en-US", analyticsLocale(Locale.US))
    assertEquals("zh-TW", analyticsLocale(Locale.forLanguageTag("zh-Hant-TW")))
    assertEquals("en", analyticsLocale(Locale.ROOT))

    val reporter = ProductUseReporter(
      preferences = { UserPreferences(onlineFeaturesEnabled = true, productAnalyticsEnabled = true) },
      identity = AnalyticsIdentityReader { IdentityResult.Success(SESSION) },
      recorder = AnalyticsRecorder(
        writer = AnalyticsOutboxWriter { 1L },
        cipher = AnalyticsEnvelopeCipher { it.copyOf() },
      ),
      contextFactory = AndroidAnalyticsContextFactory(
        clock = Clock.systemUTC(),
        timeZone = ZoneOffset.UTC,
        appVersion = "0.1.8-dev-debug",
        versionCode = 1008,
        buildChannel = "dev",
        androidApiLevel = 35,
        deviceClass = "phone",
        locale = { Locale.US },
      ),
    )
    assertEquals(
      AnalyticsRecordResult.UNAVAILABLE,
      reporter.record(AnalyticsEvent.ScreenViewed, "private-notification-screen"),
    )
  }

  private fun reporter(
    identity: AnalyticsIdentityReader,
    onWrite: () -> Unit,
  ) = ProductUseReporter(
    preferences = { UserPreferences(onlineFeaturesEnabled = true, productAnalyticsEnabled = true) },
    identity = identity,
    recorder = AnalyticsRecorder(
      writer = AnalyticsOutboxWriter { onWrite(); 1L },
      cipher = AnalyticsEnvelopeCipher { it.copyOf() },
    ),
    contextFactory = AnalyticsContextFactory { _, _ -> CONTEXT },
  )

  private companion object {
    val SESSION = CredentialSession(
      installationId = "123e4567-e89b-42d3-a456-426614174010",
      accessToken = "access-token",
      refreshToken = "x".repeat(43),
      expiresAtEpochMillis = Long.MAX_VALUE,
    )
    val CONTEXT = AnalyticsContext(
      eventId = "00000000-0000-4000-8000-000000000000",
      installationId = SESSION.installationId,
      occurredAt = "2026-10-15T02:00:00Z",
      localDate = "2026-10-14",
      timezoneOffsetMinutes = -240,
      appVersion = "0.1.8-dev-debug",
      versionCode = 1008,
      buildChannel = "dev",
      androidApiLevel = 35,
      deviceClass = "phone",
      locale = "en-US",
      consentScopeVersion = 1,
    )
  }
}
