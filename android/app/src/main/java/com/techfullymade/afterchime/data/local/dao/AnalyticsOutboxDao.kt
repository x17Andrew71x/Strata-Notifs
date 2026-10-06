package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.AnalyticsOutboxEntity

@Dao
interface AnalyticsOutboxDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(entity: AnalyticsOutboxEntity): Long

  @Query("SELECT * FROM analytics_outbox ORDER BY created_at_epoch_millis, event_id LIMIT :limit")
  suspend fun oldest(limit: Int): List<AnalyticsOutboxEntity>

  @Query("DELETE FROM analytics_outbox WHERE event_id = :eventId")
  suspend fun delete(eventId: String): Int

  @Query("SELECT COUNT(*) FROM analytics_outbox WHERE event_id = :eventId")
  suspend fun count(eventId: String): Int
}
