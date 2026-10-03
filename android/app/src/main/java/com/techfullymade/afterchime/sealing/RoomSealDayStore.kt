package com.techfullymade.afterchime.sealing

import androidx.room.withTransaction
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
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

    val alreadySealedDates = (
      database.daySummaryDao().localDatesBefore(exclusiveLocalDate) +
        database.specimenDao().anchoredLocalDatesBefore(exclusiveLocalDate)
      ).mapTo(mutableSetOf(), LocalDate::parse)

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
    if (
      database.daySummaryDao().get(localDate) != null ||
      database.specimenDao().getByAnchoredLocalDate(localDate) != null
    ) {
      return@withTransaction SealDayPersistence.AlreadySealed
    }

    database.daySummaryDao().insert(sealedDay.summary)
    val specimen = sealedDay.specimen
    if (specimen != null) {
      database.specimenDao().insert(specimen.record)
      database.specimenOutputDao().insert(specimen.output)
    }
    SealDayPersistence.Persisted
  }
}
