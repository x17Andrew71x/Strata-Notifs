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
  fun versionOneExportedSchemaMigratesToVersionFourWithoutDestructiveFallback() {
    val name = "afterchime-v1-to-v4-migration"

    migrationHelper.createDatabase(name, 1).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      4,
      true,
      AfterchimeDatabase.MIGRATION_1_2,
      AfterchimeDatabase.MIGRATION_2_3,
      AfterchimeDatabase.MIGRATION_3_4,
    ).close()
  }

  @Test
  fun versionTwoExportedSchemaMigratesToVersionFourWithoutDestructiveFallback() {
    val name = "afterchime-v2-to-v4-migration"

    migrationHelper.createDatabase(name, 2).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      4,
      true,
      AfterchimeDatabase.MIGRATION_2_3,
      AfterchimeDatabase.MIGRATION_3_4,
    ).close()
  }

  @Test
  fun versionThreeExportedSchemaMigratesToVersionFourWithoutDestructiveFallback() {
    val name = "afterchime-v3-to-v4-migration"

    migrationHelper.createDatabase(name, 3).close()
    migrationHelper.runMigrationsAndValidate(name, 4, true, AfterchimeDatabase.MIGRATION_3_4).close()
  }
}