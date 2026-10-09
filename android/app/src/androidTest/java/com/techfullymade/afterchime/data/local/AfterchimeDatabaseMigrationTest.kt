package com.techfullymade.afterchime.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AfterchimeDatabaseMigrationTest {
  @get:Rule
  val migrationHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    AfterchimeDatabase::class.java,
  )

  @Test
  fun versionOneExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v1-to-v7-migration"
    migrationHelper.createDatabase(name, 1).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      7,
      true,
      AfterchimeDatabase.MIGRATION_1_2,
      AfterchimeDatabase.MIGRATION_2_3,
      AfterchimeDatabase.MIGRATION_3_4,
      AfterchimeDatabase.MIGRATION_4_5,
      AfterchimeDatabase.MIGRATION_5_6,
      AfterchimeDatabase.MIGRATION_6_7,
    ).close()
  }

  @Test
  fun versionTwoExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v2-to-v7-migration"
    migrationHelper.createDatabase(name, 2).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      7,
      true,
      AfterchimeDatabase.MIGRATION_2_3,
      AfterchimeDatabase.MIGRATION_3_4,
      AfterchimeDatabase.MIGRATION_4_5,
      AfterchimeDatabase.MIGRATION_5_6,
      AfterchimeDatabase.MIGRATION_6_7,
    ).close()
  }

  @Test
  fun versionThreeExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v3-to-v7-migration"
    migrationHelper.createDatabase(name, 3).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      7,
      true,
      AfterchimeDatabase.MIGRATION_3_4,
      AfterchimeDatabase.MIGRATION_4_5,
      AfterchimeDatabase.MIGRATION_5_6,
      AfterchimeDatabase.MIGRATION_6_7,
    ).close()
  }

  @Test
  fun versionFourExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v4-to-v7-migration"
    migrationHelper.createDatabase(name, 4).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      7,
      true,
      AfterchimeDatabase.MIGRATION_4_5,
      AfterchimeDatabase.MIGRATION_5_6,
      AfterchimeDatabase.MIGRATION_6_7,
    ).close()
  }

  @Test
  fun versionFiveExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v5-to-v7-migration"
    migrationHelper.createDatabase(name, 5).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      7,
      true,
      AfterchimeDatabase.MIGRATION_5_6,
      AfterchimeDatabase.MIGRATION_6_7,
    ).close()
  }

  @Test
  fun versionSixExportedSchemaMigratesToVersionSevenWithoutDestructiveFallback() {
    val name = "afterchime-v6-to-v7-migration"
    migrationHelper.createDatabase(name, 6).close()
    migrationHelper.runMigrationsAndValidate(name, 7, true, AfterchimeDatabase.MIGRATION_6_7).close()
  }
}
