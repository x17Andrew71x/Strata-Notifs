package com.techfullymade.afterchime.domain

import androidx.room.withTransaction
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.dao.StoredSpecimenWithOutput
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local collection boundary for presentation code. It never exposes Room entities. */
interface MuseumRepository {
  val specimens: Flow<List<MuseumSpecimen>>

  /** Changes a revealed local specimen's protection state without leaving the device. */
  suspend fun setLocked(specimenId: String, locked: Boolean): SpecimenLockResult
}

/** Idempotent outcome for a local favourite lock request. */
sealed interface SpecimenLockResult {
  data class Changed(val isLocked: Boolean) : SpecimenLockResult

  data class AlreadySet(val isLocked: Boolean) : SpecimenLockResult

  data object NotFound : SpecimenLockResult

  data object Unrevealed : SpecimenLockResult
}

/** Immutable, renderer-neutral museum record derived only from sealed local generator output. */
data class MuseumSpecimen(
  val id: String,
  val anchoredLocalDate: LocalDate,
  val generatorVersion: Int,
  val createdAtEpochMillis: Long,
  val revealedAtEpochMillis: Long?,
  val isLocked: Boolean = false,
  val family: Family,
  val tier: Tier,
  val visual: VisualParameters,
)

/** Room-backed local museum repository with no network or analytics dependency. */
class LocalMuseumRepository(
  private val database: AfterchimeDatabase,
) : MuseumRepository {
  override val specimens: Flow<List<MuseumSpecimen>> = database.specimenDao()
    .observeAllWithOutput()
    .map { records -> records.map(StoredSpecimenWithOutput::toMuseumSpecimen) }

  override suspend fun setLocked(specimenId: String, locked: Boolean): SpecimenLockResult {
    require(specimenId.isNotBlank())
    return database.withTransaction {
      val specimenDao = database.specimenDao()
      val current = specimenDao.getBySpecimenId(specimenId) ?: return@withTransaction SpecimenLockResult.NotFound
      if (current.revealedAtEpochMillis == null) return@withTransaction SpecimenLockResult.Unrevealed
      if (current.isLocked == locked) return@withTransaction SpecimenLockResult.AlreadySet(locked)

      if (specimenDao.setLockedIfRevealed(specimenId, locked) == 1) {
        SpecimenLockResult.Changed(locked)
      } else {
        val afterUpdate = specimenDao.getBySpecimenId(specimenId)
        when {
          afterUpdate == null -> SpecimenLockResult.NotFound
          afterUpdate.revealedAtEpochMillis == null -> SpecimenLockResult.Unrevealed
          afterUpdate.isLocked == locked -> SpecimenLockResult.AlreadySet(locked)
          else -> error("A local specimen changed before its lock transaction completed")
        }
      }
    }
  }
}

internal fun StoredSpecimenWithOutput.toMuseumSpecimen(): MuseumSpecimen = MuseumSpecimen(
  id = specimenId,
  anchoredLocalDate = LocalDate.parse(anchoredLocalDate),
  generatorVersion = generatorVersion,
  createdAtEpochMillis = createdAtEpochMillis,
  revealedAtEpochMillis = revealedAtEpochMillis,
  isLocked = isLocked,
  family = family,
  tier = tier,
  visual = VisualParameters(
    hueDegrees = hueDegrees,
    strataCount = strataCount,
    inclusionDensityPercent = inclusionDensityPercent,
    reliefPercent = reliefPercent,
    rotationDegrees = rotationDegrees,
  ),
)
