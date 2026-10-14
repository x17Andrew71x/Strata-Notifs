package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Privacy-safe, server-ready notification totals for one sealed local day. */
@Entity(tableName = "daily_notification_summaries")
data class DailyNotificationSummaryEntity(
  @PrimaryKey
  @ColumnInfo(name = "local_date")
  val localDate: String,
  @ColumnInfo(name = "timezone_offset_minutes")
  val timezoneOffsetMinutes: Int,
  val revision: Int,
  @ColumnInfo(name = "rules_version")
  val rulesVersion: Int,
  @ColumnInfo(name = "eligible_count")
  val eligibleCount: Int,
  @ColumnInfo(name = "category_counts_json")
  val categoryCountsJson: String,
  @ColumnInfo(name = "hourly_buckets_json")
  val hourlyBucketsJson: String,
  @ColumnInfo(name = "game_pressure")
  val gamePressure: Int,
  @ColumnInfo(name = "observation_completeness")
  val observationCompleteness: Int,
  @ColumnInfo(name = "created_at_epoch_millis")
  val createdAtEpochMillis: Long,
  @ColumnInfo(name = "uploaded_revision")
  val uploadedRevision: Int?,
  @ColumnInfo(name = "uploaded_at_epoch_millis")
  val uploadedAtEpochMillis: Long?,
) {
  init {
    LocalDate.parse(localDate)
    require(timezoneOffsetMinutes in -MAX_TIMEZONE_OFFSET_MINUTES..MAX_TIMEZONE_OFFSET_MINUTES)
    require(revision > 0)
    require(rulesVersion > 0)
    require(eligibleCount >= 0)
    require(categoryCountsJson.length in 2..MAX_CATEGORY_COUNTS_JSON_LENGTH)
    require(categoryCountsJson.first() == '{' && categoryCountsJson.last() == '}')
    require(hourlyBucketsJson.length in 2..MAX_HOURLY_BUCKETS_JSON_LENGTH)
    require(hourlyBucketsJson.first() == '[' && hourlyBucketsJson.last() == ']')
    require(gamePressure in 0..MAX_PERCENT)
    require(observationCompleteness in 0..MAX_PERCENT)
    require(createdAtEpochMillis >= 0)
    require((uploadedRevision == null) == (uploadedAtEpochMillis == null))
    require(uploadedRevision == null || uploadedRevision in 1..revision)
    require(uploadedAtEpochMillis == null || uploadedAtEpochMillis >= 0)
  }

  private companion object {
    const val MAX_TIMEZONE_OFFSET_MINUTES = 14 * 60
    const val MAX_PERCENT = 100
    const val MAX_CATEGORY_COUNTS_JSON_LENGTH = 4_096
    const val MAX_HOURLY_BUCKETS_JSON_LENGTH = 4_096
  }
}
