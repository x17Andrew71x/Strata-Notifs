package com.techfullymade.afterchime

import androidx.room.Room
import com.techfullymade.afterchime.capture.NotificationCaptureRuntimeRegistry
import com.techfullymade.afterchime.capture.ObservationRepository
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.sealing.SealDayInputs
import com.techfullymade.afterchime.sealing.SealDayPersistence
import com.techfullymade.afterchime.sealing.SealDayRuntimeRegistry
import com.techfullymade.afterchime.sealing.SealDayStore
import com.techfullymade.afterchime.sealing.SealDayUseCase
import com.techfullymade.afterchime.sealing.SealedDay
import com.techfullymade.afterchime.security.EncryptedLocalSecret
import com.techfullymade.afterchime.security.KeystoreMaterial
import com.techfullymade.afterchime.security.LocalSecretEnvelopeStore
import com.techfullymade.afterchime.security.LocalSecretStore
import com.techfullymade.afterchime.security.LocalSecretRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AfterchimeRuntimeTest {
  private lateinit var database: AfterchimeDatabase

  @Before
  fun setUp() {
    NotificationCaptureRuntimeRegistry.runtime = null
    SealDayRuntimeRegistry.useCase = null
    database = Room.inMemoryDatabaseBuilder(
      RuntimeEnvironment.getApplication(),
      AfterchimeDatabase::class.java,
    )
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    NotificationCaptureRuntimeRegistry.runtime = null
    SealDayRuntimeRegistry.useCase = null
    database.close()
  }

  @Test
  fun `runtime activation supplies the same protected material to capture and sealing`() {
    val localSecretStore = LocalSecretStore(
      keyMaterial = FakeKeystoreMaterial(),
      envelopeStore = FakeEnvelopeStore(),
      random = LocalSecretRandom { ByteArray(LocalSecretStore.GENERATOR_SECRET_BYTES) { 7 } },
    )

    var scheduled = false
    val installed = installAfterchimeRuntime(
      ownPackageName = "com.techfullymade.afterchime",
      timeZone = ZoneOffset.UTC,
      localSecretStore = localSecretStore,
      observationRepository = ObservationRepository(
        listenerAccessStateDao = database.listenerAccessStateDao(),
        reducedNotificationDao = database.reducedNotificationDao(),
        timeZone = ZoneOffset.UTC,
      ),
      sealDayUseCase = SealDayUseCase(
        store = EmptySealDayStore,
        localSecretProvider = localSecretStore::loadGeneratorSecret,
        clock = Clock.systemUTC(),
        timeZone = ZoneOffset.UTC,
      ),
      onInstalled = { scheduled = true },
    )

    assertTrue(installed)
    assertTrue(scheduled)
    assertNotNull(NotificationCaptureRuntimeRegistry.runtime)
    assertNotNull(SealDayRuntimeRegistry.useCase)
  }

  @Test
  fun `runtime activation fails closed when initialized protected material is unavailable`() {
    var scheduled = false
    val installed = installAfterchimeRuntime(
      ownPackageName = "com.techfullymade.afterchime",
      timeZone = ZoneOffset.UTC,
      localSecretStore = LocalSecretStore(
        keyMaterial = FakeKeystoreMaterial(),
        envelopeStore = FakeEnvelopeStore(initialized = true),
        random = LocalSecretRandom { ByteArray(LocalSecretStore.GENERATOR_SECRET_BYTES) },
      ),
      observationRepository = ObservationRepository(
        listenerAccessStateDao = database.listenerAccessStateDao(),
        reducedNotificationDao = database.reducedNotificationDao(),
        timeZone = ZoneOffset.UTC,
      ),
      sealDayUseCase = SealDayUseCase(
        store = EmptySealDayStore,
        localSecretProvider = { error("unavailable material must not seal") },
        clock = Clock.systemUTC(),
        timeZone = ZoneOffset.UTC,
      ),
      onInstalled = { scheduled = true },
    )

    assertFalse(installed)
    assertFalse(scheduled)
    assertNull(NotificationCaptureRuntimeRegistry.runtime)
    assertNull(SealDayRuntimeRegistry.useCase)
  }

  private object EmptySealDayStore : SealDayStore {
    override suspend fun candidateLocalDatesBefore(exclusiveDate: LocalDate): List<LocalDate> = emptyList()

    override suspend fun inputsFor(localDate: LocalDate): SealDayInputs = error("not used")

    override suspend fun persist(sealedDay: SealedDay): SealDayPersistence = error("not used")
  }

  private class FakeEnvelopeStore(
    private var initialized: Boolean = false,
  ) : LocalSecretEnvelopeStore {
    private var envelope: EncryptedLocalSecret? = null

    override fun read(): EncryptedLocalSecret? = envelope

    override fun persist(encryptedSecret: EncryptedLocalSecret): Boolean {
      envelope = encryptedSecret
      initialized = true
      return true
    }

    override fun isInitialized(): Boolean = initialized

    override fun clear(): Boolean {
      envelope = null
      initialized = false
      return true
    }
  }

  private class FakeKeystoreMaterial : KeystoreMaterial {
    private var sourceHmacKey: SecretKey? = null
    private var wrappingKey: SecretKey? = null

    override fun sourceHmacKey(): SecretKey? = sourceHmacKey

    override fun createSourceHmacKey(): SecretKey = SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256").also {
      sourceHmacKey = it
    }

    override fun wrappingKey(): SecretKey? = wrappingKey

    override fun createWrappingKey(): SecretKey = SecretKeySpec(ByteArray(32) { 2 }, "AES").also {
      wrappingKey = it
    }

    override fun encrypt(
      plaintext: ByteArray,
      wrappingKey: SecretKey,
    ): EncryptedLocalSecret = EncryptedLocalSecret(byteArrayOf(1), plaintext.copyOf())

    override fun decrypt(
      encryptedSecret: EncryptedLocalSecret,
      wrappingKey: SecretKey,
    ): ByteArray = encryptedSecret.ciphertext.copyOf()

    override fun deleteSourceHmacKey() {
      sourceHmacKey = null
    }

    override fun deleteWrappingKey() {
      wrappingKey = null
    }
  }
}
