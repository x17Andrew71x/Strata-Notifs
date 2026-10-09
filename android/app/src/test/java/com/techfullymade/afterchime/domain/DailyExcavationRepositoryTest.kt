package com.techfullymade.afterchime.domain

import androidx.room.Room
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.gameplay.FossilCatalog
import com.techfullymade.afterchime.gameplay.GameBalance
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyExcavationRepositoryTest {
  private lateinit var database: AfterchimeDatabase
  private lateinit var repository: LocalFormationRepository
  private val localDate = LocalDate.parse("2026-10-09")
  private val now = Instant.parse("2026-10-09T12:00:00Z")

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
    repository = LocalFormationRepository(
      database = database,
      clock = Clock.fixed(now, ZoneOffset.UTC),
      selectDailyFossil = { FossilCatalog.find("relic-fossil-choir")!! },
    )
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun `one energy is earned per eligible notification with a five minute per source cooldown`() = runBlocking {
    val sourceA = "a".repeat(64)
    val sourceB = "b".repeat(64)
    insertNotification("one", sourceA, now.toEpochMilli())
    insertNotification("two", sourceA, now.plusSeconds(60).toEpochMilli())
    insertNotification("three", sourceB, now.plusSeconds(60).toEpochMilli())
    insertNotification("four", sourceA, now.plusSeconds(300).toEpochMilli())

    val snapshot = repository.observe(localDate).first().excavation!!

    assertEquals(4, snapshot.capturedNotificationCount)
    assertEquals(3, snapshot.eligibleNotificationCount)
    assertEquals(3, snapshot.energyEarned)
    assertEquals(GameBalance.ENERGY_PER_TILE, snapshot.energyAvailable)
    assertEquals("relic-fossil-choir", snapshot.artifactId)
  }

  @Test
  fun `digging spends three energy once and persists the selected tile`() = runBlocking {
    repeat(GameBalance.ENERGY_PER_TILE) { index ->
      insertNotification("event-$index", index.toString(16).padStart(64, '0'), now.toEpochMilli())
    }

    assertEquals(DigResult.Dug(tileIndex = 12, energyAvailable = 0), repository.dig(localDate, 12, now.toEpochMilli()))
    assertEquals(DigResult.AlreadyDug, repository.dig(localDate, 12, now.toEpochMilli()))

    val excavation = repository.observe(localDate).first().excavation!!
    assertEquals(setOf(12), excavation.dugTiles)
    assertEquals(0, excavation.energyAvailable)
  }

  @Test
  fun `the final tile mints the preselected fossil directly into the museum`() = runBlocking {
    repeat(GameBalance.EXCAVATION_TOTAL_ENERGY) { index ->
      insertNotification("event-$index", index.toString(16).padStart(64, '0'), now.toEpochMilli())
    }

    var result: DigResult = DigResult.Unavailable
    repeat(GameBalance.EXCAVATION_TILE_COUNT) { tileIndex ->
      result = repository.dig(localDate, tileIndex, now.plusSeconds(tileIndex.toLong()).toEpochMilli())
    }

    assertTrue(result is DigResult.Completed)
    val snapshot = repository.observe(localDate).first()
    val excavation = snapshot.excavation!!
    assertTrue(excavation.completed)
    assertEquals(GameBalance.EXCAVATION_TILE_COUNT, excavation.dugTiles.size)
    assertNotNull(snapshot.sealedSpecimen)
    assertEquals("relic-fossil-choir", snapshot.sealedSpecimen?.catalogItemId)
    assertEquals(now.plusSeconds(24).toEpochMilli(), snapshot.sealedSpecimen?.revealedAtEpochMillis)
    assertEquals(1, database.inventoryItemDao().observeAllActive().first().size)
  }

  private suspend fun insertNotification(id: String, sourceToken: String, occurredAtEpochMillis: Long) {
    database.reducedNotificationDao().insert(
      ReducedNotificationEntity(
        id = id,
        occurredAtEpochMillis = occurredAtEpochMillis,
        localDate = localDate.toString(),
        localHour = 12,
        category = CoarseNotificationCategory.OTHER,
        sourceToken = sourceToken,
        sourceColourRgb = 0x654321,
      ),
    )
  }
}
