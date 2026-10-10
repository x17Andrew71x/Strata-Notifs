package com.techfullymade.afterchime

import android.app.Application
import androidx.room.Room
import com.techfullymade.afterchime.capture.NotificationCaptureRuntime
import com.techfullymade.afterchime.capture.NotificationCaptureRuntimeRegistry
import com.techfullymade.afterchime.capture.NotificationReducer
import com.techfullymade.afterchime.capture.ObservationRepository
import com.techfullymade.afterchime.capture.ObservationRuntimeRegistry
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.LocalFormationRepository
import com.techfullymade.afterchime.domain.LocalMuseumRepository
import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.gameplay.FossilCatalogStore
import com.techfullymade.afterchime.gameplay.SharedPreferencesFossilCatalogPersistence
import com.techfullymade.afterchime.generation.GenerationResult
import com.techfullymade.afterchime.sealing.RoomSealDayStore
import com.techfullymade.afterchime.sealing.SealDayScheduler
import androidx.work.WorkManager
import com.techfullymade.afterchime.sealing.SealDayRuntimeRegistry
import com.techfullymade.afterchime.sealing.SealDayUseCase
import com.techfullymade.afterchime.security.AndroidKeystoreMaterial
import com.techfullymade.afterchime.security.LocalSecretException
import com.techfullymade.afterchime.security.LocalSecretStore
import com.techfullymade.afterchime.security.SharedPreferencesLocalSecretEnvelopeStore
import com.techfullymade.afterchime.settings.DataStoreUserPreferences
import java.time.Clock
import java.time.ZoneId
import org.json.JSONObject

class AfterchimeApplication : Application() {
  private val database: AfterchimeDatabase by lazy {
    Room.databaseBuilder(this, AfterchimeDatabase::class.java, DATABASE_NAME)
      .addMigrations(
        AfterchimeDatabase.MIGRATION_1_2,
        AfterchimeDatabase.MIGRATION_2_3,
        AfterchimeDatabase.MIGRATION_3_4,
        AfterchimeDatabase.MIGRATION_4_5,
        AfterchimeDatabase.MIGRATION_5_6,
        AfterchimeDatabase.MIGRATION_6_7,
      )
      .build()
  }
  private val localSecretStore: LocalSecretStore by lazy {
    LocalSecretStore(
      keyMaterial = AndroidKeystoreMaterial(),
      envelopeStore = SharedPreferencesLocalSecretEnvelopeStore(this),
    )
  }

  private val fossilCatalog: FossilCatalogStore by lazy {
    FossilCatalogStore(SharedPreferencesFossilCatalogPersistence(this))
  }

  internal val userPreferences: DataStoreUserPreferences by lazy { DataStoreUserPreferences(this) }
  internal val formationRepository: FormationRepository by lazy {
    LocalFormationRepository(
      database = database,
      selectDailyFossil = { localDate ->
        fossilCatalog.selectFor(localDate, localSecretStore.loadGeneratorSecret())
      },
      findFossil = fossilCatalog::find,
      catalogItemForSpecimenId = fossilCatalog::itemForSpecimenId,
    )
  }
  internal val museumRepository: MuseumRepository by lazy {
    LocalMuseumRepository(
      database = database,
      catalogItemForSpecimenId = fossilCatalog::itemForSpecimenId,
    )
  }

  internal fun updateFossilCatalog(payload: JSONObject): Boolean = fossilCatalog.apply(payload)

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
        generator = { _, _ -> GenerationResult.Unobserved },
      )
      installAfterchimeRuntime(
        ownPackageName = packageName,
        timeZone = timeZone,
        localSecretStore = activeLocalSecretStore,
        observationRepository = observationRepository,
        sealDayUseCase = sealDayUseCase,
        onInstalled = { SealDayScheduler(WorkManager.getInstance(this)).schedule() },
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
  onInstalled: () -> Unit = {},
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
  onInstalled()
  true
} catch (_: LocalSecretException) {
  // Preserve history and fail closed. A later explicit local-history reset owns replacement material.
  NotificationCaptureRuntimeRegistry.runtime = null
  SealDayRuntimeRegistry.useCase = null
  false
}
