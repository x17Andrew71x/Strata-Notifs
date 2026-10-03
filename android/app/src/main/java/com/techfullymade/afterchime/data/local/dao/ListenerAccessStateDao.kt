package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity

@Dao
interface ListenerAccessStateDao {
  @Upsert
  suspend fun upsert(state: ListenerAccessStateEntity)

  @Query("SELECT * FROM listener_access_states WHERE local_date = :localDate")
  suspend fun get(localDate: String): ListenerAccessStateEntity?

  @Query("SELECT COUNT(*) FROM listener_access_states WHERE local_date = :localDate")
  suspend fun countForLocalDate(localDate: String): Int
}
