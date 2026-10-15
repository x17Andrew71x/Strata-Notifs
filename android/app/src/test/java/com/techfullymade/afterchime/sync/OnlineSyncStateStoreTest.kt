package com.techfullymade.afterchime.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlineSyncStateStoreTest {
  @Test
  fun `registration key is durable across retry and only exact acknowledgement clears it`() {
    val persistence = MemoryPersistence()
    val store = store(persistence, ID_ONE, ID_TWO)

    assertNull(store.pendingRegistrationIdempotencyKey().available())
    assertEquals(ID_ONE, store.registrationIdempotencyKey().available())
    assertEquals(ID_ONE, store.pendingRegistrationIdempotencyKey().available())
    assertEquals(ID_ONE, store.registrationIdempotencyKey().available())
    assertFalse(store.acknowledgeRegistration(ID_TWO))
    assertEquals(ID_ONE, store.registrationIdempotencyKey().available())
    assertTrue(store.acknowledgeRegistration(ID_ONE))
    assertNull(store.pendingRegistrationIdempotencyKey().available())
    assertEquals(ID_TWO, store.registrationIdempotencyKey().available())
  }

  @Test
  fun `all consent scopes keep independent pending and acknowledged targets`() {
    val persistence = MemoryPersistence()
    val store = store(persistence, ID_ONE, ID_TWO, ID_THREE)
    assertTrue(store.bindInstallation(INSTALLATION_ONE))

    val essential = requireNotNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    val product = requireNotNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, false).available())
    val totals = requireNotNull(store.consentMutation(OnlineConsentScope.NOTIFICATION_AGGREGATES, true).available())
    assertEquals(setOf(ID_ONE, ID_TWO, ID_THREE), setOf(essential.idempotencyKey, product.idempotencyKey, totals.idempotencyKey))

    assertTrue(store.acknowledgeConsent(essential))
    assertTrue(store.acknowledgeConsent(product))
    assertTrue(store.acknowledgeConsent(totals))
    assertNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    assertNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, false).available())
    assertNull(store.consentMutation(OnlineConsentScope.NOTIFICATION_AGGREGATES, true).available())
  }

  @Test
  fun `same pending target reuses key while changed target gets a fresh key`() {
    val store = store(MemoryPersistence(), ID_ONE, ID_TWO, ID_THREE)
    assertTrue(store.bindInstallation(INSTALLATION_ONE))

    val first = requireNotNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true).available())
    assertEquals(first, store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true).available())
    val replacement = requireNotNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, false).available())
    assertNotEquals(first.idempotencyKey, replacement.idempotencyKey)
    assertFalse(store.acknowledgeConsent(first))
    assertTrue(store.acknowledgeConsent(replacement))
    assertNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, false).available())

    val changedAgain = requireNotNull(store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true).available())
    assertEquals(ID_THREE, changedAgain.idempotencyKey)
  }

  @Test
  fun `uncertain opposite pending mutation is corrected even when requested target equals last acknowledgement`() {
    val store = store(MemoryPersistence(), ID_ONE, ID_TWO, ID_THREE)
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    val enabled = requireNotNull(store.consentMutation(OnlineConsentScope.NOTIFICATION_AGGREGATES, true).available())
    assertTrue(store.acknowledgeConsent(enabled))

    val uncertainDisable = requireNotNull(store.consentMutation(OnlineConsentScope.NOTIFICATION_AGGREGATES, false).available())
    val correctiveEnable = requireNotNull(store.consentMutation(OnlineConsentScope.NOTIFICATION_AGGREGATES, true).available())
    assertNotEquals(uncertainDisable.idempotencyKey, correctiveEnable.idempotencyKey)
    assertEquals(ID_THREE, correctiveEnable.idempotencyKey)
  }

  @Test
  fun `new installation resets consent state while same installation preserves it`() {
    val persistence = MemoryPersistence()
    val store = store(persistence, ID_ONE, ID_TWO)
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    val enabled = requireNotNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    assertTrue(store.acknowledgeConsent(enabled))

    val commitsBeforeSameBinding = persistence.commitCalls
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    assertEquals(commitsBeforeSameBinding, persistence.commitCalls)
    assertNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())

    assertTrue(store.bindInstallation(INSTALLATION_TWO))
    assertFalse(persistence.keys().any { it.startsWith("consent.") })
    val afterReplacement = requireNotNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    assertEquals(ID_TWO, afterReplacement.idempotencyKey)
  }

  @Test
  fun `failed commit permanently latches without generating a replacement identifier`() {
    val persistence = MemoryPersistence().apply { failNextCommit = true }
    var generations = 0
    val store = SharedPreferencesOnlineSyncStateStore(persistence) {
      generations += 1
      if (generations == 1) ID_ONE else ID_TWO
    }

    assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())
    assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())
    assertEquals(SyncStateRead.Unavailable, store.pendingRegistrationIdempotencyKey())
    assertEquals(1, generations)
    assertEquals(1, persistence.commitCalls)

    val uncertainPersistence = MemoryPersistence().apply {
      failNextCommit = true
      mutateBeforeFailedCommit = true
    }
    val uncertainStore = store(uncertainPersistence, ID_ONE, ID_TWO)
    assertEquals(SyncStateRead.Unavailable, uncertainStore.registrationIdempotencyKey())
    assertEquals(ID_ONE, uncertainPersistence.strings["registration.idempotency_key"])
    assertEquals(SyncStateRead.Unavailable, uncertainStore.registrationIdempotencyKey())
    assertEquals(1, uncertainPersistence.commitCalls)
  }

  @Test
  fun `failed consent commit permanently withholds mutation authority`() {
    val persistence = MemoryPersistence()
    var generations = 0
    val store = SharedPreferencesOnlineSyncStateStore(persistence) {
      generations += 1
      if (generations == 1) ID_ONE else ID_TWO
    }
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    persistence.failNextCommit = true

    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true),
    )
    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true),
    )
    assertEquals(1, generations)
    assertFalse(store.bindInstallation(INSTALLATION_TWO))
  }

  @Test
  fun `commit and read exceptions permanently latch the store`() {
    val commitPersistence = MemoryPersistence().apply { throwNextCommit = true }
    val commitStore = store(commitPersistence, ID_ONE, ID_TWO)
    assertEquals(SyncStateRead.Unavailable, commitStore.registrationIdempotencyKey())
    assertEquals(SyncStateRead.Unavailable, commitStore.registrationIdempotencyKey())

    val readPersistence = MemoryPersistence().apply { throwNextKeysRead = true }
    val readStore = store(readPersistence, ID_ONE)
    assertEquals(SyncStateRead.Unavailable, readStore.registrationIdempotencyKey())
    assertEquals(SyncStateRead.Unavailable, readStore.registrationIdempotencyKey())
  }

  @Test
  fun `malformed mistyped structurally invalid and unknown state permanently latch`() {
    val invalidStates = listOf<(MemoryPersistence) -> Unit>(
      { it.strings["registration.idempotency_key"] = "not-a-uuid" },
      { it.booleans["registration.idempotency_key"] = true },
      { it.strings["unknown.key"] = ID_ONE },
      {
        it.strings["installation.id"] = INSTALLATION_ONE
        it.strings["consent.product_analytics.pending_key"] = ID_ONE
      },
      { it.booleans["consent.essential_online.acknowledged_target"] = true },
      {
        it.strings["installation.id"] = INSTALLATION_ONE
        it.malformed += "consent.notification_aggregates.pending_target"
      },
    )

    invalidStates.forEach { arrange ->
      val persistence = MemoryPersistence().also(arrange)
      val store = store(persistence, ID_ONE)
      assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())
      persistence.resetToEmpty()
      assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())
      assertEquals(0, persistence.commitCalls)
    }
  }

  @Test
  fun `all mutation paths reject successful commits whose exact state is not read back`() {
    val registrationCreatePersistence = MemoryPersistence().apply { reportNextSuccessWithoutMutation = true }
    val registrationCreateStore = store(registrationCreatePersistence, ID_ONE, ID_TWO)
    assertEquals(SyncStateRead.Unavailable, registrationCreateStore.registrationIdempotencyKey())
    assertEquals(SyncStateRead.Unavailable, registrationCreateStore.registrationIdempotencyKey())

    val registrationAckPersistence = MemoryPersistence().apply {
      strings["registration.idempotency_key"] = ID_ONE
      reportNextSuccessWithoutMutation = true
    }
    val registrationAckStore = store(registrationAckPersistence, ID_TWO)
    assertFalse(registrationAckStore.acknowledgeRegistration(ID_ONE))
    assertEquals(SyncStateRead.Unavailable, registrationAckStore.pendingRegistrationIdempotencyKey())

    val installationPersistence = MemoryPersistence().apply { reportNextSuccessWithoutMutation = true }
    val installationStore = store(installationPersistence, ID_ONE)
    assertFalse(installationStore.bindInstallation(INSTALLATION_ONE))
    assertFalse(installationStore.bindInstallation(INSTALLATION_ONE))

    val installationRemovalPersistence = MemoryPersistence().apply {
      strings["installation.id"] = INSTALLATION_ONE
      strings["consent.product_analytics.pending_key"] = ID_ONE
      booleans["consent.product_analytics.pending_target"] = true
      ignoreNextRemovals = true
    }
    val installationRemovalStore = store(installationRemovalPersistence, ID_TWO)
    assertFalse(installationRemovalStore.bindInstallation(INSTALLATION_TWO))
    assertEquals(INSTALLATION_TWO, installationRemovalPersistence.strings["installation.id"])
    assertTrue(installationRemovalPersistence.keys().any { it.startsWith("consent.") })
    assertFalse(installationRemovalStore.bindInstallation(INSTALLATION_TWO))

    val consentCreatePersistence = MemoryPersistence().apply {
      strings["installation.id"] = INSTALLATION_ONE
      reportNextSuccessWithoutMutation = true
    }
    val consentCreateStore = store(consentCreatePersistence, ID_ONE, ID_TWO)
    assertEquals(
      SyncStateRead.Unavailable,
      consentCreateStore.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )
    assertEquals(
      SyncStateRead.Unavailable,
      consentCreateStore.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )

    val consentAckPersistence = MemoryPersistence().apply {
      strings["installation.id"] = INSTALLATION_ONE
      strings["consent.essential_online.pending_key"] = ID_ONE
      booleans["consent.essential_online.pending_target"] = true
      reportNextSuccessWithoutMutation = true
    }
    val consentAckStore = store(consentAckPersistence, ID_TWO)
    val mutation = PendingConsentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true, ID_ONE)
    assertFalse(consentAckStore.acknowledgeConsent(mutation))
    assertEquals(
      SyncStateRead.Unavailable,
      consentAckStore.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )

    val consentRemovalPersistence = MemoryPersistence().apply {
      strings["installation.id"] = INSTALLATION_ONE
      strings["consent.essential_online.pending_key"] = ID_ONE
      booleans["consent.essential_online.pending_target"] = true
      ignoreNextRemovals = true
    }
    val consentRemovalStore = store(consentRemovalPersistence, ID_TWO)
    assertFalse(consentRemovalStore.acknowledgeConsent(mutation))
    assertEquals(true, consentRemovalPersistence.booleans["consent.essential_online.acknowledged_target"])
    assertEquals(ID_ONE, consentRemovalPersistence.strings["consent.essential_online.pending_key"])
    assertEquals(
      SyncStateRead.Unavailable,
      consentRemovalStore.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )

    val clearPersistence = MemoryPersistence().apply {
      strings["registration.idempotency_key"] = ID_ONE
      reportNextSuccessWithoutMutation = true
    }
    val clearStore = store(clearPersistence, ID_TWO)
    assertFalse(clearStore.clear())
    assertEquals(SyncStateRead.Unavailable, clearStore.pendingRegistrationIdempotencyKey())
  }

  @Test
  fun `shared preferences implementation survives recreation and verified clear`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences("afterchime_online_sync_state", Context.MODE_PRIVATE).edit().clear().commit()
    val first = SharedPreferencesOnlineSyncStateStore(context)
    val key = first.registrationIdempotencyKey().available()
    assertTrue(key.matches(UUID_PATTERN))

    val recreated = SharedPreferencesOnlineSyncStateStore(context)
    assertEquals(key, recreated.registrationIdempotencyKey().available())
    assertTrue(recreated.clear())
    assertNotEquals(key, recreated.registrationIdempotencyKey().available())
  }

  @Test
  fun `invalid input is rejected without poisoning store but failed clear latches`() {
    val persistence = MemoryPersistence()
    val store = store(persistence, ID_ONE)
    assertFalse(store.bindInstallation("bad"))
    assertTrue(store.bindInstallation(INSTALLATION_ONE))

    persistence.failNextCommit = true
    assertFalse(store.clear())
    assertFalse(store.clear())
    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )
    assertEquals(2, persistence.commitCalls)
  }

  private fun store(
    persistence: MemoryPersistence,
    vararg ids: String,
  ): SharedPreferencesOnlineSyncStateStore {
    val queue = ArrayDeque(ids.toList())
    return SharedPreferencesOnlineSyncStateStore(persistence) { queue.removeFirst() }
  }

  private fun <T> SyncStateRead<T>.available(): T =
    (this as SyncStateRead.Available<T>).value

  private class MemoryPersistence : SyncStatePersistence, SyncStateKeyPersistence {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()
    val malformed = mutableSetOf<String>()
    var failNextCommit = false
    var throwNextCommit = false
    var throwNextKeysRead = false
    var mutateBeforeFailedCommit = false
    var reportNextSuccessWithoutMutation = false
    var ignoreNextRemovals = false
    var commitCalls = 0

    override fun keys(): Set<String> {
      if (throwNextKeysRead) {
        throwNextKeysRead = false
        throw IllegalStateException("read failed")
      }
      return strings.keys + booleans.keys + malformed
    }

    override fun readString(key: String): StoredValue<String> = when {
      key in malformed -> StoredValue.Malformed
      key in strings -> StoredValue.Present(requireNotNull(strings[key]))
      key in booleans -> StoredValue.Malformed
      else -> StoredValue.Missing
    }

    override fun readBoolean(key: String): StoredValue<Boolean> = when {
      key in malformed -> StoredValue.Malformed
      key in booleans -> StoredValue.Present(requireNotNull(booleans[key]))
      key in strings -> StoredValue.Malformed
      else -> StoredValue.Missing
    }

    override fun commit(
      strings: Map<String, String>,
      booleans: Map<String, Boolean>,
      removals: Set<String>,
      clear: Boolean,
    ): Boolean {
      commitCalls += 1
      if (throwNextCommit) {
        throwNextCommit = false
        throw IllegalStateException("commit failed")
      }
      if (failNextCommit) {
        failNextCommit = false
        if (mutateBeforeFailedCommit) applyMutation(strings, booleans, removals, clear)
        return false
      }
      if (reportNextSuccessWithoutMutation) {
        reportNextSuccessWithoutMutation = false
        return true
      }
      val appliedRemovals = if (ignoreNextRemovals) emptySet() else removals
      ignoreNextRemovals = false
      applyMutation(strings, booleans, appliedRemovals, clear)
      return true
    }

    fun resetToEmpty() {
      strings.clear()
      booleans.clear()
      malformed.clear()
    }

    private fun applyMutation(
      strings: Map<String, String>,
      booleans: Map<String, Boolean>,
      removals: Set<String>,
      clear: Boolean,
    ) {
      if (clear) resetToEmpty()
      removals.forEach {
        this.strings.remove(it)
        this.booleans.remove(it)
        malformed.remove(it)
      }
      strings.forEach { (key, value) ->
        this.booleans.remove(key)
        malformed.remove(key)
        this.strings[key] = value
      }
      booleans.forEach { (key, value) ->
        this.strings.remove(key)
        malformed.remove(key)
        this.booleans[key] = value
      }
    }
  }

  private companion object {
    const val ID_ONE = "123e4567-e89b-42d3-a456-426614174001"
    const val ID_TWO = "123e4567-e89b-42d3-a456-426614174002"
    const val ID_THREE = "123e4567-e89b-42d3-a456-426614174003"
    const val INSTALLATION_ONE = "123e4567-e89b-42d3-a456-426614174010"
    const val INSTALLATION_TWO = "123e4567-e89b-42d3-a456-426614174011"
    val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
  }
}
