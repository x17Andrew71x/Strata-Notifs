package com.techfullymade.afterchime.aggregation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DailyNotificationSummaryTest {
  private val localDate = LocalDate.parse("2026-10-09")
  private val timezoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
  private val sourceA = "a".repeat(64)
  private val sourceB = "b".repeat(64)
  private val notifications = listOf(
    notification(
      occurredAtEpochMillis = 0L,
      localHour = 9,
      category = CoarseNotificationCategory.MESSAGE,
      sourceToken = sourceA,
      sourceColourRgb = 0x65ABCD,
    ),
    notification(
      occurredAtEpochMillis = 60_000L,
      localHour = 10,
      category = CoarseNotificationCategory.EMAIL,
      sourceToken = sourceA,
      sourceColourRgb = 0x123456,
    ),
    notification(
      occurredAtEpochMillis = 60_000L,
      localHour = 10,
      category = CoarseNotificationCategory.SOCIAL,
      sourceToken = sourceB,
      sourceColourRgb = 0xABCDEF,
    ),
    notification(
      occurredAtEpochMillis = 300_000L,
      localHour = 11,
      category = CoarseNotificationCategory.MESSAGE,
      sourceToken = sourceA,
      sourceColourRgb = 0xFEDCBA,
    ),
  )

  @Test
  fun `accepted events are conserved by category and sorted non-empty hour`() {
    val summary = calculate(uninterruptedObservation = true)

    assertEquals(1, summary.revision)
    assertEquals(1, summary.rulesVersion)
    assertEquals(3, summary.eligibleCount)
    assertEquals(
      listOf(
        DailyNotificationHourTotal(localHour = 9, eligibleCount = 1),
        DailyNotificationHourTotal(localHour = 10, eligibleCount = 1),
        DailyNotificationHourTotal(localHour = 11, eligibleCount = 1),
      ),
      summary.hourlyBuckets,
    )
    assertEquals(3, summary.hourlyBuckets.sumOf(DailyNotificationHourTotal::eligibleCount))
    assertEquals(3, summary.categoryTotals.sumOf(DailyNotificationCategoryTotal::eligibleCount))
    assertEquals(
      2,
      summary.categoryTotals.single { it.category == CoarseNotificationCategory.MESSAGE }.eligibleCount,
    )
    assertEquals(
      1,
      summary.categoryTotals.single { it.category == CoarseNotificationCategory.SOCIAL }.eligibleCount,
    )
    assertEquals(
      0,
      summary.categoryTotals.single { it.category == CoarseNotificationCategory.EMAIL }.eligibleCount,
    )
    assertEquals(CoarseNotificationCategory.values().size, summary.categoryTotals.size)
  }

  @Test
  fun `input order does not change daily totals`() {
    val chronological = calculate(uninterruptedObservation = true)
    val reversed = DailyNotificationSummaryCalculator.calculate(
      localDate = localDate,
      timezoneOffset = timezoneOffset,
      uninterruptedObservation = true,
      notifications = notifications.reversed(),
    )

    assertEquals(chronological, reversed)
  }

  @Test
  fun `completeness reflects uninterrupted and interrupted observation`() {
    assertEquals(100, calculate(uninterruptedObservation = true).observationCompleteness)
    assertEquals(0, calculate(uninterruptedObservation = false).observationCompleteness)
  }

  @Test
  fun `output contains no source token or colour`() {
    val rendered = calculate(uninterruptedObservation = true).toString()

    assertFalse(rendered.contains(sourceA))
    assertFalse(rendered.contains(sourceB))
    notifications.forEach { notification ->
      assertFalse(rendered.contains(notification.sourceColourRgb.toString()))
    }
    assertFalse(rendered.contains("sourceToken"))
    assertFalse(rendered.contains("sourceColourRgb"))
  }

  @Test
  fun `contract fields preserve date offset and disabled game pressure`() {
    val summary = calculate(uninterruptedObservation = true)

    assertEquals(localDate, summary.localDate)
    assertEquals(timezoneOffset, summary.timezoneOffset)
    assertEquals(0, summary.gamePressure)
  }

  private fun calculate(uninterruptedObservation: Boolean): DailyNotificationSummary {
    return DailyNotificationSummaryCalculator.calculate(
      localDate = localDate,
      timezoneOffset = timezoneOffset,
      uninterruptedObservation = uninterruptedObservation,
      notifications = notifications,
    )
  }

  private fun notification(
    occurredAtEpochMillis: Long,
    localHour: Int,
    category: CoarseNotificationCategory,
    sourceToken: String,
    sourceColourRgb: Int,
  ): ReducedNotification {
    return ReducedNotification(
      occurredAtEpochMillis = occurredAtEpochMillis,
      localHour = localHour,
      category = category,
      sourceToken = sourceToken,
      sourceColourRgb = sourceColourRgb,
    )
  }
}
