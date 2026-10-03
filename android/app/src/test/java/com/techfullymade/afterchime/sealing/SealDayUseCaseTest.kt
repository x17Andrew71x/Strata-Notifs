package com.techfullymade.afterchime.sealing

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealDayUseCaseTest {
  @Test
  fun `normal midnight sealing persists one observed specimen`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(
      inputs = mapOf(
        localDate to inputs(ListenerAccessState.ACTIVE),
      ),
    )
    val useCase = useCase(
      store = store,
      instant = "2026-10-04T00:05:00Z",
    )

    val outcomes = useCase.sealEligibleDays()

    assertEquals(listOf(SealDayOutcome.Sealed::class), outcomes.map { it::class })
    val sealed = requireNotNull(store.sealedDay(localDate))
    assertEquals(ObservationState.OBSERVED, sealed.summary.observationState)
    assertEquals(localDate.toString(), sealed.specimen?.record?.anchoredLocalDate)
  }

  @Test
  fun `app off catch up seals every known prior day in date order`() = runBlocking {
    val first = LocalDate.parse("2026-10-01")
    val second = first.plusDays(1)
    val third = second.plusDays(1)
    val store = InMemorySealDayStore(
      inputs = mapOf(
        third to inputs(ListenerAccessState.ACTIVE),
        first to inputs(ListenerAccessState.ACTIVE),
        second to inputs(ListenerAccessState.ACTIVE),
      ),
    )

    val outcomes = useCase(store, "2026-10-04T08:00:00Z").sealEligibleDays()

    assertEquals(listOf(first, second, third), outcomes.map(SealDayOutcome::localDate))
    assertEquals(listOf(first, second, third), store.sealedLocalDates())
  }

  @Test
  fun `duplicate worker execution returns existing day without another specimen`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(mapOf(localDate to inputs(ListenerAccessState.ACTIVE)))
    val useCase = useCase(store, "2026-10-04T00:05:00Z")

    useCase.sealEligibleDays()
    val retry = useCase.sealEligibleDays()

    assertEquals(listOf(SealDayOutcome.AlreadySealed::class), retry.map { it::class })
    assertEquals(1, store.specimenCount())
  }

  @Test
  fun `crash between summary and specimen insert leaves no partial state and retry seals once`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(
      inputs = mapOf(localDate to inputs(ListenerAccessState.ACTIVE)),
      crashAfterSummaryWrite = true,
    )
    val useCase = useCase(store, "2026-10-04T00:05:00Z")

    val failure = runCatching { useCase.sealEligibleDays() }

    assertTrue(failure.isFailure)
    assertNull(store.sealedDay(localDate))
    store.crashAfterSummaryWrite = false

    val retry = useCase.sealEligibleDays()

    assertEquals(listOf(SealDayOutcome.Sealed::class), retry.map { it::class })
    assertEquals(1, store.specimenCount())
  }

  @Test
  fun `timezone change never mints a second specimen for an anchored local day`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(mapOf(localDate to inputs(ListenerAccessState.ACTIVE)))

    useCase(store, "2026-10-04T08:00:00Z", ZoneId.of("America/Los_Angeles")).sealEligibleDays()
    val afterTravel = useCase(store, "2026-10-05T08:00:00Z", ZoneId.of("Asia/Tokyo")).sealEligibleDays()

    assertEquals(listOf(SealDayOutcome.AlreadySealed::class), afterTravel.map { it::class })
    assertEquals(1, store.specimenCount())
  }

  @Test
  fun `revoked permission seals an unobserved day without a specimen`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(mapOf(localDate to inputs(ListenerAccessState.REVOKED)))

    useCase(store, "2026-10-04T00:05:00Z").sealEligibleDays()

    val sealed = requireNotNull(store.sealedDay(localDate))
    assertEquals(ObservationState.UNOBSERVED, sealed.summary.observationState)
    assertNull(sealed.specimen)
  }

  @Test
  fun `clock rollback does not reseal the active anchored date`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-03")
    val store = InMemorySealDayStore(mapOf(localDate to inputs(ListenerAccessState.ACTIVE)))

    useCase(store, "2026-10-04T00:05:00Z").sealEligibleDays()
    val rolledBack = useCase(store, "2026-10-03T23:55:00Z").sealEligibleDays()

    assertTrue(rolledBack.isEmpty())
    assertEquals(1, store.specimenCount())
  }

  private fun useCase(
    store: SealDayStore,
    instant: String,
    timeZone: ZoneId = ZoneOffset.UTC,
  ): SealDayUseCase = SealDayUseCase(
    store = store,
    localSecretProvider = { ByteArray(32) { 7 } },
    clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
    timeZone = timeZone,
  )

  private fun inputs(accessState: ListenerAccessState): SealDayInputs = SealDayInputs(
    notifications = listOf(
      ReducedNotification(
        occurredAtEpochMillis = Instant.parse("2026-10-03T09:00:00Z").toEpochMilli(),
        localHour = 9,
        category = CoarseNotificationCategory.EMAIL,
        sourceToken = "a".repeat(64),
        sourceColourRgb = 0x123456,
      ),
    ),
    latestListenerAccessState = accessState,
    accessWasInterrupted = accessState != ListenerAccessState.ACTIVE,
  )

  private class InMemorySealDayStore(
    private val inputs: Map<LocalDate, SealDayInputs>,
    var crashAfterSummaryWrite: Boolean = false,
  ) : SealDayStore {
    private val sealed = linkedMapOf<LocalDate, SealedDay>()

    override suspend fun candidateLocalDatesBefore(exclusiveDate: LocalDate): List<LocalDate> =
      inputs.keys.filter { it < exclusiveDate }.sorted()

    override suspend fun inputsFor(localDate: LocalDate): SealDayInputs =
      requireNotNull(inputs[localDate])

    override suspend fun persist(sealedDay: SealedDay): SealDayPersistence = synchronized(this) {
      if (sealed.containsKey(sealedDay.localDate)) {
        return@synchronized SealDayPersistence.AlreadySealed
      }
      if (crashAfterSummaryWrite) {
        throw IllegalStateException("simulated crash after summary write")
      }
      sealed[sealedDay.localDate] = sealedDay
      SealDayPersistence.Persisted
    }

    fun sealedDay(localDate: LocalDate): SealedDay? = sealed[localDate]

    fun sealedLocalDates(): List<LocalDate> = sealed.keys.toList()

    fun specimenCount(): Int = sealed.values.count { it.specimen != null }
  }
}
