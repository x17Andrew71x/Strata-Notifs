package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity

@Dao
interface ReducedNotificationDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(notification: ReducedNotificationEntity)

  @Query(
    """
      SELECT * FROM reduced_notifications
      WHERE local_date = :localDate
      ORDER BY occurred_at_epoch_millis ASC, id ASC
    """,
  )
  suspend fun forLocalDate(localDate: String): List<ReducedNotificationEntity>
}