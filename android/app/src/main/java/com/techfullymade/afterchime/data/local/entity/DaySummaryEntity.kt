package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class ObservationState {
  OBSERVED,
  UNOBSERVED,
}

@Entity(tableName = "day_summaries")
data class DaySummaryEntity(
  @PrimaryKey
  @ColumnInfo(name = "local_date")
  val localDate: String,
  @ColumnInfo(name = "timezone_offset_minutes")
  val timezoneOffsetMinutes: Int,
  @ColumnInfo(name = "observation_state")
  val observationState: ObservationState,
  @ColumnInfo(name = "generator_version")
  val generatorVersion: Int,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
) {
  init {
    LocalDate.parse(localDate)
    require(timezoneOffsetMinutes in -840..840)
    require(generatorVersion > 0)
    require(createdAtEpochMillis >= 0)
  }
}