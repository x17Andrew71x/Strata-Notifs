package com.techfullymade.afterchime

import android.app.Application
import androidx.room.Room
import com.techfullymade.afterchime.capture.ObservationRepository
import com.techfullymade.afterchime.capture.ObservationRuntimeRegistry
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import java.time.ZoneId

class AfterchimeApplication : Application() {
  private val database: AfterchimeDatabase by lazy {
    Room.databaseBuilder(this, AfterchimeDatabase::class.java, DATABASE_NAME)
      .addMigrations(AfterchimeDatabase.MIGRATION_1_2)
      .build()
  }

  override fun onCreate() {
    super.onCreate()
    ObservationRuntimeRegistry.repository = ObservationRepository(
      listenerAccessStateDao = database.listenerAccessStateDao(),
      reducedNotificationDao = database.reducedNotificationDao(),
      timeZone = ZoneId.systemDefault(),
    )
  }

  override fun onTerminate() {
    ObservationRuntimeRegistry.repository = null
    database.close()
    super.onTerminate()
  }

  private companion object {
    const val DATABASE_NAME = "afterchime.db"
  }
}
