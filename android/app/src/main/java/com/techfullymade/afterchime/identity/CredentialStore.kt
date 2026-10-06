package com.techfullymade.afterchime.identity

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Only this complete, encrypted session is eligible for durable storage. */
data class CredentialSession(
  val installationId: String,
  val accessToken: String,
  val refreshToken: String,
  val expiresAtEpochMillis: Long,
)

sealed interface CredentialRead {
  data object Missing : CredentialRead
  data class Available(val session: CredentialSession) : CredentialRead
  data object Unavailable : CredentialRead
}

/** Testable durable boundary. Implementations must replace or remove the envelope atomically. */
interface CredentialEnvelopeStorage {
  fun read(): ByteArray?
  fun replace(envelope: ByteArray): Boolean
  fun clear(): Boolean
  fun isInitialized(): Boolean
  fun markInitialized(): Boolean
  fun clearInitialization(): Boolean
}

interface CredentialCipher {
  fun hasKey(): Boolean
  fun encrypt(plaintext: ByteArray): ByteArray
  fun decrypt(envelope: ByteArray): ByteArray
  fun deleteKey(): Boolean
}

/** Persists no plaintext: the file contains only the GCM IV followed by ciphertext and tag. */
class CredentialStore(
  private val storage: CredentialEnvelopeStorage,
  private val cipher: CredentialCipher,
) {
  @Synchronized
  fun read(): CredentialRead {
    return try {
      val envelope = storage.read()
      val initialized = storage.isInitialized()
      val hasKey = cipher.hasKey()
      if (envelope == null) return if (initialized || hasKey) {
        CredentialRead.Unavailable
      } else {
        CredentialRead.Missing
      }
      if (!initialized || !hasKey) return CredentialRead.Unavailable
      if (envelope.size !in (IV_BYTES + TAG_BYTES)..MAX_ENVELOPE_BYTES) return CredentialRead.Unavailable
      val plaintext = cipher.decrypt(envelope)
      try {
        val json = JSONObject(plaintext.toString(Charsets.UTF_8))
        if (json.keys().asSequence().toSet() != SESSION_FIELDS) return CredentialRead.Unavailable
        val session = CredentialSession(
          installationId = json.getString("installationId"),
          accessToken = json.getString("accessToken"),
          refreshToken = json.getString("refreshToken"),
          expiresAtEpochMillis = json.getLong("expiresAtEpochMillis"),
        )
        if (session.installationId.isBlank() || session.accessToken.isBlank() || session.refreshToken.isBlank() ||
          session.expiresAtEpochMillis <= 0L
        ) CredentialRead.Unavailable else CredentialRead.Available(session)
      } finally {
        plaintext.fill(0)
      }
    } catch (_: Exception) {
      CredentialRead.Unavailable
    }
  }

  @Synchronized
  fun save(session: CredentialSession): Boolean {
    val existing = read()
    if (existing == CredentialRead.Unavailable) return false
    if (session.installationId.isBlank() || session.accessToken.isBlank() || session.refreshToken.isBlank() ||
      session.expiresAtEpochMillis <= 0L
    ) return false
    val plaintext = JSONObject()
      .put("installationId", session.installationId)
      .put("accessToken", session.accessToken)
      .put("refreshToken", session.refreshToken)
      .put("expiresAtEpochMillis", session.expiresAtEpochMillis)
      .toString().toByteArray(Charsets.UTF_8)
    return try {
      val envelope = cipher.encrypt(plaintext)
      if (envelope.size !in (IV_BYTES + TAG_BYTES)..MAX_ENVELOPE_BYTES) {
        false
      } else if (existing == CredentialRead.Missing && !storage.markInitialized()) {
        false
      } else {
        storage.replace(envelope)
      }
    } catch (_: Exception) {
      false
    } finally {
      plaintext.fill(0)
    }
  }

  @Synchronized
  fun clear(): Boolean = try {
    storage.clear() && cipher.deleteKey() && storage.clearInitialization()
  } catch (_: Exception) {
    false
  }

  companion object {
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val MAX_ENVELOPE_BYTES = 16_384
    private val SESSION_FIELDS = setOf("installationId", "accessToken", "refreshToken", "expiresAtEpochMillis")
  }
}

class AndroidCredentialCipher : CredentialCipher {
  private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

  @Synchronized
  override fun hasKey(): Boolean = keyStore.getKey(KEY_ALIAS, null) is SecretKey

  @Synchronized
  override fun encrypt(plaintext: ByteArray): ByteArray {
    val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: createKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.iv + cipher.doFinal(plaintext)
  }

  @Synchronized
  override fun decrypt(envelope: ByteArray): ByteArray {
    require(envelope.size >= IV_LENGTH + TAG_LENGTH_BYTES)
    val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw IllegalStateException()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, envelope.copyOfRange(0, IV_LENGTH)))
    return cipher.doFinal(envelope.copyOfRange(IV_LENGTH, envelope.size))
  }

  @Synchronized
  override fun deleteKey(): Boolean {
    if (!keyStore.containsAlias(KEY_ALIAS)) return true
    keyStore.deleteEntry(KEY_ALIAS)
    return !keyStore.containsAlias(KEY_ALIAS)
  }

  private fun createKey(): SecretKey = KeyGenerator.getInstance("AES", KEYSTORE).run {
    init(android.security.keystore.KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
    ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .build())
    generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "afterchime.online-credentials.v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_LENGTH = 12
    const val TAG_LENGTH_BITS = 128
    const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
  }
}

/** AtomicFile gives crash-safe replacement; its contents are always ciphertext. */
class AtomicCredentialEnvelopeStorage(context: Context) : CredentialEnvelopeStorage {
  private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
  private val initializationFile = AtomicFile(File(context.noBackupFilesDir, INITIALIZATION_FILE_NAME))

  @Synchronized
  override fun read(): ByteArray? = if (file.baseFile.exists()) file.openRead().use { input ->
    val bytes = ByteArrayOutputStream()
    input.copyTo(bytes)
    bytes.toByteArray()
  } else null

  @Synchronized
  override fun replace(envelope: ByteArray): Boolean {
    var output: java.io.FileOutputStream? = null
    return try {
      output = file.startWrite()
      output.write(envelope)
      file.finishWrite(output)
      true
    } catch (_: Exception) {
      output?.let(file::failWrite)
      false
    }
  }

  @Synchronized
  override fun clear(): Boolean = try {
    file.delete()
    true
  } catch (_: Exception) {
    false
  }

  @Synchronized
  override fun isInitialized(): Boolean = initializationFile.baseFile.exists()

  @Synchronized
  override fun markInitialized(): Boolean {
    if (initializationFile.baseFile.exists()) return true
    var output: java.io.FileOutputStream? = null
    return try {
      output = initializationFile.startWrite()
      output.write(INITIALIZATION_MARKER)
      initializationFile.finishWrite(output)
      true
    } catch (_: Exception) {
      output?.let(initializationFile::failWrite)
      false
    }
  }

  @Synchronized
  override fun clearInitialization(): Boolean = try {
    initializationFile.delete()
    true
  } catch (_: Exception) {
    false
  }

  private companion object {
    const val FILE_NAME = "afterchime-online-credentials.enc"
    const val INITIALIZATION_FILE_NAME = "afterchime-online-credentials.initialized"
    val INITIALIZATION_MARKER = byteArrayOf(1)
  }
}
