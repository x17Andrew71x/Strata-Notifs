package com.techfullymade.afterchime.sealing

import androidx.room.withTransaction
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.domain.CollectibleState
import java.time.LocalDate

/** Room adapter that keeps the summary and optional specimen in one SQLite transaction. */
class RoomSealDayStore(
  private val database: AfterchimeDatabase,
) : SealDayStore {
  override suspend fun candidateLocalDatesBefore(exclusiveDate: LocalDate): List<LocalDate> {
    val exclusiveLocalDate = exclusiveDate.toString()
    val earliestLocalDate = listOfNotNull(
      database.reducedNotificationDao().earliestLocalDateBefore(exclusiveLocalDate),
      database.listenerAccessStateDao().earliestLocalDateBefore(exclusiveLocalDate),
    )
      .minOrNull()
      ?.let(LocalDate::parse)
      ?: return emptyList()

    val alreadySealedDates = database.daySummaryDao()
      .localDatesBefore(exclusiveLocalDate)
      .mapTo(mutableSetOf(), LocalDate::parse)

    return generateSequence(earliestLocalDate) { localDate ->
      localDate.plusDays(1).takeIf { it < exclusiveDate }
    }
      .filter { localDate -> localDate !in alreadySealedDates }
      .toList()
  }

  override suspend fun inputsFor(localDate: LocalDate): SealDayInputs {
    val localDateString = localDate.toString()
    val accessStateForDay = database.listenerAccessStateDao().get(localDateString)
    val effectiveAccessState = accessStateForDay
      ?: database.listenerAccessStateDao().latestOnOrBefore(localDateString)
    return SealDayInputs(
      notifications = database.reducedNotificationDao().forLocalDate(localDateString).map { notification ->
        ReducedNotification(
          occurredAtEpochMillis = notification.occurredAtEpochMillis,
          localHour = notification.localHour,
          category = notification.category,
          sourceToken = notification.sourceToken,
          sourceColourRgb = notification.sourceColourRgb,
        )
      },
      latestListenerAccessState = effectiveAccessState?.latestState,
      accessWasInterrupted = effectiveAccessState == null ||
        effectiveAccessState.latestState != ListenerAccessState.ACTIVE ||
        (accessStateForDay != null && (
          accessStateForDay.activeAtEpochMillis == null ||
            accessStateForDay.disconnectedAtEpochMillis != null ||
            accessStateForDay.revokedAtEpochMillis != null
          )),
    )
  }

  override suspend fun persist(sealedDay: SealedDay): SealDayPersistence = database.withTransaction {
    val localDate = sealedDay.localDate.toString()
    if (database.daySummaryDao().get(localDate) != null) {
      return@withTransaction SealDayPersistence.AlreadySealed
    }

    database.daySummaryDao().insert(sealedDay.summary)
    if (database.specimenDao().getByAnchoredLocalDate(localDate) != null) {
      return@withTransaction SealDayPersistence.Persisted
    }
    val specimen = sealedDay.specimen
    if (specimen != null) {
      database.specimenDao().insert(specimen.record)
      database.specimenOutputDao().insert(specimen.output)
      database.inventoryItemDao().insert(
        InventoryItemEntity(
          itemId = specimen.record.specimenId,
          sourceSpecimenId = specimen.record.specimenId,
          anchoredLocalDate = specimen.record.anchoredLocalDate,
          generatorVersion = specimen.record.generatorVersion,
          createdAtEpochMillis = specimen.record.createdAtEpochMillis,
          revealedAtEpochMillis = specimen.record.revealedAtEpochMillis,
          isLocked = specimen.record.isLocked,
          collectibleState = CollectibleState.ORDINARY,
          provenanceCount = 1,
          family = specimen.output.family,
          tier = specimen.output.tier,
          hueDegrees = specimen.output.hueDegrees,
          strataCount = specimen.output.strataCount,
          inclusionDensityPercent = specimen.output.inclusionDensityPercent,
          reliefPercent = specimen.output.reliefPercent,
          rotationDegrees = specimen.output.rotationDegrees,
          consumedByMutationId = null,
        ),
      )
    }
    SealDayPersistence.Persisted
  }
}
