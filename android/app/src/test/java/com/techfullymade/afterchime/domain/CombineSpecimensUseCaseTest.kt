package com.techfullymade.afterchime.domain

import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CombineSpecimensUseCaseTest {
  private val useCase = CombineSpecimensUseCase()

  @Test
  fun `three identical unlocked ordinary specimens can restore`() {
    assertEquals(
      CombineEligibility.Eligible(outputState = CollectibleState.RESTORED),
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-2"),
          candidate("ordinary-3"),
        ),
      ),
    )
  }

  @Test
  fun `three identical unlocked restored specimens can create a centre piece`() {
    assertEquals(
      CombineEligibility.Eligible(outputState = CollectibleState.CENTRE_PIECE),
      useCase(
        listOf(
          candidate("restored-1", state = CollectibleState.RESTORED),
          candidate("restored-2", state = CollectibleState.RESTORED),
          candidate("restored-3", state = CollectibleState.RESTORED),
        ),
      ),
    )
  }

  @Test
  fun `selection must contain exactly three distinct revealed specimens`() {
    assertEquals(
      CombineEligibility.RequiresExactlyThree,
      useCase(listOf(candidate("ordinary-1"), candidate("ordinary-2"))),
    )
    assertEquals(
      CombineEligibility.DuplicateSelection,
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-1"),
          candidate("ordinary-3"),
        ),
      ),
    )
    assertEquals(
      CombineEligibility.UnrevealedInput,
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-2", revealed = false),
          candidate("ordinary-3"),
        ),
      ),
    )
  }

  @Test
  fun `locked centre piece and nonidentical inputs are never eligible`() {
    assertEquals(
      CombineEligibility.LockedInput,
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-2", locked = true),
          candidate("ordinary-3"),
        ),
      ),
    )
    assertEquals(
      CombineEligibility.NonIdenticalInput,
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-2", family = Family.AMBER),
          candidate("ordinary-3"),
        ),
      ),
    )
    assertEquals(
      CombineEligibility.NonIdenticalInput,
      useCase(
        listOf(
          candidate("ordinary-1"),
          candidate("ordinary-2", tier = Tier.EXCEPTIONAL),
          candidate("ordinary-3"),
        ),
      ),
    )
    assertEquals(
      CombineEligibility.NonIdenticalInput,
      useCase(
        listOf(
          candidate("ordinary-1", state = CollectibleState.ORDINARY),
          candidate("ordinary-2", state = CollectibleState.RESTORED),
          candidate("ordinary-3", state = CollectibleState.ORDINARY),
        ),
      ),
    )
    assertEquals(
      CombineEligibility.CentrePieceInput,
      useCase(
        listOf(
          candidate("centre-1", state = CollectibleState.CENTRE_PIECE),
          candidate("centre-2", state = CollectibleState.CENTRE_PIECE),
          candidate("centre-3", state = CollectibleState.CENTRE_PIECE),
        ),
      ),
    )
  }

  private fun candidate(
    id: String,
    family: Family = Family.GEODE,
    tier: Tier = Tier.RARE,
    state: CollectibleState = CollectibleState.ORDINARY,
    revealed: Boolean = true,
    locked: Boolean = false,
  ) = CombineCandidate(
    specimen = MuseumSpecimen(
      id = id,
      anchoredLocalDate = LocalDate.parse("2026-10-03"),
      generatorVersion = 1,
      createdAtEpochMillis = 1_759_593_600_000L,
      revealedAtEpochMillis = if (revealed) 1_759_680_000_000L else null,
      isLocked = locked,
      family = family,
      tier = tier,
      visual = VisualParameters(
        hueDegrees = 143,
        strataCount = 11,
        inclusionDensityPercent = 72,
        reliefPercent = 63,
        rotationDegrees = 217,
      ),
    ),
    state = state,
  )
}
