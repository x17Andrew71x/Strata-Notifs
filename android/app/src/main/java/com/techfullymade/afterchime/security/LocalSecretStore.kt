package com.techfullymade.afterchime.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opaque encrypted generator material; raw generator bytes never enter app storage. */
data class EncryptedLocalSecret(
  val initializationVector: ByteArray,
  val ciphertext: ByteArray,
)

/** Durable storage for encrypted material only. Implementations must commit the envelope atomically. */
interface LocalSecretEnvelopeStore {
  fun read(): EncryptedLocalSecret?

  fun persist(encryptedSecret: EncryptedLocalSecret): Boolean

  fun isInitialized(): Boolean

  fun clear(): Boolean
}

/** Android Keystore boundary, kept injectable so unavailable-key recovery can be tested deterministically. */
interface KeystoreMaterial {
  fun sourceHmacKey(): SecretKey?

  fun createSourceHmacKey(): SecretKey

  fun wrappingKey(): SecretKey?

  fun createWrappingKey(): SecretKey

  fun encrypt(
    plaintext: ByteArray,
    wrappingKey: SecretKey,
  ): EncryptedLocalSecret

  fun decrypt(
    encryptedSecret: EncryptedLocalSecret,
    wrappingKey: SecretKey,
  ): ByteArray

  fun deleteSourceHmacKey()

  fun deleteWrappingKey()
}

fun interface LocalSecretRandom {
  fun nextBytes(byteCount: Int): ByteArray
}

sealed class LocalSecretException(message: String) : IllegalStateException(message)

/**
 * Protected identity material has disappeared or cannot be decrypted.
 *
 * Existing local history must be preserved. Replacement material is permitted only after the user
 * explicitly deletes that history and invokes [LocalSecretStore.resetAfterExplicitLocalHistoryDeletion].
 */
class LocalSecretUnavailableException : LocalSecretException("protected local identity material is unavailable")

class LocalSecretPersistenceException : LocalSecretException("protected local identity material could not be persisted")

/**
 * Owns the two device-local identity boundaries:
 * - a non-exportable Keystore HMAC key for reducing raw package identity; and
 * - a random generator secret encrypted with a separate Keystore AES key.
 *
 * If already-initialized protected material is unavailable, this store fails closed rather than
 * minting a replacement that would silently change source tokens or future specimen identities.
 */
class LocalSecretStore(
  private val keyMaterial: KeystoreMaterial,
  private val envelopeStore: LocalSecretEnvelopeStore,
  private val random: LocalSecretRandom = LocalSecretRandom { byteCount ->
    ByteArray(byteCount).also(SecureRandom()::nextBytes)
  },
) {
  @Synchronized
  fun sourceHmacKey(): SecretKey {
    keyMaterial.sourceHmacKey()?.let { return it }
    if (envelopeStore.isInitialized() || envelopeStore.read() != null || keyMaterial.wrappingKey() != null) {
      throw LocalSecretUnavailableException()
    }
    return keyMaterial.createSourceHmacKey()
  }

  @Synchronized
  fun loadGeneratorSecret(): ByteArray {
    val encryptedSecret = envelopeStore.read()
    if (encryptedSecret == null) {
      if (envelopeStore.isInitialized()) {
        throw LocalSecretUnavailableException()
      }
      sourceHmacKey()
      val wrappingKey = keyMaterial.wrappingKey() ?: keyMaterial.createWrappingKey()
      val generatedSecret = random.nextBytes(GENERATOR_SECRET_BYTES)
      require(generatedSecret.size == GENERATOR_SECRET_BYTES)
      val replacement = try {
        keyMaterial.encrypt(generatedSecret, wrappingKey)
      } catch (_: Exception) {
        generatedSecret.fill(0)
        throw LocalSecretUnavailableException()
      }
      if (!envelopeStore.persist(replacement)) {
        generatedSecret.fill(0)
        throw LocalSecretPersistenceException()
      }
      return generatedSecret
    }

    if (!envelopeStore.isInitialized()) {
      throw LocalSecretUnavailableException()
    }
    sourceHmacKey()
    val wrappingKey = keyMaterial.wrappingKey() ?: throw LocalSecretUnavailableException()
    val decryptedSecret = try {
      keyMaterial.decrypt(encryptedSecret, wrappingKey)
    } catch (_: Exception) {
      throw LocalSecretUnavailableException()
    }
    if (decryptedSecret.size != GENERATOR_SECRET_BYTES) {
      decryptedSecret.fill(0)
      throw LocalSecretUnavailableException()
    }
    return decryptedSecret
  }

  @Synchronized
  fun ensureAvailable() {
    loadGeneratorSecret().fill(0)
    sourceHmacKey()
  }

  /**
   * Clears protected identity material only after the caller has completed an explicit local-history
   * deletion. It is intentionally never called as an automatic recovery path.
   */
  @Synchronized
  fun resetAfterExplicitLocalHistoryDeletion() {
    if (!envelopeStore.clear()) {
      throw LocalSecretPersistenceException()
    }
    keyMaterial.deleteSourceHmacKey()
    keyMaterial.deleteWrappingKey()
  }

  companion object {
    const val GENERATOR_SECRET_BYTES = 32
  }
}

