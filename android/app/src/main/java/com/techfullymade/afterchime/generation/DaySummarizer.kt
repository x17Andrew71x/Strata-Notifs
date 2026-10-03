package com.techfullymade.afterchime.generation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DaySummarizer {
  /**
   * Reduces one local calendar day without retaining source identities in the returned summary.
   *
   * Game-pressure windows are elapsed UTC hours, deliberately distinct from local clock hours so a
   * repeated DST hour cannot merge two independent source caps. Local hourly counts remain display
   * and generator input, including both occurrences of a DST-back hour.
   */
  fun summarize(
    localDate: LocalDate,
    timeZone: ZoneId,
    observationCompleteness: ObservationCompleteness,
    notifications: List<ReducedNotification>,
  ): DaySummary {
    val dayStart = localDate.atStartOfDay(timeZone).toInstant()
    val dayEnd = localDate.plusDays(1).atStartOfDay(timeZone).toInstant()
    val notificationsInDay = notifications
      .asSequence()
      .map { notification -> TimedNotification(notification, Instant.ofEpochMilli(notification.occurredAtEpochMillis)) }
      .filter { it.occurredAt >= dayStart && it.occurredAt < dayEnd }
      .sortedBy(TimedNotification::occurredAt)
      .toList()

    val hourlyCounts = notificationsInDay
      .groupingBy { it.occurredAt.atZone(timeZone).hour }
      .eachCount()
      .toSortedMap()
    val categoryCounts = notificationsInDay
      .groupingBy { it.notification.category }
      .eachCount()
      .toSortedMap(compareBy(CoarseNotificationCategory::name))
    val trueNotificationCount = notificationsInDay.size

    return DaySummary(
      localDate = localDate,
      observationCompleteness = observationCompleteness,
      trueNotificationCount = trueNotificationCount,
      gamePressure = gamePressureFor(notificationsInDay),
      hourlyCounts = hourlyCounts,
      categoryCounts = categoryCounts,
      sourceDiversity = notificationsInDay.map(TimedNotification::notification).map(ReducedNotification::sourceToken).toSet().size,
      dayDurationMinutes = Duration.between(dayStart, dayEnd).toMinutes().toInt(),
      longestObservedQuietIntervalMinutes = longestQuietIntervalMinutes(
        dayStart = dayStart,
        dayEnd = dayEnd,
        notifications = notificationsInDay,
        observationCompleteness = observationCompleteness,
      ),
      peakHourlyCount = hourlyCounts.values.maxOrNull() ?: 0,
      maxEventsInFifteenMinuteWindow = maxEventsInFifteenMinuteWindow(notificationsInDay),
      dayNotificationCount = notificationsInDay.count { it.occurredAt.atZone(timeZone).hour in DAY_HOURS },
      nightNotificationCount = notificationsInDay.count { it.occurredAt.atZone(timeZone).hour in NIGHT_HOURS },
    )
  }

  private fun gamePressureFor(notifications: List<TimedNotification>): Int =
    notifications
      .groupBy { notification ->
        SourceWindow(
          sourceToken = notification.notification.sourceToken,
          elapsedHour = Math.floorDiv(notification.notification.occurredAtEpochMillis, HOUR_MILLIS),
        )
      }
      .values
      .sumOf { notificationsForSourceWindow ->
        minOf(notificationsForSourceWindow.size, GAME_PRESSURE_PER_SOURCE_HOUR_CAP)
      }
      .coerceAtMost(DaySummary.MAX_GAME_PRESSURE)

  private fun longestQuietIntervalMinutes(
    dayStart: Instant,
    dayEnd: Instant,
    notifications: List<TimedNotification>,
    observationCompleteness: ObservationCompleteness,
  ): Int {
    if (observationCompleteness == ObservationCompleteness.UNOBSERVED) {
      return 0
    }

    var previous = dayStart
    var longestMinutes = 0L
    notifications.forEach { notification ->
      longestMinutes = maxOf(longestMinutes, Duration.between(previous, notification.occurredAt).toMinutes())
      previous = notification.occurredAt
    }
    longestMinutes = maxOf(longestMinutes, Duration.between(previous, dayEnd).toMinutes())
    return longestMinutes.toInt()
  }

  private fun maxEventsInFifteenMinuteWindow(notifications: List<TimedNotification>): Int {
    var maximum = 0
    var endIndex = 0

    notifications.forEachIndexed { startIndex, notification ->
      val exclusiveWindowEnd = notification.occurredAt.plusSeconds(FIFTEEN_MINUTES_SECONDS)
      while (endIndex < notifications.size && notifications[endIndex].occurredAt < exclusiveWindowEnd) {
        endIndex += 1
      }
      maximum = maxOf(maximum, endIndex - startIndex)
    }

    return maximum
  }

  private data class TimedNotification(
    val notification: ReducedNotification,
    val occurredAt: Instant,
  )

  private data class SourceWindow(
    val sourceToken: String,
    val elapsedHour: Long,
  )

  private const val GAME_PRESSURE_PER_SOURCE_HOUR_CAP = 3
  private const val HOUR_MILLIS = 60L * 60L * 1_000L
  private const val FIFTEEN_MINUTES_SECONDS = 15L * 60L
  private val DAY_HOURS: IntRange = 7..21
  private val NIGHT_HOURS: Set<Int> = (0..6).toSet() + setOf(22, 23)
}
