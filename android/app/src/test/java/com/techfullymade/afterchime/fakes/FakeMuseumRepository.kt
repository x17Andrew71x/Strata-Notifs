package com.techfullymade.afterchime.fakes

import com.techfullymade.afterchime.domain.CombineRequest
import com.techfullymade.afterchime.domain.CombineResult
import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.domain.SpecimenLockResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Mutable test double for museum UI tests; production code remains Room-backed. */
class FakeMuseumRepository(
  specimens: List<MuseumSpecimen> = emptyList(),
) : MuseumRepository {
  private val mutableSpecimens = MutableStateFlow(specimens)

  override val specimens: Flow<List<MuseumSpecimen>> = mutableSpecimens
  val combineRequests = mutableListOf<CombineRequest>()
  var failNextCombine: Boolean = false

  override suspend fun setLocked(specimenId: String, locked: Boolean): SpecimenLockResult {
    val specimenIndex = mutableSpecimens.value.indexOfFirst { it.id == specimenId }
    if (specimenIndex < 0) return SpecimenLockResult.NotFound
    val specimen = mutableSpecimens.value[specimenIndex]
    if (specimen.revealedAtEpochMillis == null) return SpecimenLockResult.Unrevealed
    if (specimen.isLocked == locked) return SpecimenLockResult.AlreadySet(locked)
    mutableSpecimens.value = mutableSpecimens.value.toMutableList().also { currentSpecimens ->
      currentSpecimens[specimenIndex] = specimen.copy(isLocked = locked)
    }
    return SpecimenLockResult.Changed(locked)
  }

  override suspend fun combine(request: CombineRequest): CombineResult {
    combineRequests += request
    if (failNextCombine) {
      failNextCombine = false
      error("Simulated result-delivery failure")
    }
    return CombineResult.Combined(request.outputItemId)
  }

  fun replace(specimens: List<MuseumSpecimen>) {
    mutableSpecimens.value = specimens
  }
}
