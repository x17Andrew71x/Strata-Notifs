package com.techfullymade.afterchime.domain

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
}

/** Immutable, renderer-neutral museum record derived only from sealed local generator output. */
data class MuseumSpecimen(
  val id: String,
  val anchoredLocalDate: LocalDate,
  val generatorVersion: Int,
  val createdAtEpochMillis: Long,
  val revealedAtEpochMillis: Long?,
  val family: Family,
  val tier: Tier,
  val visual: VisualParameters,
)

/** Room-backed local museum repository with no network or analytics dependency. */
class LocalMuseumRepository(
  database: AfterchimeDatabase,
) : MuseumRepository {
  override val specimens: Flow<List<MuseumSpecimen>> = database.specimenDao()
    .observeAllWithOutput()
    .map { records -> records.map(StoredSpecimenWithOutput::toMuseumSpecimen) }
}

internal fun StoredSpecimenWithOutput.toMuseumSpecimen(): MuseumSpecimen = MuseumSpecimen(
  id = specimenId,
  anchoredLocalDate = LocalDate.parse(anchoredLocalDate),
  generatorVersion = generatorVersion,
  createdAtEpochMillis = createdAtEpochMillis,
  revealedAtEpochMillis = revealedAtEpochMillis,
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
