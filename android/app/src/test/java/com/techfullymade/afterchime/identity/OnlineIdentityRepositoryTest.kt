package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import com.techfullymade.afterchime.network.LogoutRequest
import com.techfullymade.afterchime.network.RefreshRequest
import com.techfullymade.afterchime.network.RegistrationRequest
import com.techfullymade.afterchime.network.SessionResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineIdentityRepositoryTest {
  @Test
  fun `construction is inert and registration passes exact retry key`() {
    val fixture = Fixture()
    val repository = fixture.repository()
    assertEquals(0, fixture.gateway.registerCalls)
    val request = RegistrationRequest("1.2.3", 35, "phone")
    val key = "123e4567-e89b-42d3-a456-426614174000"
    assertTrue(repository.register(request, key) is IdentityResult.Success)
    assertTrue(repository.register(request, key) is IdentityResult.Success)
    assertEquals(listOf(key, key), fixture.gateway.registrationKeys)
    assertEquals("r2", fixture.readSession()?.refreshToken)
  }

  @Test
  fun `token authentication reuses current credential and refreshes after expiry`() {
    val fixture = Fixture()
    fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis() + 5_000))
    val auth = TokenAuthenticator(fixture.repository(), fixture.clock, refreshSkewMillis = 1_000)
    assertEquals(TokenResult.Available("a1"), auth.accessToken())
    assertEquals(0, fixture.gateway.refreshCalls)
    fixture.clock.advanceMillis(4_500)
    assertEquals(TokenResult.Available("a2"), auth.accessToken())
    assertEquals(1, fixture.gateway.refreshCalls)
    assertEquals("r2", fixture.readSession()?.refreshToken)
  }

  @Test
  fun `refresh unauthorized clears replay credentials`() {
    val fixture = Fixture()
    fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis()))
    fixture.gateway.refreshResults.add(ApiResult.failure(ApiFailure.Unauthorized))
    val outcome = fixture.repository().refresh()
    assertEquals(IdentityResult.Failure(IdentityFailure.Api(ApiFailure.Unauthorized)), outcome)
    assertEquals(CredentialRead.Missing, fixture.store.read())
  }

  @Test
  fun `mismatched ownership and uncertain refresh retain prior session`() {
    val fixture = Fixture()
    val prior = fixture.session("a1", "r1", fixture.clock.millis())
    fixture.store.save(prior)
    fixture.gateway.refreshResults.add(ApiResult.success(fixture.response(installation = "other-installation")))
    assertEquals(IdentityFailure.RejectedSession,
      (fixture.repository().refresh() as IdentityResult.Failure).reason)
    assertEquals(prior, fixture.readSession())
    fixture.gateway.refreshResults.add(ApiResult.failure(ApiFailure.Network))
    assertEquals(IdentityFailure.Api(ApiFailure.Network),
      (fixture.repository().refresh() as IdentityResult.Failure).reason)
    assertEquals(prior, fixture.readSession())
  }

  @Test
  fun `failed protected persistence leaves complete previous envelope intact`() {
    val fixture = Fixture()
    val prior = fixture.session("a1", "r1", fixture.clock.millis())
    fixture.store.save(prior)
    fixture.storage.failReplace = true
    fixture.gateway.refreshResults.add(ApiResult.success(fixture.response("a2", "r2")))
    assertEquals(IdentityFailure.ProtectedStorageUnavailable,
      (fixture.repository().refresh() as IdentityResult.Failure).reason)
    assertEquals(prior, fixture.readSession())
  }

  @Test
  fun `logout success and unauthorized clear but uncertain failure preserves credentials`() {
    val fixture = Fixture()
    fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis()))
    fixture.gateway.logoutResults.add(ApiResult.failure(ApiFailure.Network))
    assertEquals(IdentityFailure.Api(ApiFailure.Network),
      (fixture.repository().logout() as IdentityResult.Failure).reason)
    assertTrue(fixture.readSession() != null)
    fixture.gateway.logoutResults.add(ApiResult.failure(ApiFailure.Unauthorized))
    assertEquals(IdentityFailure.Api(ApiFailure.Unauthorized),
      (fixture.repository().logout() as IdentityResult.Failure).reason)
    assertEquals(CredentialRead.Missing, fixture.store.read())
    fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis()))
    fixture.gateway.logoutResults.add(ApiResult.success(Unit))
    assertTrue(fixture.repository().logout() is IdentityResult.Success)
    assertEquals(CredentialRead.Missing, fixture.store.read())
  }

  @Test
  fun `fresh store accepts first save but missing envelope after initialization fails closed`() {
    val fixture = Fixture()
    assertEquals(CredentialRead.Missing, fixture.store.read())
    assertTrue(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    fixture.storage.envelope = null
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    assertFalse(fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis())))
  }

  @Test
  fun `initialization marker loss fails closed`() {
    val fixture = Fixture()
    assertTrue(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    fixture.storage.initialized = false
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    assertFalse(fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis())))
  }

  @Test
  fun `simultaneous envelope and key loss after initialization fails closed`() {
    val fixture = Fixture()
    assertTrue(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    fixture.storage.envelope = null
    fixture.cipher.keyExists = false
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    assertFalse(fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis())))
  }

  @Test
  fun `unavailable or replaced key fails closed without replacement`() {
    val fixture = Fixture()
    assertTrue(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    fixture.cipher.keyExists = false
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    assertFalse(fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis())))
  }

  @Test
  fun `malformed envelope fails closed`() {
    val fixture = Fixture()
    assertTrue(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    fixture.storage.envelope = byteArrayOf(1, 2, 3)
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    assertFalse(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    assertNull(fixture.readSession())
  }

  @Test
  fun `failed first durable write leaves initialized key without minting replacement`() {
    val fixture = Fixture()
    fixture.storage.failReplace = true
    assertFalse(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
    fixture.storage.failReplace = false
    assertFalse(fixture.store.save(fixture.session("a2", "r2", fixture.clock.millis())))
  }

  @Test
  fun `failed initialization marker write retains key and fails closed`() {
    val fixture = Fixture()
    fixture.storage.failInitialize = true
    assertFalse(fixture.store.save(fixture.session("a1", "r1", fixture.clock.millis())))
    assertTrue(fixture.cipher.keyExists)
    assertEquals(CredentialRead.Unavailable, fixture.store.read())
  }

  @Test
  fun `encrypted durable boundary contains no plaintext`() {
    val fixture = Fixture()
    val session = fixture.session("a1", "r1", fixture.clock.millis() + 1_000)
    assertTrue(fixture.store.save(session))
    val durable = fixture.storage.envelope!!.toString(Charsets.ISO_8859_1)
    assertFalse(durable.contains(session.accessToken))
    assertFalse(durable.contains(session.refreshToken))
    assertTrue(fixture.store.read() is CredentialRead.Available)
  }

  private class Fixture {
    val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    val storage = MemoryEnvelopeStorage()
    val cipher = TestCipher()
    val store = CredentialStore(storage, cipher)
    val gateway = FakeGateway()
    fun repository() = OnlineIdentityRepository(gateway, store, clock)
    fun readSession() = (store.read() as? CredentialRead.Available)?.session
    fun session(access: String, refresh: String, expires: Long) = CredentialSession("installation-1", access, refresh, expires)
    fun response(access: String = "a2", refresh: String = "r2", installation: String = "installation-1") =
      SessionResponse(access, 900, installation, refresh, "Bearer")
  }

  private class FakeGateway : IdentityGateway {
    var registerCalls = 0
    var refreshCalls = 0
    val registrationKeys = mutableListOf<String>()
    val refreshResults = ArrayDeque<ApiResult<SessionResponse>>()
    val logoutResults = ArrayDeque<ApiResult<Unit>>()
    override fun register(request: RegistrationRequest, idempotencyKey: String): ApiResult<SessionResponse> {
      registerCalls += 1
      registrationKeys += idempotencyKey
      return ApiResult.success(SessionResponse("a$registerCalls", 900, "installation-1", "r$registerCalls", "Bearer"))
    }
    override fun refresh(request: RefreshRequest): ApiResult<SessionResponse> {
      refreshCalls += 1
      return if (refreshResults.isEmpty()) ApiResult.success(SessionResponse("a2", 900, "installation-1", "r2", "Bearer"))
      else refreshResults.removeFirst()
    }
    override fun logout(request: LogoutRequest): ApiResult<Unit> =
      if (logoutResults.isEmpty()) ApiResult.success(Unit) else logoutResults.removeFirst()
  }

  private class MemoryEnvelopeStorage : CredentialEnvelopeStorage {
    var envelope: ByteArray? = null
    var failReplace = false
    var initialized = false
    var failInitialize = false
    override fun read() = envelope?.copyOf()
    override fun replace(envelope: ByteArray): Boolean {
      if (failReplace) return false
      this.envelope = envelope.copyOf()
      return true
    }
    override fun clear(): Boolean { envelope = null; return true }
    override fun isInitialized() = initialized
    override fun markInitialized(): Boolean {
      if (failInitialize) return false
      initialized = true
      return true
    }
    override fun clearInitialization(): Boolean { initialized = false; return true }
  }

  private class TestCipher : CredentialCipher {
    var keyExists = false
    var unavailable = false
    override fun hasKey() = keyExists
    override fun encrypt(plaintext: ByteArray): ByteArray {
      keyExists = true
      return ByteArray(12) { 7 } + plaintext.map { (it.toInt() xor 0x5a).toByte() }
    }
    override fun decrypt(envelope: ByteArray): ByteArray {
      if (!keyExists || unavailable || envelope.size < 28 || envelope.take(12).any { it != 7.toByte() }) {
        throw IllegalStateException()
      }
      return envelope.drop(12).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
    override fun deleteKey(): Boolean {
      keyExists = false
      return true
    }
  }

  private class MutableClock(private var instant: Instant) : Clock() {
    fun advanceMillis(millis: Long) { instant = instant.plusMillis(millis) }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
  }
}
