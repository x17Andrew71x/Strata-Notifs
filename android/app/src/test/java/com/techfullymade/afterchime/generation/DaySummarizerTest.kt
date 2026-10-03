package com.techfullymade.afterchime.generation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DaySummarizerTest {
  @Test
  fun `summarizes an observed empty ordinary day as quiet`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      observation = ObservationCompleteness.OBSERVED,
    )

    assertEquals(100, summary.observationCompletenessPercent)
    assertEquals(0, summary.trueNotificationCount)
    assertEquals(0, summary.gamePressure)
    assertEquals(1_440, summary.dayDurationMinutes)
    assertEquals(1_440, summary.longestObservedQuietIntervalMinutes)
    assertEquals(emptyMap<Int, Int>(), summary.hourlyCounts)
    assertEquals(emptyMap<CoarseNotificationCategory, Int>(), summary.categoryCounts)
  }

  @Test
  fun `unobserved days retain recorded counts without being interpreted as quiet`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      observation = ObservationCompleteness.UNOBSERVED,
      notifications = listOf(notification("2026-10-03T10:00:00Z", "a", CoarseNotificationCategory.MESSAGE)),
    )

    assertEquals(0, summary.observationCompletenessPercent)
    assertEquals(1, summary.trueNotificationCount)
    assertEquals(1, summary.gamePressure)
    assertEquals(0, summary.longestObservedQuietIntervalMinutes)
  }

  @Test
  fun `preserves sparse daily shape and true category source counts`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      notifications = listOf(
        notification("2026-10-03T01:00:00Z", "a", CoarseNotificationCategory.EMAIL),
        notification("2026-10-03T12:00:00Z", "b", CoarseNotificationCategory.MESSAGE),
        notification("2026-10-03T23:00:00Z", "c", CoarseNotificationCategory.EMAIL),
      ),
    )

    assertEquals(3, summary.trueNotificationCount)
    assertEquals(3, summary.gamePressure)
    assertEquals(mapOf(1 to 1, 12 to 1, 23 to 1), summary.hourlyCounts)
    assertEquals(mapOf(CoarseNotificationCategory.EMAIL to 2, CoarseNotificationCategory.MESSAGE to 1), summary.categoryCounts)
    assertEquals(3, summary.sourceDiversity)
    assertEquals(660, summary.longestObservedQuietIntervalMinutes)
    assertEquals(2, summary.nightNotificationCount)
    assertEquals(1, summary.dayNotificationCount)
  }

  @Test
  fun `caps repeated source activity per elapsed hour while retaining true local count`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      notifications = buildList {
        repeat(6) { minute ->
          add(notification("2026-10-03T09:%02d:00Z".format(minute), "a", CoarseNotificationCategory.SOCIAL))
        }
        repeat(5) { minute ->
          add(notification("2026-10-03T10:%02d:00Z".format(minute), "a", CoarseNotificationCategory.SOCIAL))
        }
        repeat(2) { minute ->
          add(notification("2026-10-03T09:%02d:30Z".format(30 + minute), "b", CoarseNotificationCategory.MESSAGE))
        }
      },
    )

    assertEquals(13, summary.trueNotificationCount)
    assertEquals(mapOf(9 to 8, 10 to 5), summary.hourlyCounts)
    assertEquals(8, summary.gamePressure)
    assertEquals(2, summary.sourceDiversity)
  }

  @Test
  fun `bounds game pressure at the server contract maximum`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      notifications = (0 until 101).map { index ->
        notification(
          "2026-10-03T12:%02d:00Z".format(index % 60),
          "source-$index",
          CoarseNotificationCategory.EVENT,
        )
      },
    )

    assertEquals(101, summary.trueNotificationCount)
    assertEquals(100, summary.gamePressure)
  }

  @Test
  fun `records burst distribution separately from the capped pressure`() {
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      notifications = listOf(
        notification("2026-10-03T10:00:00Z", "a", CoarseNotificationCategory.EMAIL),
        notification("2026-10-03T10:05:00Z", "b", CoarseNotificationCategory.EMAIL),
        notification("2026-10-03T10:14:00Z", "c", CoarseNotificationCategory.EMAIL),
        notification("2026-10-03T10:16:00Z", "d", CoarseNotificationCategory.EMAIL),
      ),
    )

    assertEquals(4, summary.peakHourlyCount)
    assertEquals(3, summary.maxEventsInFifteenMinuteWindow)
    assertEquals(4, summary.gamePressure)
  }

  @Test
  fun `summarizes all approved categories without source identities`() {
    val categories = CoarseNotificationCategory.entries
    val summary = summarize(
      localDate = "2026-10-03",
      timeZone = ZoneId.of("UTC"),
      notifications = categories.mapIndexed { index, category ->
        notification("2026-10-03T%02d:00:00Z".format(index), "source-$index", category)
      },
    )

    assertEquals(categories.size, summary.trueNotificationCount)
    assertEquals(categories.size, summary.categoryCounts.size)
    assertEquals(categories.associateWith { 1 }, summary.categoryCounts)
    assertEquals(categories.size, summary.sourceDiversity)
    assertEquals(7, summary.nightNotificationCount)
    assertEquals(5, summary.dayNotificationCount)
  }

  @Test
  fun `uses the actual shorter DST-forward day boundary`() {
    val summary = summarize(
      localDate = "2026-03-08",
      timeZone = ZoneId.of("America/Los_Angeles"),
      notifications = listOf(
        notification("2026-03-08T09:30:00Z", "a", CoarseNotificationCategory.EVENT),
        notification("2026-03-08T10:30:00Z", "b", CoarseNotificationCategory.EVENT),
      ),
    )

    assertEquals(1_380, summary.dayDurationMinutes)
    assertEquals(mapOf(1 to 1, 3 to 1), summary.hourlyCounts)
  }

  @Test
  fun `keeps both repeated local DST-back hours without merging game windows`() {
    val summary = summarize(
      localDate = "2026-11-01",
      timeZone = ZoneId.of("America/Los_Angeles"),
      notifications = listOf(
        notification("2026-11-01T08:15:00Z", "a", CoarseNotificationCategory.EVENT),
        notification("2026-11-01T09:15:00Z", "a", CoarseNotificationCategory.EVENT),
      ),
    )

    assertEquals(1_500, summary.dayDurationMinutes)
    assertEquals(mapOf(1 to 2), summary.hourlyCounts)
    assertEquals(2, summary.trueNotificationCount)
    assertEquals(2, summary.gamePressure)
  }

  @Test
  fun `anchors travel fixtures to the requested local day and time zone`() {
    val event = notification("2026-07-04T06:30:00Z", "a", CoarseNotificationCategory.NAVIGATION)

    val losAngelesFourth = summarize(
      localDate = "2026-07-04",
      timeZone = ZoneId.of("America/Los_Angeles"),
      notifications = listOf(event),
    )
    val newYorkFourth = summarize(
      localDate = "2026-07-04",
      timeZone = ZoneId.of("America/New_York"),
      notifications = listOf(event),
    )

    assertEquals(0, losAngelesFourth.trueNotificationCount)
    assertEquals(1, newYorkFourth.trueNotificationCount)
    assertEquals(mapOf(2 to 1), newYorkFourth.hourlyCounts)
  }

  private fun summarize(
    localDate: String,
    timeZone: ZoneId,
    observation: ObservationCompleteness = ObservationCompleteness.OBSERVED,
    notifications: List<ReducedNotification> = emptyList(),
  ): DaySummary = DaySummarizer.summarize(
    localDate = LocalDate.parse(localDate),
    timeZone = timeZone,
    observationCompleteness = observation,
    notifications = notifications,
  )

  private fun notification(
    occurredAt: String,
    source: String,
    category: CoarseNotificationCategory,
  ): ReducedNotification = ReducedNotification(
    occurredAtEpochMillis = Instant.parse(occurredAt).toEpochMilli(),
    localHour = Instant.parse(occurredAt).atZone(ZoneId.of("UTC")).hour,
    category = category,
    sourceToken = source.hashCode().toUInt().toString(16).padStart(64, '0'),
    sourceColourRgb = 0,
  )
}
