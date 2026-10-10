package com.techfullymade.afterchime.domain

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.dao.StoredSpecimenWithOutput
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.gameplay.FossilCatalog
import com.techfullymade.afterchime.gameplay.FossilCatalogItem
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

  /** Atomically exchanges three eligible collection items for one provenance-preserving output. */
  suspend fun combine(request: CombineRequest): CombineResult
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
  val anchoredLocalDate: LocalDate?,
  val generatorVersion: Int,
  val createdAtEpochMillis: Long,
  val revealedAtEpochMillis: Long?,
  val isLocked: Boolean = false,
  val collectibleState: CollectibleState = CollectibleState.ORDINARY,
  val provenanceCount: Int = 1,
  val family: Family,
  val tier: Tier,
  val visual: VisualParameters,
  val catalogItemId: String? = null,
) {
  init {
    require(provenanceCount > 0)
  }

  val isRestored: Boolean
    get() = collectibleState != CollectibleState.ORDINARY
}

/** Room-backed local museum repository with no network or analytics dependency. */
class LocalMuseumRepository(
  private val database: AfterchimeDatabase,
  private val catalogItemForSpecimenId: (String) -> FossilCatalogItem? =
    { specimenId -> FossilCatalog.itemForSpecimenId(specimenId) },
) : MuseumRepository {
  override val specimens: Flow<List<MuseumSpecimen>> = database.inventoryItemDao()
    .observeAllActive()
    .map { records ->
      records.map { record -> record.toMuseumSpecimen(catalogItemForSpecimenId) }
    }

  override suspend fun setLocked(specimenId: String, locked: Boolean): SpecimenLockResult {
    require(specimenId.isNotBlank())
    return database.withTransaction {
      val inventoryItemDao = database.inventoryItemDao()
      val current = inventoryItemDao.getByItemId(specimenId) ?: return@withTransaction SpecimenLockResult.NotFound
      if (current.revealedAtEpochMillis == null) return@withTransaction SpecimenLockResult.Unrevealed
      if (current.isLocked == locked) return@withTransaction SpecimenLockResult.AlreadySet(locked)

      if (inventoryItemDao.setLockedIfRevealed(specimenId, locked) == 1) {
        current.sourceSpecimenId?.let { sourceSpecimenId ->
          if (database.specimenDao().setLockedIfRevealed(sourceSpecimenId, locked) != 1) {
            error("A daily specimen lock could not be kept in sync with its museum item")
          }
        }
        SpecimenLockResult.Changed(locked)
      } else {
        val afterUpdate = inventoryItemDao.getByItemId(specimenId)
        when {
          afterUpdate == null -> SpecimenLockResult.NotFound
          afterUpdate.revealedAtEpochMillis == null -> SpecimenLockResult.Unrevealed
          afterUpdate.isLocked == locked -> SpecimenLockResult.AlreadySet(locked)
          else -> error("A local specimen changed before its lock transaction completed")
        }
      }
    }
  }

  /**
   * Consumes exactly three eligible active items and creates one mature output in one transaction.
   * The mutation and output identifiers are client-generated so a timeout may be retried safely.
   */
  override suspend fun combine(request: CombineRequest): CombineResult = try {
    database.withTransaction {
      val itemDao = database.inventoryItemDao()
      val mutationDao = database.inventoryMutationDao()
      val canonicalInputIds = request.inputItemIds.sorted()
      val existingMutation = mutationDao.getByMutationId(request.mutationId)
      if (existingMutation != null) {
        return@withTransaction if (
          existingMutation.outputItemId == request.outputItemId &&
          existingMutation.inputItemIds == canonicalInputIds
        ) {
          CombineResult.AlreadyCombined(existingMutation.outputItemId)
        } else {
          CombineResult.MutationConflict
        }
      }
      if (canonicalInputIds.size != COMBINE_SIZE) {
        return@withTransaction CombineResult.Ineligible(CombineEligibility.RequiresExactlyThree)
      }
      if (canonicalInputIds.toSet().size != COMBINE_SIZE) {
        return@withTransaction CombineResult.Ineligible(CombineEligibility.DuplicateSelection)
      }

      val selected = itemDao.activeByIds(canonicalInputIds).sortedBy(InventoryItemEntity::itemId)
      if (selected.size != COMBINE_SIZE) {
        return@withTransaction CombineResult.Ineligible(CombineEligibility.UnavailableInput)
      }
      val eligibility = CombineSpecimensUseCase()(selected.map { item -> item.toCombineCandidate() })
      if (eligibility !is CombineEligibility.Eligible) {
        return@withTransaction CombineResult.Ineligible(eligibility)
      }
      if (selected.any { it.provenanceCount != it.collectibleState.inputProvenanceCount }) {
        return@withTransaction CombineResult.Ineligible(CombineEligibility.NonIdenticalInput)
      }
      if (itemDao.consumeActive(canonicalInputIds, request.mutationId) != COMBINE_SIZE) {
        return@withTransaction CombineResult.Ineligible(CombineEligibility.UnavailableInput)
      }

      val firstInput = selected.first()
      itemDao.insert(
        firstInput.copy(
          itemId = request.outputItemId,
          sourceSpecimenId = null,
          anchoredLocalDate = null,
          generatorVersion = selected.maxOf(InventoryItemEntity::generatorVersion),
          createdAtEpochMillis = request.createdAtEpochMillis,
          revealedAtEpochMillis = request.createdAtEpochMillis,
          isLocked = false,
          collectibleState = eligibility.outputState,
          provenanceCount = selected.sumOf(InventoryItemEntity::provenanceCount),
          consumedByMutationId = null,
        ),
      )
      mutationDao.insert(
        com.techfullymade.afterchime.data.local.entity.InventoryMutationEntity(
          mutationId = request.mutationId,
          outputItemId = request.outputItemId,
          firstInputItemId = canonicalInputIds[0],
          secondInputItemId = canonicalInputIds[1],
          thirdInputItemId = canonicalInputIds[2],
          createdAtEpochMillis = request.createdAtEpochMillis,
        ),
      )
      CombineResult.Combined(request.outputItemId)
    }
  } catch (_: SQLiteConstraintException) {
    // An output collision aborts the surrounding Room transaction before any consumed input commits.
    CombineResult.OutputConflict
  }

  private fun InventoryItemEntity.toCombineCandidate(): CombineCandidate = CombineCandidate(
    specimen = toMuseumSpecimen(),
    state = collectibleState,
  )
}

