package com.techfullymade.afterchime.sealing

import androidx.room.Room
import com.techfullymade.afterchime.aggregation.DailyNotificationSummaryCalculator
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSealDayStoreTest {
  private lateinit var database: AfterchimeDatabase
  private lateinit var store: RoomSealDayStore

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    store = RoomSealDayStore(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `normal seal stores conserving canonical totals then purges raw rows`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val raw = rawNotification(localDate)
    database.reducedNotificationDao().insert(raw)

    assertEquals(listOf(localDate), store.candidateLocalDatesBefore(localDate.plusDays(1)))
    val result = store.persist(sealedDay(localDate, createdAtEpochMillis = 200L))

    assertEquals(SealDayPersistence.Persisted, result)
    assertTrue(database.reducedNotificationDao().forLocalDate(localDate.toString()).isEmpty())
    val totals = requireNotNull(database.dailyNotificationSummaryDao().get(localDate.toString()))
    assertEquals(1, totals.eligibleCount)
    assertTrue(totals.categoryCountsJson.contains("\"email\":1"))
    assertFalse(totals.categoryCountsJson.contains(", "))
    val categoryKeys = Regex("\"([a-z_]+)\":").findAll(totals.categoryCountsJson)
      .map { it.groupValues[1] }
      .toList()
    assertEquals(categoryKeys.sorted(), categoryKeys)
    assertEquals("[{\"hour\":9,\"count\":1}]", totals.hourlyBucketsJson)
    assertEquals(
      totals.eligibleCount,
      Regex(":(\\d+)").findAll(totals.categoryCountsJson).sumOf { it.groupValues[1].toInt() },
    )
    assertEquals(
      totals.eligibleCount,
      Regex("\"count\":(\\d+)").findAll(totals.hourlyBucketsJson)
        .sumOf { it.groupValues[1].toInt() },
    )
    val persistedPayload = totals.categoryCountsJson + totals.hourlyBucketsJson
    assertFalse(persistedPayload.contains(raw.id))
    assertFalse(persistedPayload.contains(raw.sourceToken))
    assertFalse(persistedPayload.contains(raw.sourceColourRgb.toString()))
    assertFalse(persistedPayload.contains(raw.occurredAtEpochMillis.toString()))
    assertNull(totals.uploadedRevision)
    assertNull(totals.uploadedAtEpochMillis)
  }

  @Test
  fun `legacy sealed day reconciles original metadata and purges without another specimen`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-02")
    val original = DaySummaryEntity(
      localDate = localDate.toString(),
      timezoneOffsetMinutes = 330,
      observationState = ObservationState.UNOBSERVED,
      generatorVersion = 1,
      createdAtEpochMillis = 123L,
    )
    database.daySummaryDao().insert(original)
    database.reducedNotificationDao().insert(rawNotification(localDate))

    assertEquals(listOf(localDate), store.candidateLocalDatesBefore(localDate.plusDays(1)))
    val proposed = sealedDay(
      localDate = localDate,
      timezoneOffset = ZoneOffset.ofHours(-8),
      observationState = ObservationState.OBSERVED,
      createdAtEpochMillis = 999L,
    )
    assertEquals(SealDayPersistence.AlreadySealed, store.persist(proposed))

    val reconciled = requireNotNull(database.dailyNotificationSummaryDao().get(localDate.toString()))
    assertEquals(original.timezoneOffsetMinutes, reconciled.timezoneOffsetMinutes)
    assertEquals(0, reconciled.observationCompleteness)
    assertEquals(original.createdAtEpochMillis, reconciled.createdAtEpochMillis)
    assertTrue(database.reducedNotificationDao().forLocalDate(localDate.toString()).isEmpty())
    assertNull(database.specimenDao().getByAnchoredLocalDate(localDate.toString()))
    assertTrue(store.candidateLocalDatesBefore(localDate.plusDays(1)).isEmpty())

    assertEquals(SealDayPersistence.AlreadySealed, store.persist(proposed))
    assertEquals(reconciled, database.dailyNotificationSummaryDao().get(localDate.toString()))
    assertNull(database.specimenDao().getByAnchoredLocalDate(localDate.toString()))
  }

  @Test
  fun `delete failure rolls back day totals and preserves raw rows for retry`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    database.reducedNotificationDao().insert(rawNotification(localDate))
    database.openHelper.writableDatabase.execSQL(
      """
      CREATE TRIGGER fail_reduced_notification_delete
      BEFORE DELETE ON reduced_notifications
      BEGIN
        SELECT RAISE(ABORT, 'simulated delete failure');
      END
      """.trimIndent(),
    )

    val failure = runCatching { store.persist(sealedDay(localDate, createdAtEpochMillis = 200L)) }

    assertTrue(failure.isFailure)
    assertNull(database.daySummaryDao().get(localDate.toString()))
    assertNull(database.dailyNotificationSummaryDao().get(localDate.toString()))
    assertEquals(1, database.reducedNotificationDao().forLocalDate(localDate.toString()).size)

    database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_reduced_notification_delete")
    assertEquals(
      SealDayPersistence.Persisted,
      store.persist(sealedDay(localDate, createdAtEpochMillis = 200L)),
    )
    assertTrue(database.reducedNotificationDao().forLocalDate(localDate.toString()).isEmpty())
    assertEquals(1, database.dailyNotificationSummaryDao().get(localDate.toString())?.eligibleCount)
  }

  private fun sealedDay(
    localDate: LocalDate,
    timezoneOffset: ZoneOffset = ZoneOffset.UTC,
    observationState: ObservationState = ObservationState.OBSERVED,
    createdAtEpochMillis: Long,
  ): SealedDay {
    val notification = rawNotification(localDate).toDomain()
    return SealedDay(
      localDate = localDate,
      summary = DaySummaryEntity(
        localDate = localDate.toString(),
        timezoneOffsetMinutes = timezoneOffset.totalSeconds / 60,
        observationState = observationState,
        generatorVersion = 1,
        createdAtEpochMillis = createdAtEpochMillis,
      ),
      notificationSummary = DailyNotificationSummaryCalculator.calculate(
        localDate = localDate,
        timezoneOffset = timezoneOffset,
        uninterruptedObservation = observationState == ObservationState.OBSERVED,
        notifications = listOf(notification),
      ),
      specimen = null,
    )
  }

  private fun rawNotification(localDate: LocalDate): ReducedNotificationEntity =
    ReducedNotificationEntity(
      id = "event-secret-${localDate}",
      occurredAtEpochMillis = Instant.parse("${localDate}T09:00:00Z").toEpochMilli(),
      localDate = localDate.toString(),
      localHour = 9,
      category = CoarseNotificationCategory.EMAIL,
      sourceToken = "a".repeat(64),
      sourceColourRgb = 0x123456,
    )

  private fun ReducedNotificationEntity.toDomain(): ReducedNotification = ReducedNotification(
    occurredAtEpochMillis = occurredAtEpochMillis,
    localHour = localHour,
    category = category,
    sourceToken = sourceToken,
    sourceColourRgb = sourceColourRgb,
  )
}
