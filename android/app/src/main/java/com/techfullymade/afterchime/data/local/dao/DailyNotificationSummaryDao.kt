package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.DailyNotificationSummaryEntity

@Dao
interface DailyNotificationSummaryDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(summary: DailyNotificationSummaryEntity)

  @Query("SELECT * FROM daily_notification_summaries WHERE local_date = :localDate")
  suspend fun get(localDate: String): DailyNotificationSummaryEntity?

  @Query(
    """
      SELECT * FROM daily_notification_summaries
      WHERE (uploaded_revision IS NULL OR uploaded_revision != revision)
        AND :limit BETWEEN 1 AND 100
      ORDER BY local_date ASC
      LIMIT :limit
    """,
  )
  suspend fun pendingForUpload(limit: Int): List<DailyNotificationSummaryEntity>

  @Query(
    """
      UPDATE daily_notification_summaries
      SET uploaded_revision = :revision, uploaded_at_epoch_millis = :uploadedAtEpochMillis
      WHERE local_date = :localDate
        AND revision = :revision
        AND (uploaded_revision IS NULL OR uploaded_revision != :revision)
        AND :uploadedAtEpochMillis >= 0
    """,
  )
  suspend fun markUploaded(
    localDate: String,
    revision: Int,
    uploadedAtEpochMillis: Long,
  ): Int

  @Query(
    """
      SELECT local_date FROM daily_notification_summaries
      WHERE local_date < :exclusiveLocalDate
        AND :limit BETWEEN 1 AND 100
      ORDER BY local_date ASC
      LIMIT :limit
    """,
  )
  suspend fun localDatesBefore(exclusiveLocalDate: String, limit: Int): List<String>
}
