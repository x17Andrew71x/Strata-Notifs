package com.techfullymade.afterchime.domain

import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateWeeklyDisplayUseCaseTest {
  private val useCase = CreateWeeklyDisplayUseCase()
  private val startDate = LocalDate.parse("2026-10-05")

  @Test
  fun `complete sealed week is chronological and preserves caller collection and specimen identity`() {
    val specimens = (0 until 7).map { offset -> specimen("sealed-$offset", startDate.plusDays(offset.toLong())) }
    val supplied = specimens.toMutableList()
    val originalOrder = supplied.toList()

    val result = useCase(startDate, supplied, emptyList())

    assertEquals(originalOrder, supplied)
    assertEquals(7, supplied.size)
    assertTrue(result is WeeklyDisplayResult.Success)
    val days = (result as WeeklyDisplayResult.Success).display.days
    assertEquals((0 until 7).map { startDate.plusDays(it.toLong()) }, days.map(WeeklyDisplaySlot::date))
    days.forEachIndexed { index, slot ->
      assertTrue(slot is WeeklyDisplaySlot.Sealed)
      assertSame(specimens[index], (slot as WeeklyDisplaySlot.Sealed).specimen)
    }
  }

  @Test
  fun `missing and explicitly unobserved days remain distinct at their dates`() {
    val result = useCase(startDate, emptyList(), listOf(startDate.plusDays(2)))

    assertTrue(result is WeeklyDisplayResult.Success)
    val days = (result as WeeklyDisplayResult.Success).display.days
    assertEquals(WeeklyDisplaySlot.Missing(startDate), days[0])
    assertEquals(WeeklyDisplaySlot.Unobserved(startDate.plusDays(2)), days[2])
    assertEquals(WeeklyDisplaySlot.Missing(startDate.plusDays(1)), days[1])
  }

  @Test
  fun `unrevealed sealed specimen remains displayable`() {
    val unrevealed = specimen("unrevealed", startDate, revealed = false)

    val result = useCase(startDate, listOf(unrevealed), emptyList())

    assertEquals(
      WeeklyDisplaySlot.Sealed(startDate, unrevealed),
      (result as WeeklyDisplayResult.Success).display.days.first(),
    )
  }

  @Test
  fun `derived and out of range specimens do not occupy a slot`() {
    val derived = specimen("derived", anchoredDate = null)
    val before = specimen("before", startDate.minusDays(1))
    val after = specimen("after", startDate.plusDays(7))

    val result = useCase(startDate, listOf(derived, before, after), emptyList())

    assertTrue(result is WeeklyDisplayResult.Success)
    assertTrue((result as WeeklyDisplayResult.Success).display.days.all { it is WeeklyDisplaySlot.Missing })
  }

  @Test
  fun `duplicate in range sealed candidates fail closed`() {
    val result = useCase(
      startDate,
      listOf(specimen("first", startDate), specimen("second", startDate)),
      emptyList(),
    )

    assertEquals(WeeklyDisplayResult.DuplicateSealedDate(startDate), result)
  }

  @Test
  fun `sealed and explicitly unobserved date fails closed`() {
    val result = useCase(startDate, listOf(specimen("sealed", startDate)), listOf(startDate))

    assertEquals(WeeklyDisplayResult.SealedAndUnobserved(startDate), result)
  }

  @Test
  fun `out of range unobserved dates are ignored and result is deterministic`() {
    val sealed = specimen("sealed", startDate.plusDays(3))
    val unobserved = listOf(startDate.minusDays(1), startDate.plusDays(6), startDate.plusDays(7))

    val first = useCase(startDate, listOf(sealed), unobserved)
    val second = useCase(startDate, listOf(sealed), unobserved.reversed())

    assertEquals(first, second)
    assertEquals(WeeklyDisplaySlot.Unobserved(startDate.plusDays(6)),
      (first as WeeklyDisplayResult.Success).display.days.last())
  }

  private fun specimen(
    id: String,
    anchoredDate: LocalDate?,
    revealed: Boolean = true,
  ) = MuseumSpecimen(
    id = id,
    anchoredLocalDate = anchoredDate,
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_593_600_000L,
    revealedAtEpochMillis = if (revealed) 1_759_680_000_000L else null,
    family = Family.GEODE,
    tier = Tier.RARE,
    visual = VisualParameters(
      hueDegrees = 143,
      strataCount = 11,
      inclusionDensityPercent = 72,
      reliefPercent = 63,
      rotationDegrees = 217,
    ),
  )
}
