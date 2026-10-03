package com.techfullymade.afterchime.data.local

import androidx.room.TypeConverter
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.entity.ObservationState

class AfterchimeTypeConverters {
  @TypeConverter
  fun categoryToDatabase(value: CoarseNotificationCategory): String = value.name

  @TypeConverter
  fun categoryFromDatabase(value: String): CoarseNotificationCategory =
    CoarseNotificationCategory.valueOf(value)

  @TypeConverter
  fun observationStateToDatabase(value: ObservationState): String = value.name

  @TypeConverter
  fun observationStateFromDatabase(value: String): ObservationState = ObservationState.valueOf(value)
}