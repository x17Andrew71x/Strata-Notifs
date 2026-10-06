package com.techfullymade.afterchime.domain

import java.time.LocalDate

private const val DAYS_PER_WEEK = 7

/** One renderer-neutral day in a weekly display. */
sealed interface WeeklyDisplaySlot {
  val date: LocalDate

  data class Sealed(
    override val date: LocalDate,
    val specimen: MuseumSpecimen,
  ) : WeeklyDisplaySlot

  data class Unobserved(override val date: LocalDate) : WeeklyDisplaySlot

  data class Missing(override val date: LocalDate) : WeeklyDisplaySlot
}

/** The complete chronological seven-day arrangement, with no inventory or world dimension. */
data class WeeklyDisplay(val days: List<WeeklyDisplaySlot>) {
  init {
    require(days.size == DAYS_PER_WEEK)
    require(days.zipWithNext().all { (first, second) -> first.date.plusDays(1) == second.date })
  }
}

/** Fail-closed outcomes for ambiguous source data within the requested interval. */
sealed interface WeeklyDisplayResult {
  data class Success(val display: WeeklyDisplay) : WeeklyDisplayResult

  data class DuplicateSealedDate(val date: LocalDate) : WeeklyDisplayResult

  data class SealedAndUnobserved(val date: LocalDate) : WeeklyDisplayResult
}

/** Purely arranges sealed local specimens into a seven-day display without changing the inputs. */
class CreateWeeklyDisplayUseCase {
  operator fun invoke(
    startDate: LocalDate,
    sealedCandidates: Collection<MuseumSpecimen>,
    unobservedDates: Collection<LocalDate>,
  ): WeeklyDisplayResult {
    val dates = (0 until DAYS_PER_WEEK).map { offset -> startDate.plusDays(offset.toLong()) }
    val inRangeDates = dates.toSet()
    val candidatesByDate = sealedCandidates
      .mapNotNull { specimen ->
        specimen.anchoredLocalDate
          ?.takeIf(inRangeDates::contains)
          ?.let { date -> date to specimen }
      }
      .groupBy({ it.first }, { it.second })
    val unobservedInRange = unobservedDates.filter(inRangeDates::contains).toSet()

    for (date in dates) {
      val candidates = candidatesByDate[date].orEmpty()
      if (candidates.size > 1) return WeeklyDisplayResult.DuplicateSealedDate(date)
      if (candidates.isNotEmpty() && date in unobservedInRange) {
        return WeeklyDisplayResult.SealedAndUnobserved(date)
      }
    }

    val days = dates.map { date ->
      val specimen = candidatesByDate[date]?.singleOrNull()
      when {
        specimen != null -> WeeklyDisplaySlot.Sealed(date, specimen)
        date in unobservedInRange -> WeeklyDisplaySlot.Unobserved(date)
        else -> WeeklyDisplaySlot.Missing(date)
      }
    }
    return WeeklyDisplayResult.Success(WeeklyDisplay(days))
  }
}
