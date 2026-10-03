package com.techfullymade.afterchime.sealing

import androidx.room.Room
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.GenerationResult
import com.techfullymade.afterchime.generation.Specimen
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SealRecoveryTest {
  private lateinit var database: AfterchimeDatabase

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `room store seals an observed completed day atomically`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    database.listenerAccessStateDao().upsert(activeAccessState(localDate))
    database.reducedNotificationDao().insert(notification(localDate))
    val useCase = SealDayUseCase(
      store = RoomSealDayStore(database),
      localSecretProvider = { ByteArray(32) { 7 } },
      clock = Clock.fixed(Instant.parse("2026-10-04T00:05:00Z"), ZoneOffset.UTC),
      timeZone = ZoneOffset.UTC,
    )

    val outcomes = useCase.sealEligibleDays()

    assertEquals(listOf(SealDayOutcome.Sealed::class), outcomes.map { it::class })
    assertEquals(ObservationState.OBSERVED, database.daySummaryDao().get(localDate.toString())?.observationState)
    assertNotNull(database.specimenDao().getByAnchoredLocalDate(localDate.toString()))
  }

  @Test
  fun `active listener evidence carries forward to seal a quiet completed day`() = runBlocking {
    val connectedDate = LocalDate.parse("2026-10-02")
    val quietDate = connectedDate.plusDays(1)
    database.listenerAccessStateDao().upsert(activeAccessState(connectedDate))
    val useCase = SealDayUseCase(
      store = RoomSealDayStore(database),
      localSecretProvider = { ByteArray(32) { 7 } },
      clock = Clock.fixed(Instant.parse("2026-10-04T00:05:00Z"), ZoneOffset.UTC),
      timeZone = ZoneOffset.UTC,
    )

    val outcomes = useCase.sealEligibleDays()

    assertEquals(listOf(connectedDate, quietDate), outcomes.map(SealDayOutcome::localDate))
    val sealedQuietDay = requireNotNull(database.daySummaryDao().get(quietDate.toString()))
    assertEquals(ObservationState.OBSERVED, sealedQuietDay.observationState)
    assertNotNull(database.specimenDao().getByAnchoredLocalDate(quietDate.toString()))
  }

  @Test
  fun `room store does not regenerate already sealed days`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    database.listenerAccessStateDao().upsert(activeAccessState(localDate))
    database.reducedNotificationDao().insert(notification(localDate))
    val firstUseCase = SealDayUseCase(
      store = RoomSealDayStore(database),
      localSecretProvider = { ByteArray(32) { 7 } },
      clock = Clock.fixed(Instant.parse("2026-10-04T00:05:00Z"), ZoneOffset.UTC),
      timeZone = ZoneOffset.UTC,
    )
    firstUseCase.sealEligibleDays()

    val retryWithUnavailableSecret = SealDayUseCase(
      store = RoomSealDayStore(database),
      localSecretProvider = { error("already sealed days must not reload the local secret") },
      clock = Clock.fixed(Instant.parse("2026-10-04T00:05:00Z"), ZoneOffset.UTC),
      timeZone = ZoneOffset.UTC,
    )

    assertEquals(emptyList<SealDayOutcome>(), retryWithUnavailableSecret.sealEligibleDays())
    assertNotNull(database.specimenDao().getByAnchoredLocalDate(localDate.toString()))
  }

  @Test
  fun `sealing preserves the exact generated specimen output`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val generated = Specimen(
      generatorVersion = 1,
      anchoredLocalDate = localDate,
      family = Family.GEODE,
      tier = Tier.EXCEPTIONAL,
      visual = VisualParameters(
        hueDegrees = 143,
        strataCount = 11,
        inclusionDensityPercent = 72,
        reliefPercent = 63,
        rotationDegrees = 217,
      ),
    )
    database.listenerAccessStateDao().upsert(activeAccessState(localDate))
    database.reducedNotificationDao().insert(notification(localDate))
    val useCase = SealDayUseCase(
      store = RoomSealDayStore(database),
      localSecretProvider = { ByteArray(32) { 7 } },
      clock = Clock.fixed(Instant.parse("2026-10-04T00:05:00Z"), ZoneOffset.UTC),
      timeZone = ZoneOffset.UTC,
      generator = { _, _ -> GenerationResult.Generated(generated) },
    )

    useCase.sealEligibleDays()

    val sealed = requireNotNull(database.specimenDao().getByAnchoredLocalDate(localDate.toString()))
    val output = requireNotNull(database.specimenOutputDao().getBySpecimenId(sealed.specimenId))
    assertEquals(generated.family, output.family)
    assertEquals(generated.tier, output.tier)
    assertEquals(generated.visual.hueDegrees, output.hueDegrees)
    assertEquals(generated.visual.strataCount, output.strataCount)
    assertEquals(generated.visual.inclusionDensityPercent, output.inclusionDensityPercent)
    assertEquals(generated.visual.reliefPercent, output.reliefPercent)
    assertEquals(generated.visual.rotationDegrees, output.rotationDegrees)
  }

  @Test
  fun `room transaction rolls back the summary when specimen insertion crashes`() = runBlocking {
    val targetDate = LocalDate.parse("2026-10-03")
    val collisionId = "collision"
    database.specimenDao().insert(
      SpecimenEntity(
        specimenId = collisionId,
        anchoredLocalDate = "2026-10-02",
        generatorVersion = 1,
        createdAtEpochMillis = 1,
        revealedAtEpochMillis = null,
      ),
    )
    val target = SealedDay(
      localDate = targetDate,
      summary = DaySummaryEntity(
        localDate = targetDate.toString(),
        timezoneOffsetMinutes = 0,
        observationState = ObservationState.OBSERVED,
        generatorVersion = 1,
        createdAtEpochMillis = 1,
      ),
      specimen = SealedSpecimen(
        record = SpecimenEntity(
          specimenId = collisionId,
          anchoredLocalDate = targetDate.toString(),
          generatorVersion = 1,
          createdAtEpochMillis = 1,
          revealedAtEpochMillis = null,
        ),
        output = SpecimenOutputEntity(
          specimenId = collisionId,
          family = Family.GEODE,
          tier = Tier.EXCEPTIONAL,
          hueDegrees = 143,
          strataCount = 11,
          inclusionDensityPercent = 72,
          reliefPercent = 63,
          rotationDegrees = 217,
        ),
      ),
    )

    assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
      runBlocking { RoomSealDayStore(database).persist(target) }
    }

    assertNull(database.daySummaryDao().get(targetDate.toString()))
    assertNull(database.specimenDao().getByAnchoredLocalDate(targetDate.toString()))
  }

  private fun activeAccessState(localDate: LocalDate) = ListenerAccessStateEntity(
    localDate = localDate.toString(),
    activeAtEpochMillis = 1,
    disconnectedAtEpochMillis = null,
    revokedAtEpochMillis = null,
    latestState = ListenerAccessState.ACTIVE,
    updatedAtEpochMillis = 1,
  )

  private fun notification(localDate: LocalDate) = ReducedNotificationEntity(
    id = "event-1",
    occurredAtEpochMillis = Instant.parse("2026-10-03T09:00:00Z").toEpochMilli(),
    localDate = localDate.toString(),
    localHour = 9,
    category = CoarseNotificationCategory.EMAIL,
    sourceToken = "a".repeat(64),
    sourceColourRgb = 0x123456,
  )
}
