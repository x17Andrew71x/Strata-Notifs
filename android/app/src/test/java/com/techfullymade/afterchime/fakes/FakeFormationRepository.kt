package com.techfullymade.afterchime.fakes

import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.RevealResult
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Mutable test double for UI tests; it models only safe domain snapshots. */
class FakeFormationRepository(
  snapshots: List<FormationSnapshot> = emptyList(),
) : FormationRepository {
  private val snapshots = MutableStateFlow(snapshots.associateBy(FormationSnapshot::localDate))

  override fun observe(localDate: LocalDate): Flow<FormationSnapshot> = snapshots.map { knownSnapshots ->
    knownSnapshots[localDate] ?: FormationSnapshot(
      localDate = localDate,
      observation = FormationObservation.AwaitingAccess,
      layers = emptyList(),
      sealedSpecimen = null,
    )
  }

  override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
    val currentSnapshots = snapshots.value
    val matchingEntry = currentSnapshots.entries.firstOrNull { (_, snapshot) ->
      snapshot.sealedSpecimen?.id == specimenId
    } ?: return RevealResult.NotFound
    val specimen = checkNotNull(matchingEntry.value.sealedSpecimen)
    val previousReveal = specimen.revealedAtEpochMillis
    if (previousReveal != null) {
      return RevealResult.AlreadyRevealed(previousReveal)
    }

    snapshots.value = currentSnapshots + (
      matchingEntry.key to matchingEntry.value.copy(
        sealedSpecimen = specimen.copy(revealedAtEpochMillis = revealedAtEpochMillis),
      )
    )
    return RevealResult.Revealed(revealedAtEpochMillis)
  }

  fun replace(snapshot: FormationSnapshot) {
    snapshots.value = snapshots.value + (snapshot.localDate to snapshot)
  }
}
