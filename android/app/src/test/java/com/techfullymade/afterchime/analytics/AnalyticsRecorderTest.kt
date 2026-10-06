package com.techfullymade.afterchime.analytics

import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import com.techfullymade.afterchime.network.ApiModels
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsRecorderTest {
  @Test
  fun `each closed event variant validates against shared v1`() {
    val names = events.mapIndexed { index, event ->
      val text = AnalyticsRegistry.envelope(event, context.copy(eventId = id(index)))
      ApiModels.validateAnalyticsEnvelope(text)
      assertEquals(1, JSONObject(text).getInt("schema_version"))
      JSONObject(text).getString("event_name")
    }.toSet()

    assertEquals(EXPECTED_EVENT_NAMES.size, events.size)
    assertEquals(EXPECTED_EVENT_NAMES, names)
  }

  @Test
  fun `unsupported schema version fails closed`() {
    assertThrows(IllegalArgumentException::class.java) {
      AnalyticsRegistry.envelope(events.first(), context, schemaVersion = 2)
    }
  }

  @Test
  fun `disabled consent and invalid event do not write`() = runBlocking {
    var writes = 0
    val recorder = AnalyticsRecorder(AnalyticsOutboxWriter { writes++; 1L })
    assertEquals(AnalyticsRecordResult.CONSENT_DISABLED, recorder.record(events.first(), context, AnalyticsConsentPolicy { false }))
    assertEquals(AnalyticsRecordResult.UNAVAILABLE, recorder.record(AnalyticsEvent.SessionEnded(-1), context, AnalyticsConsentPolicy { true }))
    assertEquals(0, writes)
  }

  @Test
  fun `recorder writes encrypted blob with random identity and safe failures`() = runBlocking {
    var captured: AnalyticsOutboxEntity? = null
    val writer = AnalyticsOutboxWriter { captured = it; 1L }
    val cipher = AnalyticsEnvelopeCipher { bytes -> byteArrayOf(9, 8, 7) + bytes.reversedArray() }
    val recorder = AnalyticsRecorder(writer, cipher, { 123L }, { id(31) })
    assertEquals(AnalyticsRecordResult.RECORDED, recorder.record(events[1], context, AnalyticsConsentPolicy { true }))
    assertEquals(id(31), captured?.eventId)
    assertEquals(123L, captured?.createdAtEpochMillis)
    assertFalse(String(captured!!.ciphertext).contains("app_opened"))
    val broken = AnalyticsRecorder(AnalyticsOutboxWriter { error("failure") }, cipher)
    assertEquals(AnalyticsRecordResult.UNAVAILABLE, broken.record(events[1], context, AnalyticsConsentPolicy { true }))
    val keyFailure = AnalyticsRecorder(writer, AnalyticsEnvelopeCipher { throw IllegalStateException() })
    assertEquals(AnalyticsRecordResult.UNAVAILABLE, keyFailure.record(events[1], context, AnalyticsConsentPolicy { true }))
    assertTrue(captured!!.ciphertext.isNotEmpty())
  }

  private val context = AnalyticsContext(
    eventId = id(0), installationId = "123e4567-e89b-42d3-a456-426614174000",
    occurredAt = "2026-01-01T00:00:00Z", localDate = "2026-01-01", timezoneOffsetMinutes = 0,
    appVersion = "1.0.0", versionCode = 1, buildChannel = "dev", androidApiLevel = 35,
    deviceClass = "phone", locale = "en-US", consentScopeVersion = 1,
  )

  private val events = listOf(
    AnalyticsEvent.InstallationCreated(InstallationEntryPoint.FIRST_RUN), AnalyticsEvent.AppOpened(LaunchType.COLD),
    AnalyticsEvent.SessionStarted(SessionEntryPoint.COLD_START), AnalyticsEvent.SessionEnded(5),
    AnalyticsEvent.AppBackgrounded(5), AnalyticsEvent.AppUpdated("1.0.0"), AnalyticsEvent.AuthSessionCreated,
    AnalyticsEvent.AuthSessionRefreshed, AnalyticsEvent.OnlineModeDisabled,
    AnalyticsEvent.OnboardingStarted(OnboardingEntryPoint.FIRST_RUN),
    AnalyticsEvent.OnboardingStepViewed(OnboardingStep.NOTIFICATION_ACCESS, 3), AnalyticsEvent.OnboardingCompleted(8),
    AnalyticsEvent.NotificationAccessPrompted(PromptEntryPoint.SETTINGS),
    AnalyticsEvent.NotificationAccessResult(AccessResult.GRANTED), AnalyticsEvent.AnalyticsConsentChanged(true, 1),
    AnalyticsEvent.NotificationAggregateConsentChanged(false, 1),
  )

  private companion object {
    val EXPECTED_EVENT_NAMES = setOf(
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
  }

  private fun id(n: Int) = "123e4567-e89b-42d3-a456-${n.toString().padStart(12, '0')}"
}
