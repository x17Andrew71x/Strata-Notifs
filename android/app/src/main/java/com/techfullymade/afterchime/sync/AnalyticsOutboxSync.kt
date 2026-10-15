package com.techfullymade.afterchime.sync

import com.techfullymade.afterchime.analytics.AnalyticsEnvelopeDecryptor
import com.techfullymade.afterchime.data.local.dao.AnalyticsOutboxDao
import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiModels
import org.json.JSONArray
import org.json.JSONObject

internal interface AnalyticsOutboxSync {
  suspend fun discard(): OnlineSyncOutcome

  suspend fun upload(
    installationId: String,
    bearerToken: String,
  ): OnlineSyncOutcome
}

internal interface AnalyticsUploadStore {
  suspend fun oldest(limit: Int): List<AnalyticsOutboxEntity>

  suspend fun deleteAll(eventIds: List<String>): Int

  suspend fun clear(): Int
}

internal class RoomAnalyticsUploadStore(
  private val dao: AnalyticsOutboxDao,
) : AnalyticsUploadStore {
  override suspend fun oldest(limit: Int): List<AnalyticsOutboxEntity> = dao.oldest(limit)

  override suspend fun deleteAll(eventIds: List<String>): Int = dao.deleteAll(eventIds)

  override suspend fun clear(): Int = dao.clear()
}

/** Decrypts only a bounded consented batch and deletes it only after exact server acknowledgement. */
internal class AnalyticsOutboxUploader(
  private val store: AnalyticsUploadStore,
  private val decryptor: AnalyticsEnvelopeDecryptor,
  private val api: OnlineSyncApi,
) : AnalyticsOutboxSync {
  override suspend fun discard(): OnlineSyncOutcome = try {
    store.clear()
    OnlineSyncOutcome.SUCCESS
  } catch (_: Exception) {
    OnlineSyncOutcome.RETRY
  }

  override suspend fun upload(
    installationId: String,
    bearerToken: String,
  ): OnlineSyncOutcome {
    val rows = try {
      store.oldest(MAX_EVENTS)
    } catch (_: Exception) {
      return OnlineSyncOutcome.RETRY
    }
    if (rows.isEmpty()) return OnlineSyncOutcome.SUCCESS
    if (rows.size > MAX_EVENTS) return OnlineSyncOutcome.FAILURE

    val events = JSONArray()
    val ids = mutableListOf<String>()
    for (row in rows) {
      if (row.ciphertext.size !in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
        return OnlineSyncOutcome.FAILURE
      }
      val plaintext = try {
        decryptor.decrypt(row.ciphertext)
      } catch (_: Exception) {
        return OnlineSyncOutcome.FAILURE
      }
      try {
        if (plaintext.isEmpty() || plaintext.size > MAX_PLAINTEXT_BYTES) {
          return OnlineSyncOutcome.FAILURE
        }
        val event = JSONObject(plaintext.toString(Charsets.UTF_8))
        ApiModels.validateAnalyticsEnvelope(event.toString())
        if (
          event.getString("event_id") != row.eventId ||
          event.getString("installation_id") != installationId ||
          event.getInt("consent_scope_version") != CONSENT_SCOPE_VERSION
        ) {
          return OnlineSyncOutcome.FAILURE
        }
        events.put(event)
        val candidate = JSONObject().put("events", events).toString()
        if (candidate.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
          events.remove(events.length() - 1)
          if (events.length() == 0) return OnlineSyncOutcome.FAILURE
          break
        }
        ids += row.eventId
      } catch (_: Exception) {
        return OnlineSyncOutcome.FAILURE
      } finally {
        plaintext.fill(0)
      }
    }
    if (ids.isEmpty()) return OnlineSyncOutcome.FAILURE

    val body = JSONObject().put("events", events).toString()
    val response = api.submitAnalytics(body, bearerToken)
    response.failure?.let { return apiFailureOutcome(it) }
    val results = response.value?.results ?: return OnlineSyncOutcome.FAILURE
    if (
      results.size != ids.size ||
      results.map { it.eventId } != ids ||
      results.map { it.eventId }.distinct().size != ids.size ||
      results.any { it.status !in setOf("accepted", "duplicate") }
    ) {
      return OnlineSyncOutcome.FAILURE
    }
    val deleted = try {
      store.deleteAll(ids)
    } catch (_: Exception) {
      return OnlineSyncOutcome.RETRY
    }
    return if (deleted == ids.size) OnlineSyncOutcome.SUCCESS else OnlineSyncOutcome.RETRY
  }

  private companion object {
    const val MAX_EVENTS = 50
    const val MAX_BODY_BYTES = 128 * 1_024
    const val MAX_PLAINTEXT_BYTES = 64 * 1_024
    const val MIN_ENVELOPE_BYTES = 12 + 16
    const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + MIN_ENVELOPE_BYTES
    const val CONSENT_SCOPE_VERSION = 1

    fun apiFailureOutcome(failure: ApiFailure): OnlineSyncOutcome = when (failure) {
      ApiFailure.Network,
      ApiFailure.Http,
      ApiFailure.Unauthorized,
      ApiFailure.ConsentInactive,
      -> OnlineSyncOutcome.RETRY
      ApiFailure.InvalidUrl,
      ApiFailure.UnsupportedSchemaVersion,
      ApiFailure.MalformedResponse,
      ApiFailure.Conflict,
      -> OnlineSyncOutcome.FAILURE
    }
  }
}
