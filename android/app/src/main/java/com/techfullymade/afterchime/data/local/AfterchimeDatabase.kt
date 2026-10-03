package com.techfullymade.afterchime.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.techfullymade.afterchime.data.local.dao.DaySummaryDao
import com.techfullymade.afterchime.data.local.dao.ReducedNotificationDao
import com.techfullymade.afterchime.data.local.dao.SpecimenDao
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity

@Database(
  entities = [
    ReducedNotificationEntity::class,
    DaySummaryEntity::class,
    SpecimenEntity::class,
  ],
  exportSchema = true,
  version = 1,
)
@TypeConverters(AfterchimeTypeConverters::class)
abstract class AfterchimeDatabase : RoomDatabase() {
  abstract fun reducedNotificationDao(): ReducedNotificationDao

  abstract fun daySummaryDao(): DaySummaryDao

  abstract fun specimenDao(): SpecimenDao
}