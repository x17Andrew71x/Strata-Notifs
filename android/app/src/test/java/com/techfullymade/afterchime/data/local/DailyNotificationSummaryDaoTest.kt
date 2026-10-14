package com.techfullymade.afterchime.data.local

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import com.techfullymade.afterchime.data.local.entity.DailyNotificationSummaryEntity
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
class DailyNotificationSummaryDaoTest {
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
  fun `version eight migration creates only the aggregate table shape`() {
    val sqlite = database.openHelper.writableDatabase
    sqlite.execSQL("DROP TABLE daily_notification_summaries")

    AfterchimeDatabase.MIGRATION_7_8.migrate(sqlite)

    val columns = sqlite.query(
      SimpleSQLiteQuery("PRAGMA table_info(daily_notification_summaries)"),
    ).use { cursor ->
      buildSet {
        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
      }
    }
    assertEquals(
      setOf(
        "local_date",
        "timezone_offset_minutes",
        "revision",
        "rules_version",
        "eligible_count",
        "category_counts_json",
        "hourly_buckets_json",
        "game_pressure",
        "observation_completeness",
        "created_at_epoch_millis",
        "uploaded_revision",
        "uploaded_at_epoch_millis",
      ),
      columns,
    )
  }

  @Test
  fun `round trip pending order exact revision mark and date inventory are bounded`() = runBlocking {
    val dao = database.dailyNotificationSummaryDao()
    val later = summary("2026-10-03", createdAtEpochMillis = 30L)
    val earlier = summary("2026-10-01", createdAtEpochMillis = 10L)
    dao.insert(later)
    dao.insert(earlier)

    assertEquals(earlier, dao.get(earlier.localDate))
    assertNull(dao.get("2026-09-30"))
    assertEquals(listOf(earlier, later), dao.pendingForUpload(limit = 100))
    assertEquals(emptyList<DailyNotificationSummaryEntity>(), dao.pendingForUpload(limit = 0))
    assertEquals(emptyList<DailyNotificationSummaryEntity>(), dao.pendingForUpload(limit = 101))
    assertEquals(
      listOf(earlier.localDate),
      dao.localDatesBefore(exclusiveLocalDate = later.localDate, limit = 100),
    )
    assertEquals(emptyList<String>(), dao.localDatesBefore(later.localDate, limit = 101))

    assertEquals(
      0,
      dao.markUploaded(
        localDate = earlier.localDate,
        revision = earlier.revision + 1,
        uploadedAtEpochMillis = 50L,
      ),
    )
    assertEquals(1, dao.markUploaded(earlier.localDate, earlier.revision, 50L))
    assertEquals(0, dao.markUploaded(earlier.localDate, earlier.revision, 60L))

    val uploaded = requireNotNull(dao.get(earlier.localDate))
    assertEquals(earlier.revision, uploaded.uploadedRevision)
    assertEquals(50L, uploaded.uploadedAtEpochMillis)
    assertEquals(listOf(later), dao.pendingForUpload(limit = 100))
  }

  private fun summary(
    localDate: String,
    createdAtEpochMillis: Long,
  ): DailyNotificationSummaryEntity = DailyNotificationSummaryEntity(
    localDate = localDate,
    timezoneOffsetMinutes = 0,
    revision = 1,
    rulesVersion = 1,
    eligibleCount = 1,
    categoryCountsJson = "{\"email\":1}",
    hourlyBucketsJson = "[{\"hour\":9,\"count\":1}]",
    gamePressure = 0,
    observationCompleteness = 100,
    createdAtEpochMillis = createdAtEpochMillis,
    uploadedRevision = null,
    uploadedAtEpochMillis = null,
  )
}