internal fun StoredSpecimenWithOutput.toMuseumSpecimen(
  catalogItemForSpecimenId: (String) -> FossilCatalogItem? =
    { specimenId -> FossilCatalog.itemForSpecimenId(specimenId) },
): MuseumSpecimen = MuseumSpecimen(
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
  catalogItemId = catalogItemForSpecimenId(specimenId)?.id,
)

internal fun InventoryItemEntity.toMuseumSpecimen(
  catalogItemForSpecimenId: (String) -> FossilCatalogItem? =
    { specimenId -> FossilCatalog.itemForSpecimenId(specimenId) },
): MuseumSpecimen = MuseumSpecimen(
  id = itemId,
  anchoredLocalDate = anchoredLocalDate?.let(LocalDate::parse),
  generatorVersion = generatorVersion,
  createdAtEpochMillis = createdAtEpochMillis,
  revealedAtEpochMillis = revealedAtEpochMillis,
  isLocked = isLocked,
  collectibleState = collectibleState,
  provenanceCount = provenanceCount,
  family = family,
  tier = tier,
  visual = VisualParameters(
    hueDegrees = hueDegrees,
    strataCount = strataCount,
    inclusionDensityPercent = inclusionDensityPercent,
    reliefPercent = reliefPercent,
    rotationDegrees = rotationDegrees,
  ),
  catalogItemId = catalogItemForSpecimenId(itemId)?.id,
)

private val CollectibleState.inputProvenanceCount: Int
  get() = when (this) {
    CollectibleState.ORDINARY -> 1
    CollectibleState.RESTORED -> 3
    CollectibleState.CENTRE_PIECE -> 9
  }

private const val COMBINE_SIZE = 3
