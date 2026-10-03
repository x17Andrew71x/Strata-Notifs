package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReducedNotificationDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(notification: ReducedNotificationEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(notification: ReducedNotificationEntity): Long

  @Query(
    """
      SELECT * FROM reduced_notifications
      WHERE local_date = :localDate
      ORDER BY occurred_at_epoch_millis ASC, id ASC
    """,
  )
  suspend fun forLocalDate(localDate: String): List<ReducedNotificationEntity>

  @Query(
    """
      SELECT * FROM reduced_notifications
      WHERE local_date = :localDate
      ORDER BY occurred_at_epoch_millis ASC, id ASC
    """,
  )
  fun observeForLocalDate(localDate: String): Flow<List<ReducedNotificationEntity>>

  @Query(
    """
      SELECT MIN(local_date) FROM reduced_notifications
      WHERE local_date < :exclusiveLocalDate
    """,
  )
  suspend fun earliestLocalDateBefore(exclusiveLocalDate: String): String?
}