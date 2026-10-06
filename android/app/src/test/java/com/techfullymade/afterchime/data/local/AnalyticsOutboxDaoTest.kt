package com.techfullymade.afterchime.data.local

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsOutboxDaoTest {
  private lateinit var database: AfterchimeDatabase

  @Before fun setUp() {
    database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AfterchimeDatabase::class.java)
      .allowMainThreadQueries().build()
  }

  @After fun tearDown() { database.close() }

  @Test fun `migration five to six preserves data and creates only the encrypted outbox columns`() {
    val sqlite = database.openHelper.writableDatabase
    sqlite.execSQL(
      "INSERT INTO day_summaries " +
        "(local_date, timezone_offset_minutes, observation_state, generator_version, created_at_epoch_millis) " +
        "VALUES ('2026-10-06', 0, 'OBSERVED', 1, 123)",
    )
    sqlite.execSQL("DROP TABLE analytics_outbox")

    AfterchimeDatabase.MIGRATION_5_6.migrate(sqlite)

    val preservedRows = sqlite.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM day_summaries")).use { cursor ->
      cursor.moveToFirst()
      cursor.getInt(0)
    }
    val columns = sqlite.query(SimpleSQLiteQuery("PRAGMA table_info(analytics_outbox)")).use { cursor ->
      buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }
    assertEquals(1, preservedRows)
    assertEquals(setOf("event_id", "ciphertext", "created_at_epoch_millis"), columns)
  }

  @Test fun `duplicate event id is ignored and table contains only encrypted outbox columns`() = runBlocking {
    val dao = database.analyticsOutboxDao()
    val row = AnalyticsOutboxEntity(ID, byteArrayOf(4, 3, 2, 1), 123L)
    dao.insertIfAbsent(row)
    dao.insertIfAbsent(row.copy(ciphertext = byteArrayOf(8, 8, 8)))
    assertEquals(1, dao.oldest(10).size)
    assertEquals(row.eventId, dao.oldest(10).single().eventId)
    assertEquals(row.ciphertext.toList(), dao.oldest(10).single().ciphertext.toList())
    val columns = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("PRAGMA table_info(analytics_outbox)")).use { cursor ->
      buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
    }
    assertEquals(setOf("event_id", "ciphertext", "created_at_epoch_millis"), columns)
    val stored = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT ciphertext FROM analytics_outbox")).use { cursor ->
      cursor.moveToFirst(); cursor.getBlob(0)
    }
    assertFalse(String(stored).contains("event_name"))
    assertEquals(1, dao.delete(ID))
    assertEquals(0, dao.count(ID))
  }

  private companion object { const val ID = "123e4567-e89b-42d3-a456-426614174000" }
}
