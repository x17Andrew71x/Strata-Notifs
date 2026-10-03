package com.techfullymade.afterchime.data.local

import androidx.room.TypeConverter
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier

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

  @TypeConverter
  fun listenerAccessStateToDatabase(value: ListenerAccessState): String = value.name

  @TypeConverter
  fun listenerAccessStateFromDatabase(value: String): ListenerAccessState = ListenerAccessState.valueOf(value)

  @TypeConverter
  fun familyToDatabase(value: Family): String = value.name

  @TypeConverter
  fun familyFromDatabase(value: String): Family = Family.valueOf(value)

  @TypeConverter
  fun tierToDatabase(value: Tier): String = value.name

  @TypeConverter
  fun tierFromDatabase(value: String): Tier = Tier.valueOf(value)
}