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
  fun versionOneExportedSchemaCreatesAndValidatesWithoutDestructiveFallback() {
    val name = "afterchime-v1-migration"

    migrationHelper.createDatabase(name, 1).close()
    migrationHelper.runMigrationsAndValidate(name, 1, true).close()
  }
}