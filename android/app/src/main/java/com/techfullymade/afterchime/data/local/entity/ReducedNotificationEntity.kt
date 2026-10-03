package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import java.time.LocalDate

@Entity(
  tableName = "reduced_notifications",
  indices = [Index(value = ["local_date", "occurred_at_epoch_millis"])],
)
data class ReducedNotificationEntity(
  @PrimaryKey
  val id: String,
  @ColumnInfo(name = "occurred_at_epoch_millis")
  val occurredAtEpochMillis: Long,
  @ColumnInfo(name = "local_date")
  val localDate: String,
  @ColumnInfo(name = "local_hour")
  val localHour: Int,
  val category: CoarseNotificationCategory,
  @ColumnInfo(name = "source_token")
  val sourceToken: String,
  @ColumnInfo(name = "source_colour_rgb")
  val sourceColourRgb: Int,
) {
  init {
    require(id.isNotBlank())
    require(occurredAtEpochMillis >= 0)
    LocalDate.parse(localDate)
    require(localHour in 0..23)
    require(sourceToken.matches(SOURCE_TOKEN_PATTERN))
    require(sourceColourRgb in 0..0xFFFFFF)
  }

  private companion object {
    val SOURCE_TOKEN_PATTERN = Regex("[0-9a-f]{64}")
  }
}