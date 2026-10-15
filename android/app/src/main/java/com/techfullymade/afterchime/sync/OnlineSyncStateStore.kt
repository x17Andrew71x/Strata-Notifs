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

internal interface SyncStateKeyPersistence {
  fun keys(): Set<String>
}

/** Contains only non-secret retry state. Credentials remain in CredentialStore. */
class SharedPreferencesOnlineSyncStateStore internal constructor(
  private val persistence: SyncStatePersistence,
  private val newIdempotencyKey: () -> String = { UUID.randomUUID().toString() },
) : OnlineSyncStateStore {
  private var unavailable = false

  constructor(context: Context) : this(
    SharedPreferencesSyncStatePersistence(
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    ),
  )

  @Synchronized
  override fun pendingRegistrationIdempotencyKey(): SyncStateRead<String?> {
    val state = readState() ?: return SyncStateRead.Unavailable
    return SyncStateRead.Available(state.string(REGISTRATION_KEY))
  }

  @Synchronized
  override fun registrationIdempotencyKey(): SyncStateRead<String> {
    val state = readState() ?: return SyncStateRead.Unavailable
    return state.string(REGISTRATION_KEY)?.let { SyncStateRead.Available(it) }
      ?: persistNewRegistrationKey()
  }

  @Synchronized
  override fun acknowledgeRegistration(idempotencyKey: String): Boolean {
    if (!validUuid(idempotencyKey)) return false
    val state = readState() ?: return false
    if (state.string(REGISTRATION_KEY) != idempotencyKey) return false
    return commitAndVerify(removals = setOf(REGISTRATION_KEY))
  }

  @Synchronized
  override fun bindInstallation(installationId: String): Boolean {
    if (!validUuid(installationId)) return false
    val state = readState() ?: return false
    return if (state.string(INSTALLATION_ID_KEY) == installationId) {
      true
    } else {
      persistInstallationBinding(installationId)
    }
  }

  @Synchronized
  override fun consentMutation(
    scope: OnlineConsentScope,
    target: Boolean,
  ): SyncStateRead<PendingConsentMutation?> {
    val state = readState() ?: return SyncStateRead.Unavailable
    if (state.string(INSTALLATION_ID_KEY) == null) return SyncStateRead.Unavailable

    val pendingKey = state.string(pendingKey(scope))
    val pendingTarget = state.boolean(pendingTarget(scope))
    if (pendingKey != null && pendingTarget != null) {
      if (pendingTarget == target) {
        return SyncStateRead.Available(PendingConsentMutation(scope, target, pendingKey))
      }
      return persistNewConsentMutation(scope, target)
    }

    return if (state.boolean(acknowledgedTarget(scope)) == target) {
      SyncStateRead.Available(null)
    } else {
      persistNewConsentMutation(scope, target)
    }
  }

  @Synchronized
  override fun acknowledgeConsent(mutation: PendingConsentMutation): Boolean {
    if (!validUuid(mutation.idempotencyKey)) return false
    val state = readState() ?: return false
    if (
      state.string(pendingKey(mutation.scope)) != mutation.idempotencyKey ||
      state.boolean(pendingTarget(mutation.scope)) != mutation.target
    ) {
      return false
    }
    return commitAndVerify(
      booleans = mapOf(acknowledgedTarget(mutation.scope) to mutation.target),
      removals = setOf(pendingKey(mutation.scope), pendingTarget(mutation.scope)),
    )
  }

  @Synchronized
  override fun clear(): Boolean {
    if (readState() == null) return false
    return commitAndVerify(clear = true)
  }

  private fun persistNewRegistrationKey(): SyncStateRead<String> {
    val key = newValidIdempotencyKey() ?: return SyncStateRead.Unavailable
    return if (commitAndVerify(strings = mapOf(REGISTRATION_KEY to key))) {
      SyncStateRead.Available(key)
    } else {
      SyncStateRead.Unavailable
    }
  }

  private fun persistInstallationBinding(installationId: String): Boolean =
    commitAndVerify(
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
      commitAndVerify(
        strings = mapOf(pendingKey(scope) to key),
        booleans = mapOf(pendingTarget(scope) to target),
      )
    ) {
      SyncStateRead.Available(mutation)
    } else {
      SyncStateRead.Unavailable
    }
  }

  private fun readState(): PersistedState? {
    if (unavailable) return null

    val values = mutableMapOf<String, Any>()
    val keys = try {
      (persistence as? SyncStateKeyPersistence)?.keys() ?: return failClosed()
    } catch (_: RuntimeException) {
      return failClosed()
    }
    if (keys.any { it !in STATE_KEYS }) return failClosed()

    try {
      STRING_KEYS.forEach { key ->
        when (val stored = persistence.readString(key)) {
          is StoredValue.Present -> values[key] = stored.value
          StoredValue.Malformed -> return failClosed()
          StoredValue.Missing -> Unit
        }
      }
      BOOLEAN_KEYS.forEach { key ->
        when (val stored = persistence.readBoolean(key)) {
          is StoredValue.Present -> values[key] = stored.value
          StoredValue.Malformed -> return failClosed()
          StoredValue.Missing -> Unit
        }
      }
    } catch (_: RuntimeException) {
      return failClosed()
    }

    if (keys != values.keys) return failClosed()
    if (STRING_KEYS.any { key -> values[key]?.let { !validUuid(it as String) } == true }) {
      return failClosed()
    }

    for (scope in OnlineConsentScope.entries) {
      val hasPendingKey = values.containsKey(pendingKey(scope))
      val hasPendingTarget = values.containsKey(pendingTarget(scope))
      if (hasPendingKey != hasPendingTarget) return failClosed()
    }
    if (
      !values.containsKey(INSTALLATION_ID_KEY) &&
      values.keys.any { it in CONSENT_KEYS }
    ) {
      return failClosed()
    }

    return PersistedState(values)
  }

  private fun commitAndVerify(
    strings: Map<String, String> = emptyMap(),
    booleans: Map<String, Boolean> = emptyMap(),
    removals: Set<String> = emptySet(),
    clear: Boolean = false,
  ): Boolean {
    if (unavailable) return false
    val committed = try {
      persistence.commit(strings, booleans, removals, clear)
    } catch (_: RuntimeException) {
      return failClosedBoolean()
    }
    if (!committed) return failClosedBoolean()

    val stored = readState() ?: return false
    if (clear && stored.values.isNotEmpty()) return failClosedBoolean()
    if (strings.any { (key, value) -> stored.string(key) != value }) return failClosedBoolean()
    if (booleans.any { (key, value) -> stored.boolean(key) != value }) return failClosedBoolean()
    if (removals.any(stored.values::containsKey)) return failClosedBoolean()
    return true
  }

  private fun newValidIdempotencyKey(): String? {
    if (unavailable) return null
    val key = try {
      newIdempotencyKey()
    } catch (_: RuntimeException) {
      return failClosedString()
    }
    return key.takeIf(::validUuid) ?: failClosedString()
  }

  private fun failClosed(): PersistedState? {
    unavailable = true
    return null
  }

  private fun failClosedBoolean(): Boolean {
    unavailable = true
    return false
  }

  private fun failClosedString(): String? {
    unavailable = true
    return null
  }

  private data class PersistedState(val values: Map<String, Any>) {
    fun string(key: String): String? = values[key] as? String

    fun boolean(key: String): Boolean? = values[key] as? Boolean
  }

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
    val STRING_KEYS = CONSENT_KEYS.filterTo(mutableSetOf()) { it.endsWith(".pending_key") } +
      REGISTRATION_KEY + INSTALLATION_ID_KEY
    val BOOLEAN_KEYS = CONSENT_KEYS - STRING_KEYS
    val STATE_KEYS = STRING_KEYS + BOOLEAN_KEYS

    fun validUuid(value: String): Boolean =
      runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
  }
}

private class SharedPreferencesSyncStatePersistence(
  private val preferences: SharedPreferences,
) : SyncStatePersistence, SyncStateKeyPersistence {
  override fun keys(): Set<String> = preferences.all.keys

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
