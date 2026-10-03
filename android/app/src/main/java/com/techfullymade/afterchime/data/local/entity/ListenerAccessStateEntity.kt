package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ListenerAccessState {
  ACTIVE,
  DISCONNECTED,
  REVOKED,
}

@Entity(tableName = "listener_access_states")
data class ListenerAccessStateEntity(
  @PrimaryKey
  @ColumnInfo(name = "local_date")
  val localDate: String,
  @ColumnInfo(name = "active_at_epoch_millis")
  val activeAtEpochMillis: Long?,
  @ColumnInfo(name = "disconnected_at_epoch_millis")
  val disconnectedAtEpochMillis: Long?,
  @ColumnInfo(name = "revoked_at_epoch_millis")
  val revokedAtEpochMillis: Long?,
  @ColumnInfo(name = "latest_state")
  val latestState: ListenerAccessState,
  @ColumnInfo(name = "updated_at_epoch_millis")
  val updatedAtEpochMillis: Long,
) {
  init {
    LocalDate.parse(localDate)
    require(activeAtEpochMillis == null || activeAtEpochMillis >= 0)
    require(disconnectedAtEpochMillis == null || disconnectedAtEpochMillis >= 0)
    require(revokedAtEpochMillis == null || revokedAtEpochMillis >= 0)
    require(updatedAtEpochMillis >= 0)
  }
}
