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
import com.techfullymade.afterchime.data.local.dao.SpecimenOutputDao
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessStateEntity
import com.techfullymade.afterchime.data.local.entity.ReducedNotificationEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity

@Database(
  entities = [
    ReducedNotificationEntity::class,
    DaySummaryEntity::class,
    ListenerAccessStateEntity::class,
    SpecimenEntity::class,
    SpecimenOutputEntity::class,
  ],
  exportSchema = true,
  version = 4,
)
@TypeConverters(AfterchimeTypeConverters::class)
abstract class AfterchimeDatabase : RoomDatabase() {
  abstract fun reducedNotificationDao(): ReducedNotificationDao

  abstract fun daySummaryDao(): DaySummaryDao

  abstract fun listenerAccessStateDao(): ListenerAccessStateDao

  abstract fun specimenDao(): SpecimenDao

  abstract fun specimenOutputDao(): SpecimenOutputDao

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

    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `specimen_outputs` (
            `specimen_id` TEXT NOT NULL,
            `family` TEXT NOT NULL,
            `tier` TEXT NOT NULL,
            `hue_degrees` INTEGER NOT NULL,
            `strata_count` INTEGER NOT NULL,
            `inclusion_density_percent` INTEGER NOT NULL,
            `relief_percent` INTEGER NOT NULL,
            `rotation_degrees` INTEGER NOT NULL,
            PRIMARY KEY(`specimen_id`),
            FOREIGN KEY(`specimen_id`) REFERENCES `specimens`(`specimen_id`) ON UPDATE NO ACTION ON DELETE CASCADE
          )
          """.trimIndent(),
        )
      }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `specimens` ADD COLUMN `is_locked` INTEGER NOT NULL DEFAULT 0")
      }
    }
  }
}