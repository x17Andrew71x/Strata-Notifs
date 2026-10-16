package com.techfullymade.afterchime

import android.app.Application
import android.os.Build
import androidx.room.Room
import androidx.work.WorkManager
import com.techfullymade.afterchime.analytics.AndroidAnalyticsEnvelopeCipher
import com.techfullymade.afterchime.analytics.AndroidAnalyticsContextFactory
import com.techfullymade.afterchime.analytics.AnalyticsEvent
import com.techfullymade.afterchime.analytics.AnalyticsIdentityReader
import com.techfullymade.afterchime.analytics.AnalyticsOutboxWriter
import com.techfullymade.afterchime.analytics.AnalyticsRecordResult
import com.techfullymade.afterchime.analytics.AnalyticsRecorder
import com.techfullymade.afterchime.analytics.ProductUseReporter
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
import com.techfullymade.afterchime.identity.AfterchimeIdentityGateway
import com.techfullymade.afterchime.identity.AndroidCredentialCipher
import com.techfullymade.afterchime.identity.AtomicCredentialEnvelopeStorage
import com.techfullymade.afterchime.identity.CredentialStore
import com.techfullymade.afterchime.identity.IdentityResult
import com.techfullymade.afterchime.identity.OnlineIdentityRepository
import com.techfullymade.afterchime.identity.TokenAuthenticator
import com.techfullymade.afterchime.network.NetworkModule
import com.techfullymade.afterchime.network.RegistrationRequest
import com.techfullymade.afterchime.sealing.RoomSealDayStore
import com.techfullymade.afterchime.sealing.SealDayRuntimeRegistry
import com.techfullymade.afterchime.sealing.SealDayScheduler
import com.techfullymade.afterchime.sealing.SealDayUseCase
import com.techfullymade.afterchime.security.AndroidKeystoreMaterial
import com.techfullymade.afterchime.security.LocalSecretException
import com.techfullymade.afterchime.security.LocalSecretStore
import com.techfullymade.afterchime.security.SharedPreferencesLocalSecretEnvelopeStore
import com.techfullymade.afterchime.settings.DataStoreUserPreferences
import com.techfullymade.afterchime.sync.AfterchimeOnlineSyncApi
import com.techfullymade.afterchime.sync.AnalyticsOutboxUploader
import com.techfullymade.afterchime.sync.AuthenticatorOnlineTokenClient
import com.techfullymade.afterchime.sync.OnlineSyncCoordinator
import com.techfullymade.afterchime.sync.OnlineSyncRunner
import com.techfullymade.afterchime.sync.OnlineSyncRuntimeRegistry
import com.techfullymade.afterchime.sync.OnlineSyncScheduler
import com.techfullymade.afterchime.sync.RepositoryOnlineIdentityClient
import com.techfullymade.afterchime.sync.RoomDailySummaryUploadStore
import com.techfullymade.afterchime.sync.RoomAnalyticsUploadStore
import com.techfullymade.afterchime.sync.SharedPreferencesOnlineSyncStateStore
import java.time.Clock
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        AfterchimeDatabase.MIGRATION_7_8,
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
  private val analyticsCipher: AndroidAnalyticsEnvelopeCipher by lazy {
    AndroidAnalyticsEnvelopeCipher()
  }
  private val onlineApi by lazy {
    NetworkModule.create(BuildConfig.API_BASE_URL, BuildConfig.FLAVOR == "dev")
  }
  private val onlineIdentityRepository: OnlineIdentityRepository by lazy {
    OnlineIdentityRepository(
      gateway = AfterchimeIdentityGateway(onlineApi),
      credentials = CredentialStore(
        storage = AtomicCredentialEnvelopeStorage(this),
        cipher = AndroidCredentialCipher(),
      ),
    )
  }
  private val onlineTokenAuthenticator: TokenAuthenticator by lazy {
    TokenAuthenticator(onlineIdentityRepository, refreshSkewMillis = ONLINE_TOKEN_REFRESH_SKEW_MILLIS)
  }

  private val productUseReporter: ProductUseReporter by lazy {
    ProductUseReporter(
      preferences = { userPreferences.values.first() },
      identity = AnalyticsIdentityReader { onlineIdentityRepository.currentSession() },
      recorder = AnalyticsRecorder(
        writer = AnalyticsOutboxWriter(database.analyticsOutboxDao()::insertIfAbsent),
        cipher = analyticsCipher,
      ),
      contextFactory = AndroidAnalyticsContextFactory(
        clock = Clock.systemUTC(),
        timeZone = ZoneId.systemDefault(),
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildChannel = BuildConfig.FLAVOR,
        androidApiLevel = Build.VERSION.SDK_INT,
        deviceClass = onlineDeviceClass(resources.configuration.smallestScreenWidthDp),
        locale = {
          val locales = resources.configuration.locales
          if (locales.isEmpty) Locale.getDefault() else locales[0]
        },
      ),
    )
  }

  private val onlineSyncCoordinator: OnlineSyncCoordinator by lazy {
    val syncApi = AfterchimeOnlineSyncApi(onlineApi)
    OnlineSyncCoordinator(
      preferences = { userPreferences.values.first() },
      identity = RepositoryOnlineIdentityClient(onlineIdentityRepository),
      tokenClient = AuthenticatorOnlineTokenClient(onlineTokenAuthenticator),
      api = syncApi,
      summaries = RoomDailySummaryUploadStore(database.dailyNotificationSummaryDao()),
      analyticsOutbox = AnalyticsOutboxUploader(
        store = RoomAnalyticsUploadStore(database.analyticsOutboxDao()),
        decryptor = analyticsCipher,
        api = syncApi,
      ),
      state = SharedPreferencesOnlineSyncStateStore(this),
      registrationRequest = {
        RegistrationRequest(
          appVersion = BuildConfig.VERSION_NAME,
          androidApiLevel = Build.VERSION.SDK_INT,
          deviceClass = onlineDeviceClass(resources.configuration.smallestScreenWidthDp),
        )
      },
    )
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

  internal fun scheduleOnlineSync() {
    onlineWorkManager()?.let { OnlineSyncScheduler(it).scheduleImmediate() }
  }

  internal suspend fun developmentFeedbackToken(): String? {
    if (!BuildConfig.DEV_FEEDBACK_ENABLED) return null
    return withContext(Dispatchers.IO) {
      val current = when (val session = onlineIdentityRepository.currentSession()) {
        is IdentityResult.Success -> session.value
        is IdentityResult.Failure -> return@withContext null
      }
      if (current == null) {
        val preferences = getSharedPreferences(DEV_FEEDBACK_IDENTITY_STORE, MODE_PRIVATE)
        val idempotencyKey = preferences.getString(DEV_FEEDBACK_IDEMPOTENCY_KEY, null)
          ?: UUID.randomUUID().toString().also { generated ->
            if (!preferences.edit().putString(DEV_FEEDBACK_IDEMPOTENCY_KEY, generated).commit()) {
              return@withContext null
            }
          }
        val registered = onlineIdentityRepository.register(
          RegistrationRequest(
            appVersion = BuildConfig.VERSION_NAME,
            androidApiLevel = Build.VERSION.SDK_INT,
            deviceClass = onlineDeviceClass(resources.configuration.smallestScreenWidthDp),
          ),
          idempotencyKey,
        )
        if (registered !is IdentityResult.Success) return@withContext null
        preferences.edit().remove(DEV_FEEDBACK_IDEMPOTENCY_KEY).commit()
      }
      (onlineTokenAuthenticator.accessToken() as? com.techfullymade.afterchime.identity.TokenResult.Available)
        ?.accessToken
    }
  }

  internal suspend fun recordProductUse(
    event: AnalyticsEvent,
    screenName: String? = null,
  ): AnalyticsRecordResult = productUseReporter.record(event, screenName).also { result ->
    if (result == AnalyticsRecordResult.RECORDED) scheduleOnlineSync()
  }

  override fun onCreate() {
    super.onCreate()
    OnlineSyncRuntimeRegistry.runner = OnlineSyncRunner { onlineSyncCoordinator.sync() }
    onlineWorkManager()?.let { OnlineSyncScheduler(it).schedule() }
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
    OnlineSyncRuntimeRegistry.runner = null
    NotificationCaptureRuntimeRegistry.runtime = null
    ObservationRuntimeRegistry.repository = null
    SealDayRuntimeRegistry.useCase = null
    super.onTerminate()
  }

  private fun onlineWorkManager(): WorkManager? = try {
    WorkManager.getInstance(this)
  } catch (_: IllegalStateException) {
    null
  }

  private companion object {
    const val DATABASE_NAME = "afterchime.db"
    const val DEV_FEEDBACK_IDENTITY_STORE = "development_feedback_identity"
    const val DEV_FEEDBACK_IDEMPOTENCY_KEY = "registration_idempotency_key"
    const val ONLINE_TOKEN_REFRESH_SKEW_MILLIS = 60_000L
  }
}

internal fun onlineDeviceClass(smallestScreenWidthDp: Int): String =
  if (smallestScreenWidthDp >= 600) "tablet" else "phone"

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
