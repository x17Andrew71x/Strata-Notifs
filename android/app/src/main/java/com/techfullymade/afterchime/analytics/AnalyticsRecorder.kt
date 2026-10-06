package com.techfullymade.afterchime.analytics

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.techfullymade.afterchime.data.local.dao.AnalyticsOutboxDao
import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

fun interface AnalyticsConsentPolicy {
  fun permitsProductAnalytics(): Boolean
}

fun interface AnalyticsOutboxWriter {
  suspend fun insertIfAbsent(entity: AnalyticsOutboxEntity): Long
}

fun interface AnalyticsEnvelopeCipher {
  fun encrypt(plaintext: ByteArray): ByteArray
}

enum class AnalyticsRecordResult { RECORDED, CONSENT_DISABLED, UNAVAILABLE }

/** Explicitly invoked, consent-gated local recorder. It has no network or scheduling behavior. */
class AnalyticsRecorder(
  private val writer: AnalyticsOutboxWriter,
  private val cipher: AnalyticsEnvelopeCipher = AndroidAnalyticsEnvelopeCipher(),
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newEventId: () -> String = { UUID.randomUUID().toString() },
) {
  constructor(dao: AnalyticsOutboxDao) : this(AnalyticsOutboxWriter(dao::insertIfAbsent))

  suspend fun record(
    event: AnalyticsEvent,
    context: AnalyticsContext,
    consent: AnalyticsConsentPolicy,
  ): AnalyticsRecordResult {
    if (!try { consent.permitsProductAnalytics() } catch (_: Exception) { false }) {
      return AnalyticsRecordResult.CONSENT_DISABLED
    }
    var plaintext: ByteArray? = null
    return try {
      val eventId = newEventId()
      val envelope = AnalyticsRegistry.envelope(event, context.copy(eventId = eventId))
      plaintext = envelope.toByteArray(Charsets.UTF_8)
      val encrypted = cipher.encrypt(plaintext)
      if (encrypted.isEmpty()) return AnalyticsRecordResult.UNAVAILABLE
      writer.insertIfAbsent(AnalyticsOutboxEntity(eventId, encrypted, nowMillis()))
      AnalyticsRecordResult.RECORDED
    } catch (_: Exception) {
      AnalyticsRecordResult.UNAVAILABLE
    } finally {
      plaintext?.fill(0)
    }
  }
}

/** Dedicated Android Keystore key; database values are IV concatenated with authenticated ciphertext. */
class AndroidAnalyticsEnvelopeCipher : AnalyticsEnvelopeCipher {
  override fun encrypt(plaintext: ByteArray): ByteArray {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.iv + cipher.doFinal(plaintext)
  }

  private fun createKey(): SecretKey = KeyGenerator.getInstance("AES", KEYSTORE).run {
    init(KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .build())
    generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "afterchime.analytics-outbox.v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}
