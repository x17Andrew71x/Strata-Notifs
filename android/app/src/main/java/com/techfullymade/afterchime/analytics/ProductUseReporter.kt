package com.techfullymade.afterchime.analytics

import com.techfullymade.afterchime.identity.CredentialSession
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.settings.UserPreferences
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

internal fun interface AnalyticsIdentityReader {
  fun currentSession(): IdentityResult<CredentialSession?>
}

internal fun interface AnalyticsContextFactory {
  fun create(
    session: CredentialSession,
    screenName: String?,
  ): AnalyticsContext
}

internal class AndroidAnalyticsContextFactory(
  private val clock: Clock,
  private val timeZone: ZoneId,
  private val appVersion: String,
  private val versionCode: Int,
  private val buildChannel: String,
  private val androidApiLevel: Int,
  private val deviceClass: String,
  private val locale: () -> Locale,
) : AnalyticsContextFactory {
  init {
    require(versionCode > 0)
    require(buildChannel in setOf("dev", "prod"))
    require(androidApiLevel in 26..100)
    require(deviceClass in setOf("phone", "tablet", "foldable"))
  }

  override fun create(
    session: CredentialSession,
    screenName: String?,
  ): AnalyticsContext {
    require(screenName == null || screenName in ALLOWED_SCREENS)
    val instant = clock.instant()
    val local = instant.atZone(timeZone)
    return AnalyticsContext(
      eventId = PLACEHOLDER_EVENT_ID,
      installationId = session.installationId,
      occurredAt = instant.toString(),
      localDate = local.toLocalDate().toString(),
      timezoneOffsetMinutes = local.offset.totalSeconds / 60,
      appVersion = appVersion,
      versionCode = versionCode,
      buildChannel = buildChannel,
      androidApiLevel = androidApiLevel,
      deviceClass = deviceClass,
      locale = analyticsLocale(locale()),
      consentScopeVersion = CONSENT_SCOPE_VERSION,
      screenName = screenName,
    )
  }

  private companion object {
    const val PLACEHOLDER_EVENT_ID = "00000000-0000-4000-8000-000000000000"
    const val CONSENT_SCOPE_VERSION = 1
    val ALLOWED_SCREENS = setOf("onboarding", "privacy", "settings", "today", "museum", "community", "more")
  }
}

internal fun analyticsLocale(locale: Locale): String {
  val language = locale.language.lowercase(Locale.ROOT)
  if (!language.matches(Regex("^[a-z]{2,3}$"))) return "en"
  val country = locale.country.uppercase(Locale.ROOT)
  return if (country.matches(Regex("^[A-Z]{2}$"))) "$language-$country" else language
}

/** Records only while both local opt-ins are active and an anonymous installation already exists. */
internal class ProductUseReporter(
  private val preferences: suspend () -> UserPreferences,
  private val identity: AnalyticsIdentityReader,
  private val recorder: AnalyticsRecorder,
  private val contextFactory: AnalyticsContextFactory,
) {
  suspend fun record(
    event: AnalyticsEvent,
    screenName: String? = null,
  ): AnalyticsRecordResult {
    val currentPreferences = try {
      preferences()
    } catch (_: Exception) {
      return AnalyticsRecordResult.UNAVAILABLE
    }
    if (!currentPreferences.onlineFeaturesEnabled || !currentPreferences.productAnalyticsEnabled) {
      return AnalyticsRecordResult.CONSENT_DISABLED
    }
    val session = when (val result = identity.currentSession()) {
      is IdentityResult.Success -> result.value ?: return AnalyticsRecordResult.UNAVAILABLE
      is IdentityResult.Failure -> return AnalyticsRecordResult.UNAVAILABLE
    }
    val context = try {
      contextFactory.create(session, screenName)
    } catch (_: Exception) {
      return AnalyticsRecordResult.UNAVAILABLE
    }
    return recorder.record(
      event = event,
      context = context,
      consent = AnalyticsConsentPolicy {
        currentPreferences.onlineFeaturesEnabled && currentPreferences.productAnalyticsEnabled
      },
    )
  }
}
