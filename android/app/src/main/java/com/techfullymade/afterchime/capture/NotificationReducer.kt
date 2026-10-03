package com.techfullymade.afterchime.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.SecretKey

class NotificationReducer(
  private val ownPackageName: String,
  private val sourceHmacKey: SecretKey,
  private val timeZone: ZoneId,
  excludedRawCategories: Set<String> = emptySet(),
) {
  private val excludedRawCategories = excludedRawCategories + SYSTEM_CATEGORY

  init {
    require(ownPackageName.isNotBlank())
  }

  fun reduce(statusBarNotification: StatusBarNotification): ReducedNotification? {
    val rawPackageName = statusBarNotification.packageName
    if (rawPackageName == ownPackageName) {
      return null
    }

    val postedNotification = statusBarNotification.notification
    return reduce(
      sourceDigest = sourceDigest(rawPackageName),
      rawCategory = postedNotification.category,
      isGroupSummary = postedNotification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
      isOngoing = postedNotification.flags and Notification.FLAG_ONGOING_EVENT != 0,
      occurredAtEpochMillis = statusBarNotification.postTime,
    )
  }

  private fun reduce(
    sourceDigest: ByteArray,
    rawCategory: String?,
    isGroupSummary: Boolean,
    isOngoing: Boolean,
    occurredAtEpochMillis: Long,
  ): ReducedNotification? {
    if (
      isGroupSummary ||
      isOngoing ||
      rawCategory in excludedRawCategories
    ) {
      return null
    }

    return ReducedNotification(
      occurredAtEpochMillis = occurredAtEpochMillis,
      localHour = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(timeZone).hour,
      category = categoryFor(rawCategory),
      sourceToken = sourceDigest.toHex(),
      sourceColourRgb = sourceDigest.toColourRgb(),
    )
  }

  private fun sourceDigest(rawPackageName: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(sourceHmacKey)
    mac.update(SOURCE_TOKEN_DOMAIN)
    return mac.doFinal(rawPackageName.toByteArray(UTF_8))
  }

  private fun categoryFor(rawCategory: String?): CoarseNotificationCategory = when (rawCategory) {
    Notification.CATEGORY_ALARM -> CoarseNotificationCategory.ALARM
    Notification.CATEGORY_CALL -> CoarseNotificationCategory.CALL
    Notification.CATEGORY_EMAIL -> CoarseNotificationCategory.EMAIL
    Notification.CATEGORY_EVENT -> CoarseNotificationCategory.EVENT
    Notification.CATEGORY_MESSAGE -> CoarseNotificationCategory.MESSAGE
    Notification.CATEGORY_NAVIGATION -> CoarseNotificationCategory.NAVIGATION
    Notification.CATEGORY_PROGRESS -> CoarseNotificationCategory.PROGRESS
    Notification.CATEGORY_REMINDER -> CoarseNotificationCategory.REMINDER
    Notification.CATEGORY_SOCIAL -> CoarseNotificationCategory.SOCIAL
    Notification.CATEGORY_TRANSPORT -> CoarseNotificationCategory.TRANSPORT
    Notification.CATEGORY_WORKOUT -> CoarseNotificationCategory.WORKOUT
    else -> CoarseNotificationCategory.OTHER
  }

  private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xFF)
  }

  private fun ByteArray.toColourRgb(): Int =
    ((this[0].toInt() and 0xFF) shl 16) or
      ((this[1].toInt() and 0xFF) shl 8) or
      (this[2].toInt() and 0xFF)

  private companion object {
    val SOURCE_TOKEN_DOMAIN = "afterchime:source-token:v1\u0000".toByteArray(UTF_8)
    const val SYSTEM_CATEGORY = "sys"
  }
}
