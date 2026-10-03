package com.techfullymade.afterchime.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class AfterchimeDatabaseTest {
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
  fun `persists only reduced notification day summary and specimen fields`() = runBlocking {
    val notification = reducedNotification(id = "event-1")
    val summary = daySummary()
    val specimen = specimen()

    database.reducedNotificationDao().insert(notification)
    database.daySummaryDao().insert(summary)
    database.specimenDao().insert(specimen)

    assertEquals(listOf(notification), database.reducedNotificationDao().forLocalDate(summary.localDate))
    assertEquals(summary, database.daySummaryDao().get(summary.localDate))
    assertEquals(specimen, database.specimenDao().getByAnchoredLocalDate(summary.localDate))
    assertEquals(
      setOf(
        "category",
        "id",
        "local_date",
        "local_hour",
        "occurred_at_epoch_millis",
        "source_colour_rgb",
        "source_token",
      ),
      database.columnsFor("reduced_notifications"),
    )
    assertEquals(
      setOf(
        "created_at_epoch_millis",
        "generator_version",
        "local_date",
        "observation_state",
        "timezone_offset_minutes",
      ),
      database.columnsFor("day_summaries"),
    )
    assertEquals(
      setOf(
        "anchored_local_date",
        "created_at_epoch_millis",
        "generator_version",
        "revealed_at_epoch_millis",
        "specimen_id",
      ),
      database.columnsFor("specimens"),
    )
  }

  @Test
  fun `unique day and specimen keys reject duplicate sealed records`() = runBlocking {
    val summary = daySummary()
    val specimen = specimen()
    database.daySummaryDao().insert(summary)
    database.specimenDao().insert(specimen)

    assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
      runBlocking {
        database.daySummaryDao().insert(summary.copy(generatorVersion = 2))
      }
    }
    assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
      runBlocking {
        database.specimenDao().insert(specimen.copy(specimenId = "specimen-duplicate"))
      }
    }
    Unit
  }

  @Test
  fun `failed sealed day transaction rolls back its summary and specimen`() {
    val summary = daySummary()
    val specimen = specimen()

    assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
      runBlocking {
        database.withTransaction {
          database.daySummaryDao().insert(summary)
          database.specimenDao().insert(specimen)
          database.specimenDao().insert(specimen(specimenId = "specimen-duplicate"))
        }
      }
    }

    runBlocking {
      assertNull(database.daySummaryDao().get(summary.localDate))
      assertNull(database.specimenDao().getByAnchoredLocalDate(summary.localDate))
    }
  }

  private fun reducedNotification(id: String) = ReducedNotificationEntity(
    id = id,
    occurredAtEpochMillis = 1_759_507_500_000L,
    localDate = "2026-10-03",
    localHour = 9,
    category = CoarseNotificationCategory.EMAIL,
    sourceToken = "a".repeat(64),
    sourceColourRgb = 0x123456,
  )

  private fun daySummary() = DaySummaryEntity(
    localDate = "2026-10-03",
    timezoneOffsetMinutes = 0,
    observationState = ObservationState.OBSERVED,
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_593_600_000L,
  )

  private fun specimen(specimenId: String = "specimen-1") = SpecimenEntity(
    specimenId = specimenId,
    anchoredLocalDate = "2026-10-03",
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_593_600_000L,
    revealedAtEpochMillis = null,
  )

  private fun AfterchimeDatabase.columnsFor(table: String): Set<String> =
    openHelper.readableDatabase.query(SimpleSQLiteQuery("PRAGMA table_info($table)"))
      .use { cursor ->
        buildSet {
          val nameIndex = cursor.getColumnIndexOrThrow("name")
          while (cursor.moveToNext()) {
            add(cursor.getString(nameIndex))
          }
        }
      }
}