class AndroidKeystoreMaterial : KeystoreMaterial {
  private val keyStore: KeyStore = fromKeystore {
    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
  }

  override fun sourceHmacKey(): SecretKey? = fromKeystore {
    keyStore.secretAt(SOURCE_HMAC_KEY_ALIAS)
  }

  override fun createSourceHmacKey(): SecretKey = sourceHmacKey() ?: fromKeystore {
    KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
      ANDROID_KEYSTORE,
    ).run {
      init(
        KeyGenParameterSpec.Builder(
          SOURCE_HMAC_KEY_ALIAS,
          KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
          .setKeySize(HMAC_KEY_SIZE_BITS)
          .build(),
      )
      generateKey()
    }
  }

  override fun wrappingKey(): SecretKey? = fromKeystore {
    keyStore.secretAt(WRAPPING_KEY_ALIAS)
  }

  override fun createWrappingKey(): SecretKey = wrappingKey() ?: fromKeystore {
    KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_AES,
      ANDROID_KEYSTORE,
    ).run {
      init(
        KeyGenParameterSpec.Builder(
          WRAPPING_KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(AES_KEY_SIZE_BITS)
          .setRandomizedEncryptionRequired(true)
          .build(),
      )
      generateKey()
    }
  }

  override fun encrypt(
    plaintext: ByteArray,
    wrappingKey: SecretKey,
  ): EncryptedLocalSecret = fromKeystore {
    Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
      init(Cipher.ENCRYPT_MODE, wrappingKey)
      EncryptedLocalSecret(
        initializationVector = iv,
        ciphertext = doFinal(plaintext),
      )
    }
  }

  override fun decrypt(
    encryptedSecret: EncryptedLocalSecret,
    wrappingKey: SecretKey,
  ): ByteArray = fromKeystore {
    Cipher.getInstance(AES_GCM_TRANSFORMATION).run {
      init(
        Cipher.DECRYPT_MODE,
        wrappingKey,
        GCMParameterSpec(GCM_TAG_LENGTH_BITS, encryptedSecret.initializationVector),
      )
      doFinal(encryptedSecret.ciphertext)
    }
  }

  override fun deleteSourceHmacKey() {
    fromKeystore { keyStore.deleteEntry(SOURCE_HMAC_KEY_ALIAS) }
  }

  override fun deleteWrappingKey() {
    fromKeystore { keyStore.deleteEntry(WRAPPING_KEY_ALIAS) }
  }

  private fun KeyStore.secretAt(alias: String): SecretKey? =
    (getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

  private inline fun <T> fromKeystore(block: () -> T): T = try {
    block()
  } catch (_: Exception) {
    throw LocalSecretUnavailableException()
  }

  private companion object {
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val SOURCE_HMAC_KEY_ALIAS = "afterchime.source-hmac.v1"
    const val WRAPPING_KEY_ALIAS = "afterchime.generator-wrap.v1"
    const val HMAC_KEY_SIZE_BITS = 256
    const val AES_KEY_SIZE_BITS = 256
    const val GCM_TAG_LENGTH_BITS = 128
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
  }
}

class SharedPreferencesLocalSecretEnvelopeStore(
  context: Context,
) : LocalSecretEnvelopeStore {
  private val preferences: SharedPreferences = context.getSharedPreferences(
    PREFERENCES_FILE,
    Context.MODE_PRIVATE,
  )

  override fun read(): EncryptedLocalSecret? {
    val encodedInitializationVector = preferences.getString(INITIALIZATION_VECTOR_KEY, null)
    val encodedCiphertext = preferences.getString(CIPHERTEXT_KEY, null)
    if (encodedInitializationVector == null && encodedCiphertext == null) {
      return null
    }
    if (encodedInitializationVector == null || encodedCiphertext == null) {
      return EncryptedLocalSecret(ByteArray(0), ByteArray(0))
    }
    return try {
      EncryptedLocalSecret(
        initializationVector = Base64.decode(encodedInitializationVector, Base64.NO_WRAP),
        ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP),
      )
    } catch (_: IllegalArgumentException) {
      EncryptedLocalSecret(ByteArray(0), ByteArray(0))
    }
  }

  override fun persist(encryptedSecret: EncryptedLocalSecret): Boolean = preferences.edit()
    .putString(INITIALIZATION_VECTOR_KEY, Base64.encodeToString(encryptedSecret.initializationVector, Base64.NO_WRAP))
    .putString(CIPHERTEXT_KEY, Base64.encodeToString(encryptedSecret.ciphertext, Base64.NO_WRAP))
    .putBoolean(INITIALIZED_KEY, true)
    .commit()

  override fun isInitialized(): Boolean = preferences.getBoolean(INITIALIZED_KEY, false)

  override fun clear(): Boolean = preferences.edit()
    .remove(INITIALIZATION_VECTOR_KEY)
    .remove(CIPHERTEXT_KEY)
    .remove(INITIALIZED_KEY)
    .commit()

  private companion object {
    const val PREFERENCES_FILE = "afterchime.protected-local-material"
    const val INITIALIZATION_VECTOR_KEY = "generator_secret_iv"
    const val CIPHERTEXT_KEY = "generator_secret_ciphertext"
    const val INITIALIZED_KEY = "initialized"
  }
}
