package com.techfullymade.afterchime

import android.app.Application
import androidx.room.Room
import com.techfullymade.afterchime.capture.NotificationCaptureRuntime
import com.techfullymade.afterchime.capture.NotificationCaptureRuntimeRegistry
import com.techfullymade.afterchime.capture.NotificationReducer
import com.techfullymade.afterchime.capture.ObservationRepository
import com.techfullymade.afterchime.capture.ObservationRuntimeRegistry
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.domain.LocalMuseumRepository
import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.sealing.RoomSealDayStore
import com.techfullymade.afterchime.sealing.SealDayRuntimeRegistry
import com.techfullymade.afterchime.sealing.SealDayUseCase
import com.techfullymade.afterchime.security.AndroidKeystoreMaterial
import com.techfullymade.afterchime.security.LocalSecretException
import com.techfullymade.afterchime.security.LocalSecretStore
import com.techfullymade.afterchime.security.SharedPreferencesLocalSecretEnvelopeStore
import com.techfullymade.afterchime.settings.DataStoreUserPreferences
import java.time.Clock
import java.time.ZoneId

class AfterchimeApplication : Application() {
  private val database: AfterchimeDatabase by lazy {
    Room.databaseBuilder(this, AfterchimeDatabase::class.java, DATABASE_NAME)
      .addMigrations(AfterchimeDatabase.MIGRATION_1_2, AfterchimeDatabase.MIGRATION_2_3)
      .build()
  }
  private val localSecretStore: LocalSecretStore by lazy {
    LocalSecretStore(
      keyMaterial = AndroidKeystoreMaterial(),
      envelopeStore = SharedPreferencesLocalSecretEnvelopeStore(this),
    )
  }

  internal val userPreferences: DataStoreUserPreferences by lazy { DataStoreUserPreferences(this) }
  internal val museumRepository: MuseumRepository by lazy { LocalMuseumRepository(database) }

  override fun onCreate() {
    super.onCreate()
    val timeZone = ZoneId.systemDefault()
    val observationRepository = ObservationRepository(
      listenerAccessStateDao = database.listenerAccessStateDao(),
      reducedNotificationDao = database.reducedNotificationDao(),
      timeZone = timeZone,
    )
    ObservationRuntimeRegistry.repository = observationRepository
    try {
      val activeLocalSecretStore = localSecretStore
      val sealDayUseCase = SealDayUseCase(
        store = RoomSealDayStore(database),
        localSecretProvider = activeLocalSecretStore::loadGeneratorSecret,
        clock = Clock.systemUTC(),
        timeZone = timeZone,
      )
      installAfterchimeRuntime(
        ownPackageName = packageName,
        timeZone = timeZone,
        localSecretStore = activeLocalSecretStore,
        observationRepository = observationRepository,
        sealDayUseCase = sealDayUseCase,
      )
    } catch (_: LocalSecretException) {
      NotificationCaptureRuntimeRegistry.runtime = null
      SealDayRuntimeRegistry.useCase = null
    }
  }

  override fun onTerminate() {
    NotificationCaptureRuntimeRegistry.runtime = null
    ObservationRuntimeRegistry.repository = null
    SealDayRuntimeRegistry.useCase = null
    database.close()
    super.onTerminate()
  }

  private companion object {
    const val DATABASE_NAME = "afterchime.db"
  }
}

/** Installs capture and sealing only after both protected local identity materials are available. */
internal fun installAfterchimeRuntime(
  ownPackageName: String,
  timeZone: ZoneId,
  localSecretStore: LocalSecretStore,
  observationRepository: ObservationRepository,
  sealDayUseCase: SealDayUseCase,
): Boolean = try {
  localSecretStore.ensureAvailable()
  NotificationCaptureRuntimeRegistry.runtime = NotificationCaptureRuntime(
    reducer = NotificationReducer(
      ownPackageName = ownPackageName,
      sourceHmacKey = localSecretStore.sourceHmacKey(),
      timeZone = timeZone,
    ),
    observationRepository = observationRepository,
  )
  SealDayRuntimeRegistry.useCase = sealDayUseCase
  true
} catch (_: LocalSecretException) {
  // Preserve history and fail closed. A later explicit local-history reset owns replacement material.
  NotificationCaptureRuntimeRegistry.runtime = null
  SealDayRuntimeRegistry.useCase = null
  false
}
