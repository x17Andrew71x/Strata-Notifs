package com.techfullymade.afterchime.aggregation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.gameplay.NotificationEnergyPolicy
import java.time.LocalDate
import java.time.ZoneOffset

/** A count for one member of the closed coarse-category set. */
data class DailyNotificationCategoryTotal(
  val category: CoarseNotificationCategory,
  val eligibleCount: Int,
)

/** A non-empty accepted-event count for one local hour. */
data class DailyNotificationHourTotal(
  val localHour: Int,
  val eligibleCount: Int,
)

/** Privacy-safe aggregate containing counts and bounded contract metadata only. */
data class DailyNotificationSummary(
  val localDate: LocalDate,
  val timezoneOffset: ZoneOffset,
  val revision: Int,
  val rulesVersion: Int,
  val eligibleCount: Int,
  val categoryTotals: List<DailyNotificationCategoryTotal>,
  val hourlyBuckets: List<DailyNotificationHourTotal>,
  val gamePressure: Int,
  val observationCompleteness: Int,
)

object DailyNotificationSummaryCalculator {
  private const val REVISION = 1
  private const val RULES_VERSION = 1
  private const val UNINTERRUPTED_COMPLETENESS = 100
  private const val INTERRUPTED_COMPLETENESS = 0
  private const val DISABLED_GAME_PRESSURE = 0

  fun calculate(
    localDate: LocalDate,
    timezoneOffset: ZoneOffset,
    uninterruptedObservation: Boolean,
    notifications: Iterable<ReducedNotification>,
  ): DailyNotificationSummary {
    val accepted = NotificationEnergyPolicy.accepted(
      inputs = notifications,
      sourceKey = ReducedNotification::sourceToken,
      occurredAtEpochMillis = ReducedNotification::occurredAtEpochMillis,
    )
    val countsByCategory = accepted.groupingBy(ReducedNotification::category).eachCount()
    val categoryTotals = CoarseNotificationCategory.values().map { category ->
      DailyNotificationCategoryTotal(
        category = category,
        eligibleCount = countsByCategory[category] ?: 0,
      )
    }
    val hourlyBuckets = accepted
      .groupingBy(ReducedNotification::localHour)
      .eachCount()
      .toSortedMap()
      .map { (localHour, count) ->
        DailyNotificationHourTotal(
          localHour = localHour,
          eligibleCount = count,
        )
      }

    return DailyNotificationSummary(
      localDate = localDate,
      timezoneOffset = timezoneOffset,
      revision = REVISION,
      rulesVersion = RULES_VERSION,
      eligibleCount = accepted.size,
      categoryTotals = categoryTotals,
      hourlyBuckets = hourlyBuckets,
      gamePressure = DISABLED_GAME_PRESSURE,
      observationCompleteness = if (uninterruptedObservation) {
        UNINTERRUPTED_COMPLETENESS
      } else {
        INTERRUPTED_COMPLETENESS
      },
    )
  }
}
