package com.techfullymade.afterchime.generation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import java.time.LocalDate

enum class ObservationCompleteness(
  val percent: Int,
) {
  OBSERVED(100),
  UNOBSERVED(0),
}

/**
 * Safe, source-identity-free local input for deterministic specimen generation.
 *
 * Counts retain observed notification rhythm only. `trueNotificationCount` is intentionally separate
 * from capped `gamePressure`, and an unobserved day never acquires a synthetic quiet interval.
 */
data class DaySummary(
  val localDate: LocalDate,
  val observationCompleteness: ObservationCompleteness,
  val trueNotificationCount: Int,
  val gamePressure: Int,
  val hourlyCounts: Map<Int, Int>,
  val categoryCounts: Map<CoarseNotificationCategory, Int>,
  val sourceDiversity: Int,
  val dayDurationMinutes: Int,
  val longestObservedQuietIntervalMinutes: Int,
  val peakHourlyCount: Int,
  val maxEventsInFifteenMinuteWindow: Int,
  val dayNotificationCount: Int,
  val nightNotificationCount: Int,
) {
  val observationCompletenessPercent: Int
    get() = observationCompleteness.percent

  init {
    require(trueNotificationCount >= 0)
    require(gamePressure in 0..MAX_GAME_PRESSURE)
    require(gamePressure <= trueNotificationCount)
    require(hourlyCounts.keys.all { it in 0..23 })
    require(hourlyCounts.values.all { it > 0 })
    require(hourlyCounts.values.sum() == trueNotificationCount)
    require(categoryCounts.values.all { it > 0 })
    require(categoryCounts.values.sum() == trueNotificationCount)
    require(sourceDiversity in 0..trueNotificationCount)
    require(dayDurationMinutes > 0)
    require(longestObservedQuietIntervalMinutes in 0..dayDurationMinutes)
    require(peakHourlyCount >= 0)
    require(maxEventsInFifteenMinuteWindow >= 0)
    require(dayNotificationCount >= 0)
    require(nightNotificationCount >= 0)
    require(dayNotificationCount + nightNotificationCount == trueNotificationCount)
  }

  companion object {
    const val MAX_GAME_PRESSURE = 100
  }
}
