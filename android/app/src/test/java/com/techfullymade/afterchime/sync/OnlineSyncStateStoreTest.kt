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
    val store = store(MemoryPersistence(), ID_ONE, ID_TWO)
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    val enabled = requireNotNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    assertTrue(store.acknowledgeConsent(enabled))

    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    assertNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())

    assertTrue(store.bindInstallation(INSTALLATION_TWO))
    val afterReplacement = requireNotNull(store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true).available())
    assertEquals(ID_TWO, afterReplacement.idempotencyKey)
  }

  @Test
  fun `failed commit never releases an unpersisted identifier`() {
    val persistence = MemoryPersistence().apply { failNextCommit = true }
    val store = store(persistence, ID_ONE, ID_TWO)

    assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())
    assertEquals(ID_TWO, store.registrationIdempotencyKey().available())

    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    persistence.failNextCommit = true
    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true),
    )
  }

  @Test
  fun `malformed stored values fail closed`() {
    val persistence = MemoryPersistence().apply {
      strings["registration.idempotency_key"] = "not-a-uuid"
    }
    val store = store(persistence, ID_ONE)
    assertEquals(SyncStateRead.Unavailable, store.registrationIdempotencyKey())

    persistence.strings.clear()
    persistence.strings["installation.id"] = INSTALLATION_ONE
    persistence.malformed += "consent.product_analytics.pending_target"
    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.PRODUCT_ANALYTICS, true),
    )
  }

  @Test
  fun `shared preferences implementation survives recreation and clear`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences("afterchime_online_sync_state", Context.MODE_PRIVATE).edit().clear().commit()
    val first = SharedPreferencesOnlineSyncStateStore(context)
    val key = first.registrationIdempotencyKey().available()
    assertTrue(key.matches(UUID_PATTERN))

    val recreated = SharedPreferencesOnlineSyncStateStore(context)
    assertEquals(key, recreated.registrationIdempotencyKey().available())
    assertTrue(recreated.clear())
    assertNotEquals(key, SharedPreferencesOnlineSyncStateStore(context).registrationIdempotencyKey().available())
  }

  @Test
  fun `invalid installation and failed clear fail closed`() {
    val persistence = MemoryPersistence()
    val store = store(persistence, ID_ONE)
    assertFalse(store.bindInstallation("bad"))
    assertEquals(
      SyncStateRead.Unavailable,
      store.consentMutation(OnlineConsentScope.ESSENTIAL_ONLINE, true),
    )
    assertTrue(store.bindInstallation(INSTALLATION_ONE))
    persistence.failNextCommit = true
    assertFalse(store.clear())
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

  private class MemoryPersistence : SyncStatePersistence {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()
    val malformed = mutableSetOf<String>()
    var failNextCommit = false

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
      if (failNextCommit) {
        failNextCommit = false
        return false
      }
      if (clear) {
        this.strings.clear()
        this.booleans.clear()
        malformed.clear()
      }
      removals.forEach {
        this.strings.remove(it)
        this.booleans.remove(it)
        malformed.remove(it)
      }
      this.strings.putAll(strings)
      this.booleans.putAll(booleans)
      return true
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
