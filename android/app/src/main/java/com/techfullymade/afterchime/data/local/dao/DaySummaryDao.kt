package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity

@Dao
interface DaySummaryDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(summary: DaySummaryEntity)

  @Query("SELECT * FROM day_summaries WHERE local_date = :localDate")
  suspend fun get(localDate: String): DaySummaryEntity?

  @Query("SELECT local_date FROM day_summaries WHERE local_date < :exclusiveLocalDate")
  suspend fun localDatesBefore(exclusiveLocalDate: String): List<String>
}