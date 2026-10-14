package com.techfullymade.afterchime.sealing

import androidx.room.withTransaction
import com.techfullymade.afterchime.aggregation.DailyNotificationSummary
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.DailyNotificationSummaryEntity
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.domain.CollectibleState
import java.time.LocalDate
import java.util.Locale

/** Room adapter that atomically replaces sealed-day notification rows with privacy-safe totals. */
class RoomSealDayStore(
  private val database: AfterchimeDatabase,
) : SealDayStore {
  override suspend fun candidateLocalDatesBefore(exclusiveDate: LocalDate): List<LocalDate> {
    val exclusiveLocalDate = exclusiveDate.toString()
    val reducedNotificationDates = database.reducedNotificationDao()
      .localDatesBefore(exclusiveLocalDate)
      .mapTo(mutableSetOf(), LocalDate::parse)
    val earliestLocalDate = listOfNotNull(
      reducedNotificationDates.minOrNull(),
      database.listenerAccessStateDao().earliestLocalDateBefore(exclusiveLocalDate)?.let(LocalDate::parse),
    ).minOrNull() ?: return emptyList()

    val alreadySealedDates = database.daySummaryDao()
      .localDatesBefore(exclusiveLocalDate)
      .mapTo(mutableSetOf(), LocalDate::parse)

    return generateSequence(earliestLocalDate) { localDate ->
      localDate.plusDays(1).takeIf { it < exclusiveDate }
    }
      .filter { localDate -> localDate !in alreadySealedDates || localDate in reducedNotificationDates }
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
      existingSummary = database.daySummaryDao().get(localDateString),
    )
  }

  override suspend fun persist(sealedDay: SealedDay): SealDayPersistence = database.withTransaction {
    val localDate = sealedDay.localDate.toString()
    val existingDaySummary = database.daySummaryDao().get(localDate)
    val persistedDaySummary = existingDaySummary ?: sealedDay.summary.also {
      database.daySummaryDao().insert(it)
    }

    if (database.dailyNotificationSummaryDao().get(localDate) == null) {
      database.dailyNotificationSummaryDao().insert(
        sealedDay.notificationSummary.toEntity(persistedDaySummary),
      )
    }

    if (existingDaySummary == null && database.specimenDao().getByAnchoredLocalDate(localDate) == null) {
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
    }

    // Raw rows are removed only after every durable sealed-day write above has succeeded.
    database.reducedNotificationDao().deleteForLocalDate(localDate)

    if (existingDaySummary == null) {
      SealDayPersistence.Persisted
    } else {
      SealDayPersistence.AlreadySealed
    }
  }

  private fun DailyNotificationSummary.toEntity(
    persistedDaySummary: DaySummaryEntity,
  ): DailyNotificationSummaryEntity {
    require(categoryTotals.map { it.category }.toSet() == CoarseNotificationCategory.values().toSet())
    require(categoryTotals.all { it.eligibleCount >= 0 })
    require(categoryTotals.sumOf { it.eligibleCount.toLong() } == eligibleCount.toLong())
    require(hourlyBuckets.map { it.localHour }.distinct().size == hourlyBuckets.size)
    require(hourlyBuckets.all { it.localHour in 0..23 && it.eligibleCount > 0 })
    require(hourlyBuckets.sumOf { it.eligibleCount.toLong() } == eligibleCount.toLong())

    val categoryCountsJson = categoryTotals
      .sortedBy { it.category.name.lowercase(Locale.ROOT) }
      .joinToString(separator = ",", prefix = "{", postfix = "}") { total ->
        "\"${total.category.name.lowercase(Locale.ROOT)}\":${total.eligibleCount}"
      }
    val hourlyBucketsJson = hourlyBuckets
      .sortedBy { it.localHour }
      .joinToString(separator = ",", prefix = "[", postfix = "]") { bucket ->
        "{\"hour\":${bucket.localHour},\"count\":${bucket.eligibleCount}}"
      }

    return DailyNotificationSummaryEntity(
      localDate = persistedDaySummary.localDate,
      timezoneOffsetMinutes = persistedDaySummary.timezoneOffsetMinutes,
      revision = revision,
      rulesVersion = rulesVersion,
      eligibleCount = eligibleCount,
      categoryCountsJson = categoryCountsJson,
      hourlyBucketsJson = hourlyBucketsJson,
      gamePressure = gamePressure,
      observationCompleteness = when (persistedDaySummary.observationState) {
        ObservationState.OBSERVED -> COMPLETE_OBSERVATION_PERCENT
        ObservationState.UNOBSERVED -> NO_OBSERVATION_PERCENT
      },
      createdAtEpochMillis = persistedDaySummary.createdAtEpochMillis,
      uploadedRevision = null,
      uploadedAtEpochMillis = null,
    )
  }

  private companion object {
    const val COMPLETE_OBSERVATION_PERCENT = 100
    const val NO_OBSERVATION_PERCENT = 0
  }
}
