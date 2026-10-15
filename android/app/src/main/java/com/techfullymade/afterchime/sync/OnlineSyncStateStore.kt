package com.techfullymade.afterchime.sync

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

enum class OnlineConsentScope(val wireName: String) {
  ESSENTIAL_ONLINE("essential_online"),
  PRODUCT_ANALYTICS("product_analytics"),
  NOTIFICATION_AGGREGATES("notification_aggregates"),
}

data class PendingConsentMutation(
  val scope: OnlineConsentScope,
  val target: Boolean,
  val idempotencyKey: String,
)

sealed interface SyncStateRead<out T> {
  data class Available<T>(val value: T) : SyncStateRead<T>
  data object Unavailable : SyncStateRead<Nothing>
}

interface OnlineSyncStateStore {
  fun pendingRegistrationIdempotencyKey(): SyncStateRead<String?>

  fun registrationIdempotencyKey(): SyncStateRead<String>

  fun acknowledgeRegistration(idempotencyKey: String): Boolean

  fun bindInstallation(installationId: String): Boolean

  fun consentMutation(
    scope: OnlineConsentScope,
    target: Boolean,
  ): SyncStateRead<PendingConsentMutation?>

  fun acknowledgeConsent(mutation: PendingConsentMutation): Boolean

  fun clear(): Boolean
}

internal sealed interface StoredValue<out T> {
  data object Missing : StoredValue<Nothing>
  data object Malformed : StoredValue<Nothing>
  data class Present<T>(val value: T) : StoredValue<T>
}

internal interface SyncStatePersistence {
  fun readString(key: String): StoredValue<String>

  fun readBoolean(key: String): StoredValue<Boolean>

  fun commit(
    strings: Map<String, String> = emptyMap(),
    booleans: Map<String, Boolean> = emptyMap(),
    removals: Set<String> = emptySet(),
    clear: Boolean = false,
  ): Boolean
}

