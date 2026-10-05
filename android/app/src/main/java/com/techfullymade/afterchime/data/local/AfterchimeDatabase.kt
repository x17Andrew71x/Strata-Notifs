package com.techfullymade.afterchime.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.techfullymade.afterchime.data.local.dao.DaySummaryDao
import com.techfullymade.afterchime.data.local.dao.InventoryItemDao
import com.techfullymade.afterchime.data.local.dao.InventoryMutationDao
import com.techfullymade.afterchime.data.local.dao.ListenerAccessStateDao
import com.techfullymade.afterchime.data.local.dao.ReducedNotificationDao
import com.techfullymade.afterchime.data.local.dao.SpecimenDao
import com.techfullymade.afterchime.data.local.dao.SpecimenOutputDao
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.InventoryItemEntity
import com.techfullymade.afterchime.data.local.entity.InventoryMutationEntity
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
    InventoryItemEntity::class,
    InventoryMutationEntity::class,
  ],
  exportSchema = true,
  version = 5,
)
@TypeConverters(AfterchimeTypeConverters::class)
abstract class AfterchimeDatabase : RoomDatabase() {
  abstract fun reducedNotificationDao(): ReducedNotificationDao

  abstract fun daySummaryDao(): DaySummaryDao

  abstract fun listenerAccessStateDao(): ListenerAccessStateDao

  abstract fun specimenDao(): SpecimenDao

  abstract fun specimenOutputDao(): SpecimenOutputDao

  abstract fun inventoryItemDao(): InventoryItemDao

  abstract fun inventoryMutationDao(): InventoryMutationDao

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

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `inventory_items` (
            `item_id` TEXT NOT NULL,
            `source_specimen_id` TEXT,
            `anchored_local_date` TEXT,
            `generator_version` INTEGER NOT NULL,
            `created_at_epoch_millis` INTEGER NOT NULL,
            `revealed_at_epoch_millis` INTEGER,
            `is_locked` INTEGER NOT NULL DEFAULT 0,
            `collectible_state` TEXT NOT NULL,
            `provenance_count` INTEGER NOT NULL,
            `family` TEXT NOT NULL,
            `tier` TEXT NOT NULL,
            `hue_degrees` INTEGER NOT NULL,
            `strata_count` INTEGER NOT NULL,
            `inclusion_density_percent` INTEGER NOT NULL,
            `relief_percent` INTEGER NOT NULL,
            `rotation_degrees` INTEGER NOT NULL,
            `consumed_by_mutation_id` TEXT,
            PRIMARY KEY(`item_id`),
            FOREIGN KEY(`source_specimen_id`) REFERENCES `specimens`(`specimen_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_items_source_specimen_id` ON `inventory_items` (`source_specimen_id`)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_inventory_items_consumed_by_mutation_id` ON `inventory_items` (`consumed_by_mutation_id`)",
        )
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `inventory_mutations` (
            `mutation_id` TEXT NOT NULL,
            `output_item_id` TEXT NOT NULL,
            `first_input_item_id` TEXT NOT NULL,
            `second_input_item_id` TEXT NOT NULL,
            `third_input_item_id` TEXT NOT NULL,
            `created_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`mutation_id`)
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_mutations_output_item_id` ON `inventory_mutations` (`output_item_id`)",
        )
        db.execSQL(
          """
          INSERT INTO `inventory_items` (
            `item_id`, `source_specimen_id`, `anchored_local_date`, `generator_version`,
            `created_at_epoch_millis`, `revealed_at_epoch_millis`, `is_locked`,
            `collectible_state`, `provenance_count`, `family`, `tier`, `hue_degrees`,
            `strata_count`, `inclusion_density_percent`, `relief_percent`, `rotation_degrees`,
            `consumed_by_mutation_id`
          )
          SELECT
            specimens.`specimen_id`, specimens.`specimen_id`, specimens.`anchored_local_date`,
            specimens.`generator_version`, specimens.`created_at_epoch_millis`,
            specimens.`revealed_at_epoch_millis`, specimens.`is_locked`, 'ORDINARY', 1,
            specimen_outputs.`family`, specimen_outputs.`tier`, specimen_outputs.`hue_degrees`,
            specimen_outputs.`strata_count`, specimen_outputs.`inclusion_density_percent`,
            specimen_outputs.`relief_percent`, specimen_outputs.`rotation_degrees`, NULL
          FROM `specimens`
          INNER JOIN `specimen_outputs` ON specimen_outputs.`specimen_id` = specimens.`specimen_id`
          """.trimIndent(),
        )
      }
    }
  }
}