package com.techfullymade.afterchime.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analytics_outbox")
data class AnalyticsOutboxEntity(
  @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
  @ColumnInfo(name = "ciphertext") val ciphertext: ByteArray,
  @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
)
