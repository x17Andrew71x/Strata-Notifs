package com.techfullymade.afterchime.data.local

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.techfullymade.afterchime.data.local.entity.DailyExcavationEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DailyExcavationDaoTest {
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
  fun `version seven migration creates only the durable excavation table`() {
    val sqlite = database.openHelper.writableDatabase
    sqlite.execSQL("DROP TABLE daily_excavations")

    AfterchimeDatabase.MIGRATION_6_7.migrate(sqlite)

    val columns = sqlite.query(SimpleSQLiteQuery("PRAGMA table_info(daily_excavations)")).use { cursor ->
      buildSet {
        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
      }
    }
    assertEquals(
      setOf(
        "local_date",
        "artifact_id",
        "dug_mask",
        "created_at_epoch_millis",
        "completed_at_epoch_millis",
        "specimen_id",
      ),
      columns,
    )
  }

  @Test
  fun `daily excavation progress round trips by local date`() = runBlocking {
    val localDate = LocalDate.parse("2026-10-09")
    val entity = DailyExcavationEntity(
      localDate = localDate.toString(),
      artifactId = "relic-domal-stromatolite",
      dugMask = 1L shl 7,
      createdAtEpochMillis = 10L,
      completedAtEpochMillis = null,
      specimenId = null,
    )

    assertEquals(1L, database.dailyExcavationDao().insertIfAbsent(entity))
    assertEquals(entity, database.dailyExcavationDao().get(localDate.toString()))
    assertEquals(entity, database.dailyExcavationDao().observe(localDate.toString()).first())
  }
}
