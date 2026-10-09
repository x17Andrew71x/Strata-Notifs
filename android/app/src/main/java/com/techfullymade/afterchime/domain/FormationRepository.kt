package com.techfullymade.afterchime.domain

import androidx.room.withTransaction
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.DailyExcavationEntity
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity
import com.techfullymade.afterchime.gameplay.FossilCatalog
import com.techfullymade.afterchime.gameplay.FossilCatalogItem
import com.techfullymade.afterchime.gameplay.GameBalance
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Safe renderer input for one live local notification layer; source identity never crosses this API. */
data class FormationLayer(
  val localHour: Int,
  val category: CoarseNotificationCategory,
  val sourceColourRgb: Int,
)

/** Presentable local observation state, including sealed-day truth. */
sealed interface FormationObservation {
  data object AwaitingAccess : FormationObservation

  data object Active : FormationObservation

  data object Disconnected : FormationObservation

  data object Revoked : FormationObservation

  data object SealedObserved : FormationObservation

  data object SealedUnobserved : FormationObservation
}

/** Privacy-reduced, tunable state for one date-bound excavation. */
data class DailyExcavationSnapshot(
  val artifactId: String,
  val capturedNotificationCount: Int,
  val eligibleNotificationCount: Int,
  val energyPerNotification: Int,
  val energyEarned: Int,
  val energySpent: Int,
  val energyAvailable: Int,
  val tileEnergyCost: Int,
  val gridColumns: Int,
  val gridRows: Int,
  val dugTiles: Set<Int>,
  val completedAtEpochMillis: Long?,
) {
  val completed: Boolean
    get() = completedAtEpochMillis != null
}

data class FormationSnapshot(
  val localDate: LocalDate,
  val observation: FormationObservation,
  val layers: List<FormationLayer>,
  val sealedSpecimen: MuseumSpecimen?,
  val excavation: DailyExcavationSnapshot? = null,
)

sealed interface RevealResult {
  data class Revealed(
    val revealedAtEpochMillis: Long,
  ) : RevealResult

  data class AlreadyRevealed(
    val revealedAtEpochMillis: Long,
  ) : RevealResult

  data object NotFound : RevealResult
}

sealed interface DigResult {
  data class Dug(val tileIndex: Int, val energyAvailable: Int) : DigResult

  data class Completed(val specimenId: String) : DigResult

  data class InsufficientEnergy(val required: Int, val available: Int) : DigResult

  data object AlreadyDug : DigResult

  data object AlreadyCompleted : DigResult

  data object InvalidTile : DigResult

  data object Unavailable : DigResult
}

/** UI-facing formation boundary. Its implementation is local-only for offline gameplay. */
interface FormationRepository {
  fun observe(localDate: LocalDate): Flow<FormationSnapshot>

  suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult

  suspend fun dig(localDate: LocalDate, tileIndex: Int, occurredAtEpochMillis: Long): DigResult =
    DigResult.Unavailable
}

