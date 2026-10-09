package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.techfullymade.afterchime.gameplay.GameBalance
import java.time.LocalDate

/** Durable, date-bound progress for one concealed daily excavation. */
@Entity(tableName = "daily_excavations")
data class DailyExcavationEntity(
  @PrimaryKey
  @ColumnInfo(name = "local_date")
  val localDate: String,
  @ColumnInfo(name = "artifact_id")
  val artifactId: String,
  @ColumnInfo(name = "dug_mask")
  val dugMask: Long,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
  @ColumnInfo(name = "completed_at_epoch_millis")
  val completedAtEpochMillis: Long?,
  @ColumnInfo(name = "specimen_id")
  val specimenId: String?,
) {
  init {
    LocalDate.parse(localDate)
    require(artifactId.matches(ARTIFACT_ID_PATTERN))
    require(dugMask >= 0)
    require(dugMask ushr GameBalance.EXCAVATION_TILE_COUNT == 0L)
    require(createdAtEpochMillis >= 0)
    require(completedAtEpochMillis == null || completedAtEpochMillis >= createdAtEpochMillis)
    require((completedAtEpochMillis == null) == (specimenId == null))
    require(specimenId == null || specimenId.matches(SPECIMEN_ID_PATTERN))
  }

  private companion object {
    val ARTIFACT_ID_PATTERN = Regex("[a-z0-9-]{1,64}")
    val SPECIMEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
  }
}
