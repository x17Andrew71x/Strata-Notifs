package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
  tableName = "specimens",
  indices = [Index(value = ["anchored_local_date"], unique = true)],
)
data class SpecimenEntity(
  @PrimaryKey
  @ColumnInfo(name = "specimen_id")
  val specimenId: String,
  @ColumnInfo(name = "anchored_local_date")
  val anchoredLocalDate: String,
  @ColumnInfo(name = "generator_version")
  val generatorVersion: Int,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
  @ColumnInfo(name = "revealed_at_epoch_millis")
  val revealedAtEpochMillis: Long?,
  @ColumnInfo(name = "is_locked", defaultValue = "0")
  val isLocked: Boolean = false,
) {
  init {
    require(specimenId.isNotBlank())
    LocalDate.parse(anchoredLocalDate)
    require(generatorVersion > 0)
    require(createdAtEpochMillis >= 0)
    require(revealedAtEpochMillis == null || revealedAtEpochMillis >= createdAtEpochMillis)
  }
}