package com.techfullymade.afterchime.domain

/** Local collectible maturity. Base daily specimens begin as ordinary and only explicit combines advance it. */
enum class CollectibleState {
  ORDINARY,
  RESTORED,
  CENTRE_PIECE,
}

/** A selected local museum item and the only state needed to decide whether it may be combined. */
data class CombineCandidate(
  val specimen: MuseumSpecimen,
  val state: CollectibleState,
)

/** A side-effect-free combine decision; persistence owns consumption and output creation in a later transaction. */
sealed interface CombineEligibility {
  data class Eligible(val outputState: CollectibleState) : CombineEligibility

  data object RequiresExactlyThree : CombineEligibility

  data object DuplicateSelection : CombineEligibility

  data object UnrevealedInput : CombineEligibility

  data object LockedInput : CombineEligibility

  data object NonIdenticalInput : CombineEligibility

  data object CentrePieceInput : CombineEligibility
}

/**
 * Evaluates the irreversible local restoration rule before any confirmation or database mutation.
 *
 * Identity deliberately means family, tier, and maturity only: worlds are renderers rather than
 * inventory variants, so a cosmetic world can never create a distinct combine class.
 */
class CombineSpecimensUseCase {
  operator fun invoke(selection: List<CombineCandidate>): CombineEligibility {
    if (selection.size != COMBINE_SIZE) return CombineEligibility.RequiresExactlyThree
    if (selection.map { it.specimen.id }.toSet().size != COMBINE_SIZE) {
      return CombineEligibility.DuplicateSelection
    }
    if (selection.any { it.specimen.revealedAtEpochMillis == null }) {
      return CombineEligibility.UnrevealedInput
    }
    if (selection.any { it.specimen.isLocked }) return CombineEligibility.LockedInput

    val first = selection.first()
    if (selection.any { candidate ->
        candidate.specimen.family != first.specimen.family ||
          candidate.specimen.tier != first.specimen.tier ||
          candidate.state != first.state
      }
    ) {
      return CombineEligibility.NonIdenticalInput
    }

    return when (first.state) {
      CollectibleState.ORDINARY -> CombineEligibility.Eligible(CollectibleState.RESTORED)
      CollectibleState.RESTORED -> CombineEligibility.Eligible(CollectibleState.CENTRE_PIECE)
      CollectibleState.CENTRE_PIECE -> CombineEligibility.CentrePieceInput
    }
  }

  private companion object {
    const val COMBINE_SIZE = 3
  }
}