/** Room-backed formation repository. No network client or analytics service is accepted or used. */
class LocalFormationRepository(
  private val database: AfterchimeDatabase,
  private val clock: Clock = Clock.systemUTC(),
  private val selectDailyFossil: (LocalDate) -> FossilCatalogItem = { FossilCatalog.items.first() },
) : FormationRepository {
  override fun observe(localDate: LocalDate): Flow<FormationSnapshot> = flow {
    ensureDailyExcavation(localDate)
    emitAll(observePersisted(localDate))
  }

  private fun observePersisted(localDate: LocalDate): Flow<FormationSnapshot> {
    val localDateString = localDate.toString()
    val liveInputs = combine(
      database.reducedNotificationDao().observeForLocalDate(localDateString),
      database.listenerAccessStateDao().observe(localDateString),
      database.dailyExcavationDao().observe(localDateString),
    ) { notifications, accessState, excavation ->
      LocalFormationInputs(
        layers = notifications.map { notification ->
          FormationLayer(
            localHour = notification.localHour,
            category = notification.category,
            sourceColourRgb = notification.sourceColourRgb,
          )
        },
        notifications = notifications,
        accessState = accessState?.latestState,
        excavation = excavation,
      )
    }
    val sealedInputs = combine(
      database.daySummaryDao().observe(localDateString),
      database.specimenDao().observeWithOutputForAnchoredLocalDate(localDateString),
      database.specimenDao().observeAllWithOutput(),
    ) { summary, specimen, specimens ->
      val pendingPriorSpecimen = specimens.firstOrNull { candidate ->
        candidate.revealedAtEpochMillis == null &&
          LocalDate.parse(candidate.anchoredLocalDate) < localDate
      }
      SealedFormationInputs(
        observationState = summary?.observationState,
        specimen = (specimen ?: pendingPriorSpecimen)?.toMuseumSpecimen(),
      )
    }

    return combine(liveInputs, sealedInputs) { live, sealed ->
      FormationSnapshot(
        localDate = localDate,
        observation = sealed.observationState.toFormationObservation(live.accessState),
        layers = live.layers,
        sealedSpecimen = sealed.specimen,
        excavation = live.excavation?.toSnapshot(live.notifications),
      )
    }
  }

  override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
    require(specimenId.isNotBlank())
    require(revealedAtEpochMillis >= 0)
    return database.withTransaction {
      val specimenDao = database.specimenDao()
      val current = specimenDao.getBySpecimenId(specimenId) ?: return@withTransaction RevealResult.NotFound
      val previouslyRevealedAt = current.revealedAtEpochMillis
      if (previouslyRevealedAt != null) {
        return@withTransaction RevealResult.AlreadyRevealed(previouslyRevealedAt)
      }
      require(revealedAtEpochMillis >= current.createdAtEpochMillis)

      if (specimenDao.markRevealedIfUnrevealed(specimenId, revealedAtEpochMillis) == 1) {
        if (
          database.inventoryItemDao().markSourceRevealedIfUnrevealed(specimenId, revealedAtEpochMillis) != 1
        ) {
          error("A local museum item was missing when its sealed specimen was revealed")
        }
        RevealResult.Revealed(revealedAtEpochMillis)
      } else {
        val afterConcurrentReveal = specimenDao.getBySpecimenId(specimenId)?.revealedAtEpochMillis
        if (afterConcurrentReveal != null) {
          RevealResult.AlreadyRevealed(afterConcurrentReveal)
        } else {
          error("A local specimen changed before its reveal transaction completed")
        }
      }
    }
  }

  override suspend fun dig(
    localDate: LocalDate,
    tileIndex: Int,
    occurredAtEpochMillis: Long,
  ): DigResult {
    if (tileIndex !in 0 until GameBalance.EXCAVATION_TILE_COUNT) return DigResult.InvalidTile
    require(occurredAtEpochMillis >= 0)
    ensureDailyExcavation(localDate)
    return database.withTransaction {
      val localDateString = localDate.toString()
      val excavationDao = database.dailyExcavationDao()
      val excavation = excavationDao.get(localDateString) ?: return@withTransaction DigResult.Unavailable
      if (excavation.completedAtEpochMillis != null) return@withTransaction DigResult.AlreadyCompleted
      val tileBit = 1L shl tileIndex
      if (excavation.dugMask and tileBit != 0L) return@withTransaction DigResult.AlreadyDug

      val notifications = database.reducedNotificationDao().forLocalDate(localDateString)
      val eligibleNotifications = eligibleNotificationCount(notifications)
      val spent = java.lang.Long.bitCount(excavation.dugMask) * GameBalance.ENERGY_PER_TILE
      val available = eligibleNotifications * GameBalance.ENERGY_PER_ELIGIBLE_NOTIFICATION - spent
      if (available < GameBalance.ENERGY_PER_TILE) {
        return@withTransaction DigResult.InsufficientEnergy(
          required = GameBalance.ENERGY_PER_TILE,
          available = maxOf(0, available),
        )
      }

      val updatedMask = excavation.dugMask or tileBit
      val completed = java.lang.Long.bitCount(updatedMask) == GameBalance.EXCAVATION_TILE_COUNT
      if (!completed) {
        check(excavationDao.update(excavation.copy(dugMask = updatedMask)) == 1)
        return@withTransaction DigResult.Dug(
          tileIndex = tileIndex,
          energyAvailable = available - GameBalance.ENERGY_PER_TILE,
        )
      }

      val catalogItem = FossilCatalog.find(excavation.artifactId)
        ?: return@withTransaction DigResult.Unavailable
      val specimenId = FossilCatalog.specimenId(localDate, catalogItem)
      persistCompletedSpecimen(
        localDate = localDate,
        excavation = excavation,
        catalogItem = catalogItem,
        specimenId = specimenId,
        completedAtEpochMillis = occurredAtEpochMillis,
      )
      check(
        excavationDao.update(
          excavation.copy(
            dugMask = updatedMask,
            completedAtEpochMillis = occurredAtEpochMillis,
            specimenId = specimenId,
          ),
        ) == 1,
      )
      DigResult.Completed(specimenId)
    }
  }

  private suspend fun ensureDailyExcavation(localDate: LocalDate) {
    database.withTransaction {
      val dao = database.dailyExcavationDao()
      if (dao.get(localDate.toString()) != null) return@withTransaction
      val item = selectDailyFossil(localDate)
      dao.insertIfAbsent(
        DailyExcavationEntity(
          localDate = localDate.toString(),
          artifactId = item.id,
          dugMask = 0L,
          createdAtEpochMillis = clock.millis(),
          completedAtEpochMillis = null,
          specimenId = null,
        ),
      )
    }
  }

  private suspend fun persistCompletedSpecimen(
    localDate: LocalDate,
    excavation: DailyExcavationEntity,
    catalogItem: FossilCatalogItem,
    specimenId: String,
    completedAtEpochMillis: Long,
  ) {
    check(database.specimenDao().getByAnchoredLocalDate(localDate.toString()) == null) {
      "The daily excavation cannot replace an existing specimen"
    }
    val record = SpecimenEntity(
      specimenId = specimenId,
      anchoredLocalDate = localDate.toString(),
      generatorVersion = GameBalance.CATALOG_GENERATOR_VERSION,
      createdAtEpochMillis = excavation.createdAtEpochMillis,
      revealedAtEpochMillis = completedAtEpochMillis,
    )
    val output = SpecimenOutputEntity(
      specimenId = specimenId,
      family = catalogItem.family,
      tier = catalogItem.tier,
      hueDegrees = catalogItem.visual.hueDegrees,
      strataCount = catalogItem.visual.strataCount,
      inclusionDensityPercent = catalogItem.visual.inclusionDensityPercent,
      reliefPercent = catalogItem.visual.reliefPercent,
      rotationDegrees = catalogItem.visual.rotationDegrees,
    )
    database.specimenDao().insert(record)
    database.specimenOutputDao().insert(output)
    database.inventoryItemDao().insert(
      InventoryItemEntity(
        itemId = specimenId,
        sourceSpecimenId = specimenId,
        anchoredLocalDate = localDate.toString(),
        generatorVersion = record.generatorVersion,
        createdAtEpochMillis = record.createdAtEpochMillis,
        revealedAtEpochMillis = record.revealedAtEpochMillis,
        isLocked = false,
        collectibleState = CollectibleState.ORDINARY,
        provenanceCount = 1,
        family = output.family,
        tier = output.tier,
        hueDegrees = output.hueDegrees,
        strataCount = output.strataCount,
        inclusionDensityPercent = output.inclusionDensityPercent,
        reliefPercent = output.reliefPercent,
        rotationDegrees = output.rotationDegrees,
        consumedByMutationId = null,
      ),
    )
  }

  private fun DailyExcavationEntity.toSnapshot(
    notifications: List<ReducedNotificationEntity>,
  ): DailyExcavationSnapshot {
    val eligible = eligibleNotificationCount(notifications)
    val dugTiles = (0 until GameBalance.EXCAVATION_TILE_COUNT)
      .filterTo(linkedSetOf()) { tileIndex -> dugMask and (1L shl tileIndex) != 0L }
    val spent = dugTiles.size * GameBalance.ENERGY_PER_TILE
    val earned = eligible * GameBalance.ENERGY_PER_ELIGIBLE_NOTIFICATION
    return DailyExcavationSnapshot(
      artifactId = artifactId,
      capturedNotificationCount = notifications.size,
      eligibleNotificationCount = eligible,
      energyPerNotification = GameBalance.ENERGY_PER_ELIGIBLE_NOTIFICATION,
      energyEarned = earned,
      energySpent = spent,
      energyAvailable = maxOf(0, earned - spent),
      tileEnergyCost = GameBalance.ENERGY_PER_TILE,
      gridColumns = GameBalance.EXCAVATION_GRID_COLUMNS,
      gridRows = GameBalance.EXCAVATION_GRID_ROWS,
      dugTiles = dugTiles,
      completedAtEpochMillis = completedAtEpochMillis,
    )
  }

  private fun eligibleNotificationCount(notifications: List<ReducedNotificationEntity>): Int {
    val latestAcceptedBySource = mutableMapOf<String, Long>()
    var eligible = 0
    for (notification in notifications.sortedBy(ReducedNotificationEntity::occurredAtEpochMillis)) {
      val previous = latestAcceptedBySource[notification.sourceToken]
      if (
        previous == null ||
        notification.occurredAtEpochMillis - previous >= GameBalance.SOURCE_NOTIFICATION_COOLDOWN_MILLIS
      ) {
        latestAcceptedBySource[notification.sourceToken] = notification.occurredAtEpochMillis
        eligible += 1
      }
    }
    return eligible
  }

  private fun ObservationState?.toFormationObservation(
    currentAccessState: ListenerAccessState?,
  ): FormationObservation = when (this) {
    ObservationState.OBSERVED -> FormationObservation.SealedObserved
    ObservationState.UNOBSERVED -> FormationObservation.SealedUnobserved
    null -> when (currentAccessState) {
      ListenerAccessState.ACTIVE -> FormationObservation.Active
      ListenerAccessState.DISCONNECTED -> FormationObservation.Disconnected
      ListenerAccessState.REVOKED -> FormationObservation.Revoked
      null -> FormationObservation.AwaitingAccess
    }
  }

  private data class LocalFormationInputs(
    val layers: List<FormationLayer>,
    val notifications: List<ReducedNotificationEntity>,
    val accessState: ListenerAccessState?,
    val excavation: DailyExcavationEntity?,
  )

  private data class SealedFormationInputs(
    val observationState: ObservationState?,
    val specimen: MuseumSpecimen?,
  )
}
