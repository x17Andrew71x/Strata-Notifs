package com.techfullymade.afterchime.security

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalSecretStoreTest {
  @Test
  fun `fresh material is stable across process-local store instances`() {
    val keyMaterial = FakeKeystoreMaterial()
    val envelopeStore = FakeEnvelopeStore()
    val firstStore = LocalSecretStore(keyMaterial, envelopeStore, TestRandom(listOf(bytes(7))))

    val firstGeneratorSecret = firstStore.loadGeneratorSecret()
    val firstSourceKey = firstStore.sourceHmacKey()
    val secondGeneratorSecret = LocalSecretStore(
      keyMaterial,
      envelopeStore,
      TestRandom(listOf(bytes(9))),
    ).loadGeneratorSecret()

    assertArrayEquals(bytes(7), firstGeneratorSecret)
    assertArrayEquals(firstGeneratorSecret, secondGeneratorSecret)
    assertEquals("HmacSHA256", firstSourceKey.algorithm)
    assertEquals(1, keyMaterial.sourceHmacKeyCreations)
    assertEquals(1, keyMaterial.wrappingKeyCreations)
    assertTrue(envelopeStore.isInitialized())
  }

  @Test
  fun `lost source HMAC key with protected history fails closed`() {
    val envelopeStore = FakeEnvelopeStore()
    LocalSecretStore(
      FakeKeystoreMaterial(),
      envelopeStore,
      TestRandom(listOf(bytes(5))),
    ).loadGeneratorSecret()
    val replacementKeyMaterial = FakeKeystoreMaterial()
    val replacementStore = LocalSecretStore(
      replacementKeyMaterial,
      envelopeStore,
      TestRandom(listOf(bytes(6))),
    )

    assertThrows(LocalSecretUnavailableException::class.java) {
      replacementStore.sourceHmacKey()
    }

    assertEquals(0, replacementKeyMaterial.sourceHmacKeyCreations)
    assertTrue(envelopeStore.isInitialized())
  }

  @Test
  fun `lost generator wrapping key with protected history fails closed`() {
    val keyMaterial = FakeKeystoreMaterial()
    val envelopeStore = FakeEnvelopeStore()
    val store = LocalSecretStore(keyMaterial, envelopeStore, TestRandom(listOf(bytes(3))))
    store.loadGeneratorSecret()
    keyMaterial.deleteWrappingKey()

    assertThrows(LocalSecretUnavailableException::class.java) {
      store.loadGeneratorSecret()
    }

    assertTrue(envelopeStore.isInitialized())
  }

  @Test
  fun `failed envelope persistence never returns new secret material`() {
    val keyMaterial = FakeKeystoreMaterial()
    val envelopeStore = FakeEnvelopeStore(allowPersist = false)
    val store = LocalSecretStore(keyMaterial, envelopeStore, TestRandom(listOf(bytes(4))))

    assertThrows(LocalSecretPersistenceException::class.java) {
      store.loadGeneratorSecret()
    }

    assertFalse(envelopeStore.isInitialized())
    assertNull(envelopeStore.read())
  }

  @Test
  fun `explicit local history reset alone permits replacing protected material`() {
    val keyMaterial = FakeKeystoreMaterial()
    val envelopeStore = FakeEnvelopeStore()
    val store = LocalSecretStore(
      keyMaterial,
      envelopeStore,
      TestRandom(listOf(bytes(1), bytes(2))),
    )
    val original = store.loadGeneratorSecret()

    store.resetAfterExplicitLocalHistoryDeletion()
    val replacement = store.loadGeneratorSecret()

    assertArrayEquals(bytes(1), original)
    assertArrayEquals(bytes(2), replacement)
    assertFalse(original.contentEquals(replacement))
    assertEquals(2, keyMaterial.sourceHmacKeyCreations)
    assertEquals(2, keyMaterial.wrappingKeyCreations)
    assertTrue(envelopeStore.isInitialized())
  }

  private fun bytes(value: Int): ByteArray = ByteArray(LocalSecretStore.GENERATOR_SECRET_BYTES) { value.toByte() }

  private class TestRandom(
    private val values: List<ByteArray>,
  ) : LocalSecretRandom {
    private var index = 0

    override fun nextBytes(byteCount: Int): ByteArray = values[index++].copyOf().also { value ->
      require(value.size == byteCount)
    }
  }

  private class FakeEnvelopeStore(
    private val allowPersist: Boolean = true,
  ) : LocalSecretEnvelopeStore {
    private var envelope: EncryptedLocalSecret? = null
    private var initialized = false

    override fun read(): EncryptedLocalSecret? = envelope

    override fun persist(encryptedSecret: EncryptedLocalSecret): Boolean {
      if (!allowPersist) {
        return false
      }
      envelope = encryptedSecret
      initialized = true
      return true
    }

    override fun isInitialized(): Boolean = initialized

    override fun clear(): Boolean {
      envelope = null
      initialized = false
      return true
    }
  }

  private class FakeKeystoreMaterial : KeystoreMaterial {
    private var sourceHmacKey: SecretKey? = null
    private var wrappingKey: SecretKey? = null
    var sourceHmacKeyCreations = 0
      private set
    var wrappingKeyCreations = 0
      private set

    override fun sourceHmacKey(): SecretKey? = sourceHmacKey

    override fun createSourceHmacKey(): SecretKey {
      sourceHmacKeyCreations += 1
      return SecretKeySpec(
        ByteArray(32) { sourceHmacKeyCreations.toByte() },
        "HmacSHA256",
      ).also { sourceHmacKey = it }
    }

    override fun wrappingKey(): SecretKey? = wrappingKey

    override fun createWrappingKey(): SecretKey {
      wrappingKeyCreations += 1
      return SecretKeySpec(
        ByteArray(32) { wrappingKeyCreations.toByte() },
        "AES",
      ).also { wrappingKey = it }
    }

    override fun encrypt(
      plaintext: ByteArray,
      wrappingKey: SecretKey,
    ): EncryptedLocalSecret = EncryptedLocalSecret(
      initializationVector = byteArrayOf(1),
      ciphertext = plaintext.copyOf(),
    )

    override fun decrypt(
      encryptedSecret: EncryptedLocalSecret,
      wrappingKey: SecretKey,
    ): ByteArray = encryptedSecret.ciphertext.copyOf()

    override fun deleteSourceHmacKey() {
      sourceHmacKey = null
    }

    override fun deleteWrappingKey() {
      wrappingKey = null
    }
  }
}
