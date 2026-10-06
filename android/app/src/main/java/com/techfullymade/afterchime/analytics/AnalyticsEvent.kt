package com.techfullymade.afterchime.analytics

import org.json.JSONObject

/** Closed set of analytics facts approved by shared schema v1. */
sealed interface AnalyticsEvent {
  data class InstallationCreated(val entryPoint: InstallationEntryPoint) : AnalyticsEvent
  data class AppOpened(val launchType: LaunchType) : AnalyticsEvent
  data class SessionStarted(val entryPoint: SessionEntryPoint) : AnalyticsEvent
  data class SessionEnded(val durationSeconds: Int) : AnalyticsEvent
  data class AppBackgrounded(val durationSeconds: Int) : AnalyticsEvent
  data class AppUpdated(val previousAppVersion: String) : AnalyticsEvent
  data object AuthSessionCreated : AnalyticsEvent
  data object AuthSessionRefreshed : AnalyticsEvent
  data object OnlineModeDisabled : AnalyticsEvent
  data class OnboardingStarted(val entryPoint: OnboardingEntryPoint) : AnalyticsEvent
  data class OnboardingStepViewed(val step: OnboardingStep, val stepIndex: Int) : AnalyticsEvent
  data class OnboardingCompleted(val durationSeconds: Int) : AnalyticsEvent
  data class NotificationAccessPrompted(val entryPoint: PromptEntryPoint) : AnalyticsEvent
  data class NotificationAccessResult(val result: AccessResult) : AnalyticsEvent
  data class AnalyticsConsentChanged(val enabled: Boolean, val consentVersion: Int) : AnalyticsEvent
  data class NotificationAggregateConsentChanged(val enabled: Boolean, val consentVersion: Int) : AnalyticsEvent
  data object ScreenViewed : AnalyticsEvent
  data object TabSelected : AnalyticsEvent
  data object HelpOpened : AnalyticsEvent
  data object SettingChanged : AnalyticsEvent
  data object ShareStarted : AnalyticsEvent
  data object ShareCompleted : AnalyticsEvent
  data object ShareFailed : AnalyticsEvent
  data object FormationViewed : AnalyticsEvent
  data object DaySealed : AnalyticsEvent
  data object SpecimenGenerated : AnalyticsEvent
  data object SpecimenRevealStarted : AnalyticsEvent
  data object SpecimenRevealed : AnalyticsEvent
  data object SpecimenLocked : AnalyticsEvent
  data object SpecimenUnlocked : AnalyticsEvent
  data object WeeklyDioramaCreated : AnalyticsEvent
}

enum class InstallationEntryPoint { FIRST_RUN, ONLINE_MODE_ENABLED }
enum class LaunchType { COLD, WARM }
enum class SessionEntryPoint { COLD_START, FOREGROUND_RETURN }
enum class OnboardingEntryPoint { FIRST_RUN, RESUME }
enum class OnboardingStep { WELCOME, PRIVACY, NOTIFICATION_ACCESS, ANALYTICS_CONSENT, READY }
enum class PromptEntryPoint { ONBOARDING, SETTINGS }
enum class AccessResult { GRANTED, DENIED, UNAVAILABLE }

data class AnalyticsContext(
  val eventId: String,
  val installationId: String,
  val occurredAt: String,
  val localDate: String,
  val timezoneOffsetMinutes: Int,
  val appVersion: String,
  val versionCode: Int,
  val buildChannel: String,
  val androidApiLevel: Int,
  val deviceClass: String,
  val locale: String,
  val consentScopeVersion: Int,
  val userId: String? = null,
  val sessionId: String? = null,
  val receivedAt: String? = null,
  val screenName: String? = null,
)

internal data class EncodedAnalyticsEvent(val name: String, val properties: JSONObject)

