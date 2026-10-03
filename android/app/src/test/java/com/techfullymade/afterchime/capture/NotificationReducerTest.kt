package com.techfullymade.afterchime.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import java.time.Instant
import java.time.ZoneId
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationReducerTest {
  @Test
  fun `reduces an eligible notification to approved local fields`() {
    val sourcePackage = packageName("mail")
    val occurredAt = Instant.parse("2026-10-03T09:45:00Z").toEpochMilli()

    val reduced = reducer().reduce(notification(sourcePackage, "email", occurredAt))

    requireNotNull(reduced)
    assertEquals(occurredAt, reduced.occurredAtEpochMillis)
    assertEquals(9, reduced.localHour)
    assertEquals(CoarseNotificationCategory.EMAIL, reduced.category)
    assertTrue(reduced.sourceToken.matches(Regex("[0-9a-f]{64}")))
    assertTrue(reduced.sourceColourRgb in 0..0xFFFFFF)
    assertFalse(reduced.toString().contains(sourcePackage))
  }

  @Test
  @Suppress("DEPRECATION")
  fun `direct Android adapter ignores content-bearing payloads`() {
    val application = RuntimeEnvironment.getApplication()
    val payloads = listOf(
      fixtureValue("title"),
      fixtureValue("text"),
      fixtureValue("sender"),
      fixtureValue("message"),
      fixtureValue("action"),
      fixtureValue("reply"),
    )
    val action = Notification.Action.Builder(
      Icon.createWithResource(application, android.R.drawable.ic_dialog_info),
      payloads[4],
      PendingIntent.getBroadcast(
        application,
        0,
        Intent("com.example.afterchime.fixture"),
        PendingIntent.FLAG_IMMUTABLE,
      ),
    )
      .addRemoteInput(RemoteInput.Builder("fixture-response").setLabel(payloads[5]).build())
      .build()
    val notification = Notification.Builder(application, "fixture-channel")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setCategory(Notification.CATEGORY_EMAIL)
      .setContentTitle(payloads[0])
      .setContentText(payloads[1])
      .setTicker(payloads[2])
      .setLargeIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
      .addExtras(
        Bundle().apply {
          putCharSequence(Notification.EXTRA_TITLE, payloads[0])
          putCharSequence(Notification.EXTRA_TEXT, payloads[1])
          putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf(payloads[3]))
        },
      )
      .addAction(action)
      .build()
    val sourcePackage = packageName("mail")

    val reduced = requireNotNull(
      reducer().reduce(
        StatusBarNotification(
          sourcePackage,
          sourcePackage,
          1,
          "fixture",
          0,
          0,
          0,
          notification,
          Process.myUserHandle(),
          1_000L,
        ),
      ),
    )

    assertEquals(1_000L, reduced.occurredAtEpochMillis)
    assertEquals(CoarseNotificationCategory.EMAIL, reduced.category)
    payloads.forEach { payload -> assertFalse(reduced.toString().contains(payload)) }
    assertFalse(reduced.toString().contains(sourcePackage))
  }

  @Test
  fun `source token and colour are stable locally but keyed`() {
    val sourcePackage = packageName("calendar")
    val first = requireNotNull(reducer().reduce(notification(sourcePackage, "event")))
    val repeated = requireNotNull(reducer().reduce(notification(sourcePackage, "event")))
    val differentSource = requireNotNull(reducer().reduce(notification(packageName("music"), "event")))
    val differentKey = requireNotNull(reducer("replacement-key").reduce(notification(sourcePackage, "event")))

    assertEquals(first.sourceToken, repeated.sourceToken)
    assertEquals(first.sourceColourRgb, repeated.sourceColourRgb)
    assertNotEquals(first.sourceToken, differentSource.sourceToken)
    assertNotEquals(first.sourceToken, differentKey.sourceToken)
  }

  @Test
  fun `maps only the server approved category set`() {
    val cases = mapOf(
      "alarm" to CoarseNotificationCategory.ALARM,
      "call" to CoarseNotificationCategory.CALL,
      "email" to CoarseNotificationCategory.EMAIL,
      "event" to CoarseNotificationCategory.EVENT,
      "msg" to CoarseNotificationCategory.MESSAGE,
      "navigation" to CoarseNotificationCategory.NAVIGATION,
      "progress" to CoarseNotificationCategory.PROGRESS,
      "reminder" to CoarseNotificationCategory.REMINDER,
      "social" to CoarseNotificationCategory.SOCIAL,
      "transport" to CoarseNotificationCategory.TRANSPORT,
      "workout" to CoarseNotificationCategory.WORKOUT,
      "promo" to CoarseNotificationCategory.OTHER,
      null to CoarseNotificationCategory.OTHER,
    )

    cases.forEach { (rawCategory, expected) ->
      val reduced = requireNotNull(reducer().reduce(notification(packageName("source"), rawCategory)))
      assertEquals(expected, reduced.category)
    }
  }

  @Test
  fun `filters own group ongoing system and configured excluded notifications`() {
    val standardReducer = reducer()

    assertNull(standardReducer.reduce(notification(packageName("app"), "email")))
    assertNull(standardReducer.reduce(notification(packageName("other"), "email", isGroupSummary = true)))
    assertNull(standardReducer.reduce(notification(packageName("other"), "email", isOngoing = true)))
    assertNull(standardReducer.reduce(notification(packageName("other"), "sys")))
    assertNull(
      reducer(excludedRawCategories = setOf("social")).reduce(notification(packageName("other"), "social")),
    )
    assertTrue(standardReducer.reduce(notification(packageName("application"), "email")) != null)
  }

  @Test
  fun `unknown oversized category collapses to other without appearing in reduced output`() {
    val rawCategory = "opaque-" + "x".repeat(131_072)

    val reduced = requireNotNull(reducer().reduce(notification(packageName("source"), rawCategory)))

    assertEquals(CoarseNotificationCategory.OTHER, reduced.category)
    assertFalse(reduced.toString().contains(rawCategory))
  }

  private fun reducer(
    keyMaterial: String = "fixture-key-material-that-is-long-enough",
    excludedRawCategories: Set<String> = emptySet(),
  ): NotificationReducer = NotificationReducer(
    ownPackageName = packageName("app"),
    sourceHmacKey = SecretKeySpec(keyMaterial.toByteArray(), "HmacSHA256"),
    timeZone = ZoneId.of("UTC"),
    excludedRawCategories = excludedRawCategories,
  )

  private fun packageName(suffix: String): String = listOf("com", "example", suffix).joinToString(".")

  @Suppress("DEPRECATION")
  private fun notification(
    sourcePackage: String,
    rawCategory: String?,
    occurredAtEpochMillis: Long = 0L,
    isGroupSummary: Boolean = false,
    isOngoing: Boolean = false,
  ): StatusBarNotification {
    val application = RuntimeEnvironment.getApplication()
    val notification = Notification.Builder(application, "fixture-channel")
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .apply {
        if (rawCategory != null) {
          setCategory(rawCategory)
        }
        if (isGroupSummary) {
          setGroupSummary(true)
        }
        if (isOngoing) {
          setOngoing(true)
        }
      }
      .build()
    return StatusBarNotification(
      sourcePackage,
      sourcePackage,
      1,
      "fixture",
      0,
      0,
      0,
      notification,
      Process.myUserHandle(),
      occurredAtEpochMillis,
    )
  }

  private fun fixtureValue(label: String): String = "redacted-$label-${"x".repeat(32)}"
}
