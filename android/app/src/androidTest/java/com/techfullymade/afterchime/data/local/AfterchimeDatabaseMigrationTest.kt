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
  fun versionOneExportedSchemaMigratesToVersionThreeWithoutDestructiveFallback() {
    val name = "afterchime-v1-to-v3-migration"

    migrationHelper.createDatabase(name, 1).close()
    migrationHelper.runMigrationsAndValidate(
      name,
      3,
      true,
      AfterchimeDatabase.MIGRATION_1_2,
      AfterchimeDatabase.MIGRATION_2_3,
    ).close()
  }

  @Test
  fun versionTwoExportedSchemaMigratesToVersionThreeWithoutDestructiveFallback() {
    val name = "afterchime-v2-to-v3-migration"

    migrationHelper.createDatabase(name, 2).close()
    migrationHelper.runMigrationsAndValidate(name, 3, true, AfterchimeDatabase.MIGRATION_2_3).close()
  }
}