package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.techfullymade.afterchime.data.local.entity.DailyExcavationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyExcavationDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(excavation: DailyExcavationEntity): Long

  @Update
  suspend fun update(excavation: DailyExcavationEntity): Int

  @Query("SELECT * FROM daily_excavations WHERE local_date = :localDate")
  suspend fun get(localDate: String): DailyExcavationEntity?

  @Query("SELECT * FROM daily_excavations WHERE local_date = :localDate")
  fun observe(localDate: String): Flow<DailyExcavationEntity?>
}