internal fun AnalyticsEvent.encode(): EncodedAnalyticsEvent = when (this) {
  is AnalyticsEvent.InstallationCreated -> EncodedAnalyticsEvent("installation_created", JSONObject().put("entry_point", when (entryPoint) { InstallationEntryPoint.FIRST_RUN -> "first_run"; InstallationEntryPoint.ONLINE_MODE_ENABLED -> "online_mode_enabled" }))
  is AnalyticsEvent.AppOpened -> EncodedAnalyticsEvent("app_opened", JSONObject().put("launch_type", when (launchType) { LaunchType.COLD -> "cold"; LaunchType.WARM -> "warm" }))
  is AnalyticsEvent.SessionStarted -> EncodedAnalyticsEvent("session_started", JSONObject().put("entry_point", when (entryPoint) { SessionEntryPoint.COLD_START -> "cold_start"; SessionEntryPoint.FOREGROUND_RETURN -> "foreground_return" }))
  is AnalyticsEvent.SessionEnded -> EncodedAnalyticsEvent("session_ended", JSONObject().put("duration_seconds", durationSeconds))
  is AnalyticsEvent.AppBackgrounded -> EncodedAnalyticsEvent("app_backgrounded", JSONObject().put("duration_seconds", durationSeconds))
  is AnalyticsEvent.AppUpdated -> EncodedAnalyticsEvent("app_updated", JSONObject().put("previous_app_version", previousAppVersion))
  AnalyticsEvent.AuthSessionCreated -> EncodedAnalyticsEvent("auth_session_created", JSONObject().put("auth_method", "anonymous"))
  AnalyticsEvent.AuthSessionRefreshed -> EncodedAnalyticsEvent("auth_session_refreshed", JSONObject().put("outcome", "rotated"))
  AnalyticsEvent.OnlineModeDisabled -> EncodedAnalyticsEvent("online_mode_disabled", JSONObject().put("reason", "user_request"))
  is AnalyticsEvent.OnboardingStarted -> EncodedAnalyticsEvent("onboarding_started", JSONObject().put("entry_point", when (entryPoint) { OnboardingEntryPoint.FIRST_RUN -> "first_run"; OnboardingEntryPoint.RESUME -> "resume" }))
  is AnalyticsEvent.OnboardingStepViewed -> EncodedAnalyticsEvent("onboarding_step_viewed", JSONObject().put("step", step.name.lowercase()).put("step_index", stepIndex))
  is AnalyticsEvent.OnboardingCompleted -> EncodedAnalyticsEvent("onboarding_completed", JSONObject().put("duration_seconds", durationSeconds))
  is AnalyticsEvent.NotificationAccessPrompted -> EncodedAnalyticsEvent("notification_access_prompted", JSONObject().put("entry_point", when (entryPoint) { PromptEntryPoint.ONBOARDING -> "onboarding"; PromptEntryPoint.SETTINGS -> "settings" }))
  is AnalyticsEvent.NotificationAccessResult -> EncodedAnalyticsEvent("notification_access_result", JSONObject().put("result", when (result) { AccessResult.GRANTED -> "granted"; AccessResult.DENIED -> "denied"; AccessResult.UNAVAILABLE -> "unavailable" }))
  is AnalyticsEvent.AnalyticsConsentChanged -> EncodedAnalyticsEvent("analytics_consent_changed", JSONObject().put("enabled", enabled).put("consent_version", consentVersion))
  is AnalyticsEvent.NotificationAggregateConsentChanged -> EncodedAnalyticsEvent("notification_aggregate_consent_changed", JSONObject().put("enabled", enabled).put("consent_version", consentVersion))
  AnalyticsEvent.ScreenViewed -> EncodedAnalyticsEvent("screen_viewed", JSONObject())
  AnalyticsEvent.TabSelected -> EncodedAnalyticsEvent("tab_selected", JSONObject())
  AnalyticsEvent.HelpOpened -> EncodedAnalyticsEvent("help_opened", JSONObject())
  AnalyticsEvent.SettingChanged -> EncodedAnalyticsEvent("setting_changed", JSONObject())
  AnalyticsEvent.ShareStarted -> EncodedAnalyticsEvent("share_started", JSONObject())
  AnalyticsEvent.ShareCompleted -> EncodedAnalyticsEvent("share_completed", JSONObject())
  AnalyticsEvent.ShareFailed -> EncodedAnalyticsEvent("share_failed", JSONObject())
  AnalyticsEvent.FormationViewed -> EncodedAnalyticsEvent("formation_viewed", JSONObject())
  AnalyticsEvent.DaySealed -> EncodedAnalyticsEvent("day_sealed", JSONObject())
  AnalyticsEvent.SpecimenGenerated -> EncodedAnalyticsEvent("specimen_generated", JSONObject())
  AnalyticsEvent.SpecimenRevealStarted -> EncodedAnalyticsEvent("specimen_reveal_started", JSONObject())
  AnalyticsEvent.SpecimenRevealed -> EncodedAnalyticsEvent("specimen_revealed", JSONObject())
  AnalyticsEvent.SpecimenLocked -> EncodedAnalyticsEvent("specimen_locked", JSONObject())
  AnalyticsEvent.SpecimenUnlocked -> EncodedAnalyticsEvent("specimen_unlocked", JSONObject())
  AnalyticsEvent.WeeklyDioramaCreated -> EncodedAnalyticsEvent("weekly_diorama_created", JSONObject())
}
