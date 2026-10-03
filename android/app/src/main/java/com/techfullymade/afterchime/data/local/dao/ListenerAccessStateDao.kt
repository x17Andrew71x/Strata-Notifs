package com.techfullymade.afterchime.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenerAccessStateDao {
  @Upsert
  suspend fun upsert(state: ListenerAccessStateEntity)

  @Query("SELECT * FROM listener_access_states WHERE local_date = :localDate")
  suspend fun get(localDate: String): ListenerAccessStateEntity?

  @Query("SELECT * FROM listener_access_states WHERE local_date = :localDate")
  fun observe(localDate: String): Flow<ListenerAccessStateEntity?>

  @Query("SELECT COUNT(*) FROM listener_access_states WHERE local_date = :localDate")
  suspend fun countForLocalDate(localDate: String): Int

  @Query(
    """
      SELECT MIN(local_date) FROM listener_access_states
      WHERE local_date < :exclusiveLocalDate
    """,
  )
  suspend fun earliestLocalDateBefore(exclusiveLocalDate: String): String?

  @Query(
    """
      SELECT * FROM listener_access_states
      WHERE local_date <= :localDate
      ORDER BY local_date DESC
      LIMIT 1
    """,
  )
  suspend fun latestOnOrBefore(localDate: String): ListenerAccessStateEntity?
}
