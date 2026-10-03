package com.techfullymade.afterchime.capture

import androidx.room.Room
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObservationRepositoryTest {
  private lateinit var database: AfterchimeDatabase
  private lateinit var repository: ObservationRepository

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    repository = ObservationRepository(
      listenerAccessStateDao = database.listenerAccessStateDao(),
      reducedNotificationDao = database.reducedNotificationDao(),
      timeZone = TIME_ZONE,
    )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `records listener connection disconnect and revocation for one local day`() = runBlocking {
    val connectedAt = epochMillis("2026-10-03T07:30:00Z")
    val disconnectedAt = epochMillis("2026-10-03T12:00:00Z")
    val revokedAt = epochMillis("2026-10-03T13:00:00Z")

    repository.recordListenerConnected(connectedAt)
    repository.recordListenerDisconnected(disconnectedAt)
    repository.recordNotificationAccessRevoked(revokedAt)

    assertEquals(
      ListenerAccessState.REVOKED,
      requireNotNull(repository.listenerAccessStateFor("2026-10-03")).latestState,
    )
    requireNotNull(repository.listenerAccessStateFor("2026-10-03")).also { state ->
      assertEquals(connectedAt, state.activeAtEpochMillis)
      assertEquals(disconnectedAt, state.disconnectedAtEpochMillis)
      assertEquals(revokedAt, state.revokedAtEpochMillis)
      assertEquals(revokedAt, state.updatedAtEpochMillis)
    }
    Unit
  }

  @Test
  fun `repeated callbacks are idempotent and a later reconnection preserves access-loss evidence`() = runBlocking {
    val connectedAt = epochMillis("2026-10-03T07:30:00Z")
    val revokedAt = epochMillis("2026-10-03T13:00:00Z")
    val reconnectedAt = epochMillis("2026-10-03T15:00:00Z")

    repository.recordListenerConnected(connectedAt)
    repository.recordListenerConnected(connectedAt)
    repository.recordNotificationAccessRevoked(revokedAt)
    repository.recordListenerConnected(reconnectedAt)
    repository.recordListenerConnected(reconnectedAt)
    repository.recordListenerDisconnected(epochMillis("2026-10-03T12:00:00Z"))

    requireNotNull(repository.listenerAccessStateFor("2026-10-03")).also { state ->
      assertEquals(ListenerAccessState.ACTIVE, state.latestState)
      assertEquals(connectedAt, state.activeAtEpochMillis)
      assertEquals(revokedAt, state.revokedAtEpochMillis)
      assertEquals(reconnectedAt, state.updatedAtEpochMillis)
    }
    assertEquals(1, database.listenerAccessStateDao().countForLocalDate("2026-10-03"))
  }

  @Test
  fun `persists a reduced notification under its local date without creating observation data for a quiet day`() = runBlocking {
    val occurredAt = epochMillis("2026-10-03T06:30:00Z")
    val reduced = ReducedNotification(
      occurredAtEpochMillis = occurredAt,
      localHour = 23,
      category = CoarseNotificationCategory.EMAIL,
      sourceToken = "a".repeat(64),
      sourceColourRgb = 0x123456,
    )

    repository.recordReducedNotification(reduced)
    repository.recordReducedNotification(reduced)

    assertEquals(1, database.reducedNotificationDao().forLocalDate("2026-10-02").size)
    assertNull(repository.listenerAccessStateFor("2026-10-02"))
  }

  private fun epochMillis(value: String): Long = Instant.parse(value).toEpochMilli()

  private companion object {
    val TIME_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
  }
}
