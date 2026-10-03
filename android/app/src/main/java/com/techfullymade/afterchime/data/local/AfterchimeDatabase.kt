package com.techfullymade.afterchime.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.techfullymade.afterchime.data.local.dao.DaySummaryDao
import com.techfullymade.afterchime.data.local.dao.ListenerAccessStateDao
import com.techfullymade.afterchime.data.local.dao.ReducedNotificationDao
import com.techfullymade.afterchime.data.local.dao.SpecimenDao
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity

@Database(
  entities = [
    ReducedNotificationEntity::class,
    DaySummaryEntity::class,
    ListenerAccessStateEntity::class,
    SpecimenEntity::class,
  ],
  exportSchema = true,
  version = 2,
)
@TypeConverters(AfterchimeTypeConverters::class)
abstract class AfterchimeDatabase : RoomDatabase() {
  abstract fun reducedNotificationDao(): ReducedNotificationDao

  abstract fun daySummaryDao(): DaySummaryDao

  abstract fun listenerAccessStateDao(): ListenerAccessStateDao

  abstract fun specimenDao(): SpecimenDao

  companion object {
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `listener_access_states` (
            `local_date` TEXT NOT NULL,
            `active_at_epoch_millis` INTEGER,
            `disconnected_at_epoch_millis` INTEGER,
            `revoked_at_epoch_millis` INTEGER,
            `latest_state` TEXT NOT NULL,
            `updated_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`local_date`)
          )
          """.trimIndent(),
        )
      }
    }
  }
}