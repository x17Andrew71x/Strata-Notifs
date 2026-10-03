package com.techfullymade.afterchime.capture

import com.techfullymade.afterchime.data.local.dao.ListenerAccessStateDao
import com.techfullymade.afterchime.data.local.dao.ReducedNotificationDao
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

class ObservationRepository(
  private val listenerAccessStateDao: ListenerAccessStateDao,
  private val reducedNotificationDao: ReducedNotificationDao,
  private val timeZone: ZoneId,
) {
  suspend fun recordListenerConnected(occurredAtEpochMillis: Long) {
    recordAccessState(ListenerAccessState.ACTIVE, occurredAtEpochMillis)
  }

  suspend fun recordListenerDisconnected(occurredAtEpochMillis: Long) {
    recordAccessState(ListenerAccessState.DISCONNECTED, occurredAtEpochMillis)
  }

  suspend fun recordNotificationAccessRevoked(occurredAtEpochMillis: Long) {
    recordAccessState(ListenerAccessState.REVOKED, occurredAtEpochMillis)
  }

  suspend fun recordReducedNotification(notification: ReducedNotification) {
    val localDate = localDateFor(notification.occurredAtEpochMillis)
    reducedNotificationDao.insertIfAbsent(
      ReducedNotificationEntity(
        id = reducedNotificationId(notification),
        occurredAtEpochMillis = notification.occurredAtEpochMillis,
        localDate = localDate,
        localHour = notification.localHour,
        category = notification.category,
        sourceToken = notification.sourceToken,
        sourceColourRgb = notification.sourceColourRgb,
      ),
    )
  }

  suspend fun listenerAccessStateFor(localDate: String): ListenerAccessStateEntity? =
    listenerAccessStateDao.get(localDate)

  private suspend fun recordAccessState(
    state: ListenerAccessState,
    occurredAtEpochMillis: Long,
  ) {
    require(occurredAtEpochMillis >= 0)
    val localDate = localDateFor(occurredAtEpochMillis)
    val existing = listenerAccessStateDao.get(localDate)
    if (existing != null && occurredAtEpochMillis < existing.updatedAtEpochMillis) {
      return
    }

    listenerAccessStateDao.upsert(
      when (state) {
        ListenerAccessState.ACTIVE -> ListenerAccessStateEntity(
          localDate = localDate,
          activeAtEpochMillis = existing?.activeAtEpochMillis ?: occurredAtEpochMillis,
          disconnectedAtEpochMillis = existing?.disconnectedAtEpochMillis,
          revokedAtEpochMillis = existing?.revokedAtEpochMillis,
          latestState = ListenerAccessState.ACTIVE,
          updatedAtEpochMillis = occurredAtEpochMillis,
        )

        ListenerAccessState.DISCONNECTED -> ListenerAccessStateEntity(
          localDate = localDate,
          activeAtEpochMillis = existing?.activeAtEpochMillis,
          disconnectedAtEpochMillis = occurredAtEpochMillis,
          revokedAtEpochMillis = existing?.revokedAtEpochMillis,
          latestState = ListenerAccessState.DISCONNECTED,
          updatedAtEpochMillis = occurredAtEpochMillis,
        )

        ListenerAccessState.REVOKED -> ListenerAccessStateEntity(
          localDate = localDate,
          activeAtEpochMillis = existing?.activeAtEpochMillis,
          disconnectedAtEpochMillis = existing?.disconnectedAtEpochMillis,
          revokedAtEpochMillis = occurredAtEpochMillis,
          latestState = ListenerAccessState.REVOKED,
          updatedAtEpochMillis = occurredAtEpochMillis,
        )
      },
    )
  }

  private fun localDateFor(occurredAtEpochMillis: Long): String =
    Instant.ofEpochMilli(occurredAtEpochMillis).atZone(timeZone).toLocalDate().toString()

  private fun reducedNotificationId(notification: ReducedNotification): String {
    val material = listOf(
      REDUCED_NOTIFICATION_ID_DOMAIN,
      notification.occurredAtEpochMillis.toString(),
      notification.category.name,
      notification.sourceToken,
      notification.sourceColourRgb.toString(),
    ).joinToString(separator = "\u0000")
    return MessageDigest.getInstance("SHA-256")
      .digest(material.toByteArray(UTF_8))
      .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
  }

  private companion object {
    const val REDUCED_NOTIFICATION_ID_DOMAIN = "afterchime:reduced-notification:v1"
  }
}