/** Contains only non-secret retry state. Credentials remain in CredentialStore. */
class SharedPreferencesOnlineSyncStateStore internal constructor(
  private val persistence: SyncStatePersistence,
  private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) : OnlineSyncStateStore {
  constructor(context: Context) : this(
    SharedPreferencesSyncStatePersistence(
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    ),
  )

  @Synchronized
  override fun pendingRegistrationIdempotencyKey(): SyncStateRead<String?> =
    when (val stored = persistence.readString(REGISTRATION_KEY)) {
      is StoredValue.Present -> {
        if (validUuid(stored.value)) SyncStateRead.Available(stored.value) else SyncStateRead.Unavailable
      }
      StoredValue.Malformed -> SyncStateRead.Unavailable
      StoredValue.Missing -> SyncStateRead.Available(null)
    }

  @Synchronized
  override fun registrationIdempotencyKey(): SyncStateRead<String> {
    return when (val stored = persistence.readString(REGISTRATION_KEY)) {
      is StoredValue.Present -> {
        if (validUuid(stored.value)) SyncStateRead.Available(stored.value) else SyncStateRead.Unavailable
      }
      StoredValue.Malformed -> SyncStateRead.Unavailable
      StoredValue.Missing -> persistNewRegistrationKey()
    }
  }

  @Synchronized
  override fun acknowledgeRegistration(idempotencyKey: String): Boolean {
    if (!validUuid(idempotencyKey)) return false
    val stored = persistence.readString(REGISTRATION_KEY)
    if (stored !is StoredValue.Present || stored.value != idempotencyKey) return false
    return persistence.commit(removals = setOf(REGISTRATION_KEY))
  }

  @Synchronized
  override fun bindInstallation(installationId: String): Boolean {
    if (!validUuid(installationId)) return false
    return when (val stored = persistence.readString(INSTALLATION_ID_KEY)) {
      StoredValue.Malformed -> false
      is StoredValue.Present -> {
        if (!validUuid(stored.value)) {
          false
        } else if (stored.value == installationId) {
          true
        } else {
          persistInstallationBinding(installationId)
        }
      }
      StoredValue.Missing -> persistInstallationBinding(installationId)
    }
  }

  @Synchronized
  override fun consentMutation(
    scope: OnlineConsentScope,
    target: Boolean,
  ): SyncStateRead<PendingConsentMutation?> {
    if (!hasValidInstallationBinding()) return SyncStateRead.Unavailable

    val pendingKey = persistence.readString(pendingKey(scope))
    val pendingTarget = persistence.readBoolean(pendingTarget(scope))
    if (pendingKey == StoredValue.Malformed || pendingTarget == StoredValue.Malformed) {
      return SyncStateRead.Unavailable
    }
    if ((pendingKey is StoredValue.Present) != (pendingTarget is StoredValue.Present)) {
      return SyncStateRead.Unavailable
    }
    if (pendingKey is StoredValue.Present && pendingTarget is StoredValue.Present) {
      if (!validUuid(pendingKey.value)) return SyncStateRead.Unavailable
      if (pendingTarget.value == target) {
        return SyncStateRead.Available(PendingConsentMutation(scope, target, pendingKey.value))
      }
      return persistNewConsentMutation(scope, target)
    }

    return when (val acknowledged = persistence.readBoolean(acknowledgedTarget(scope))) {
      StoredValue.Malformed -> SyncStateRead.Unavailable
      is StoredValue.Present -> {
        if (acknowledged.value == target) {
          SyncStateRead.Available(null)
        } else {
          persistNewConsentMutation(scope, target)
        }
      }
      StoredValue.Missing -> persistNewConsentMutation(scope, target)
    }
  }

  @Synchronized
  override fun acknowledgeConsent(mutation: PendingConsentMutation): Boolean {
    if (!validUuid(mutation.idempotencyKey)) return false
    val key = persistence.readString(pendingKey(mutation.scope))
    val target = persistence.readBoolean(pendingTarget(mutation.scope))
    if (
      key !is StoredValue.Present ||
      target !is StoredValue.Present ||
      key.value != mutation.idempotencyKey ||
      target.value != mutation.target
    ) {
      return false
    }
    return persistence.commit(
      booleans = mapOf(acknowledgedTarget(mutation.scope) to mutation.target),
      removals = setOf(pendingKey(mutation.scope), pendingTarget(mutation.scope)),
    )
  }

  @Synchronized
  override fun clear(): Boolean = persistence.commit(clear = true)

  private fun persistNewRegistrationKey(): SyncStateRead<String> {
    val key = newValidIdempotencyKey() ?: return SyncStateRead.Unavailable
    return if (persistence.commit(strings = mapOf(REGISTRATION_KEY to key))) {
      SyncStateRead.Available(key)
    } else {
      SyncStateRead.Unavailable
    }
  }

  private fun persistInstallationBinding(installationId: String): Boolean =
    persistence.commit(
      strings = mapOf(INSTALLATION_ID_KEY to installationId),
      removals = CONSENT_KEYS,
    )

  private fun persistNewConsentMutation(
    scope: OnlineConsentScope,
    target: Boolean,
  ): SyncStateRead<PendingConsentMutation?> {
    val key = newValidIdempotencyKey() ?: return SyncStateRead.Unavailable
    val mutation = PendingConsentMutation(scope, target, key)
    return if (
      persistence.commit(
        strings = mapOf(pendingKey(scope) to key),
        booleans = mapOf(pendingTarget(scope) to target),
      )
    ) {
      SyncStateRead.Available(mutation)
    } else {
      SyncStateRead.Unavailable
    }
  }

  private fun hasValidInstallationBinding(): Boolean =
    (persistence.readString(INSTALLATION_ID_KEY) as? StoredValue.Present)?.value?.let(::validUuid) == true

  private fun newValidIdempotencyKey(): String? =
    runCatching { newIdempotencyKey() }.getOrNull()?.takeIf(::validUuid)

  private companion object {
    const val PREFERENCES_NAME = "afterchime_online_sync_state"
    const val REGISTRATION_KEY = "registration.idempotency_key"
    const val INSTALLATION_ID_KEY = "installation.id"

    fun acknowledgedTarget(scope: OnlineConsentScope) = "consent.${scope.wireName}.acknowledged_target"

    fun pendingKey(scope: OnlineConsentScope) = "consent.${scope.wireName}.pending_key"

    fun pendingTarget(scope: OnlineConsentScope) = "consent.${scope.wireName}.pending_target"

    val CONSENT_KEYS = OnlineConsentScope.entries.flatMapTo(mutableSetOf()) { scope ->
      listOf(acknowledgedTarget(scope), pendingKey(scope), pendingTarget(scope))
    }

    fun validUuid(value: String): Boolean =
      runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
  }
}

private class SharedPreferencesSyncStatePersistence(
  private val preferences: SharedPreferences,
) : SyncStatePersistence {
  override fun readString(key: String): StoredValue<String> = try {
    if (!preferences.contains(key)) StoredValue.Missing
    else preferences.getString(key, null)?.let { StoredValue.Present(it) } ?: StoredValue.Malformed
  } catch (_: ClassCastException) {
    StoredValue.Malformed
  }

  override fun readBoolean(key: String): StoredValue<Boolean> = try {
    if (!preferences.contains(key)) StoredValue.Missing
    else StoredValue.Present(preferences.getBoolean(key, false))
  } catch (_: ClassCastException) {
    StoredValue.Malformed
  }

  override fun commit(
    strings: Map<String, String>,
    booleans: Map<String, Boolean>,
    removals: Set<String>,
    clear: Boolean,
  ): Boolean = try {
    preferences.edit().also { editor ->
      if (clear) editor.clear()
      removals.forEach(editor::remove)
      strings.forEach(editor::putString)
      booleans.forEach(editor::putBoolean)
    }.commit()
  } catch (_: RuntimeException) {
    false
  }
}
