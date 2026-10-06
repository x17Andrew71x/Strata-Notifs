package com.techfullymade.afterchime.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContractTest {
  @Test
  fun `shared fixtures exercise the closed v1 boundary`() {
    fixtures("valid").forEach { fixture ->
      val outcome = runCatching { ApiModels.validateAnalyticsEnvelope(fixture.readText()) }
      assertTrue("valid fixture rejected: ${fixture.name}", outcome.isSuccess)
    }
    fixtures("invalid").forEach { fixture ->
      val outcome = runCatching { ApiModels.validateAnalyticsEnvelope(fixture.readText()) }
      assertTrue("invalid fixture accepted: ${fixture.name}", outcome.isFailure)
    }
  }

  @Test
  fun `wire codecs preserve casing and reject unknown response shapes`() {
    val registration = JSONObject(ApiModels.encodeRegistration(RegistrationRequest("1.2.3", 35, "phone")))
    assertEquals(setOf("appVersion", "device"), registration.keys().asSequence().toSet())
    assertEquals(35, registration.getJSONObject("device").getInt("androidApiLevel"))
    assertEquals("phone", registration.getJSONObject("device").getString("deviceClass"))

    val refreshValue = "x".repeat(43)
    assertEquals(refreshValue, JSONObject(ApiModels.encodeRefresh(RefreshRequest(refreshValue))).getString("refreshToken"))
    assertEquals(refreshValue, JSONObject(ApiModels.encodeLogout(LogoutRequest(refreshValue))).getString("refreshToken"))

    val consent = JSONObject(ApiModels.encodeConsent(ConsentUpdate(true, "product_analytics", "v1")))
    assertEquals(setOf("granted", "scope", "scopeVersion"), consent.keys().asSequence().toSet())

    val session = ApiModels.parseSession(
      """{"accessToken":"sample-value","expiresInSeconds":900,"installationId":"123e4567-e89b-42d3-a456-426614174000","refreshToken":"$refreshValue","tokenType":"Bearer"}""",
    )
    assertEquals("Bearer", session.tokenType)
    assertEquals(
      listOf(ConsentState(true, "product_analytics", "v1")),
      ApiModels.parseConsent("""{"consents":[{"granted":true,"scope":"product_analytics","scopeVersion":"v1"}]}""").consents,
    )
    assertEquals(
      "accepted",
      ApiModels.parseAnalyticsBatch(
        """{"results":[{"eventId":"123e4567-e89b-42d3-a456-426614174000","status":"accepted"}]}""",
      ).results.single().status,
    )
    assertEquals(
      "duplicate",
      ApiModels.parseDailyAggregateResult(
        """{"result":{"localDate":"2026-01-02","revision":1,"status":"duplicate"}}""",
      ).status,
    )
    assertEquals(
      ErrorResponse("aggregate_revision_conflict"),
      ApiModels.parseError(
        """{"error":"aggregate_revision_conflict"}""",
        setOf("aggregate_revision_conflict"),
      ),
    )

    assertRejected { ApiModels.parseSession("not-json") }
    assertRejected {
      ApiModels.parseSession(
        """{"accessToken":"sample-value","expiresInSeconds":900,"installationId":"123e4567-e89b-42d3-a456-426614174000","refreshToken":"$refreshValue","tokenType":"Bearer","extra":true}""",
      )
    }
    assertRejected { ApiModels.parseConsent("""{"consents":[{"granted":true,"scope":"unknown","scopeVersion":"v1"}]}""") }
    assertRejected { ApiModels.parseAnalyticsBatch("""{"results":[],"extra":true}""") }
    assertRejected {
      ApiModels.parseDailyAggregateResult(
        """{"result":{"localDate":"2026-01-02","revision":1,"status":"duplicate","extra":true}}""",
      )
    }
    assertRejected { ApiModels.parseError("""{"error":"unknown"}""", setOf("unauthorized")) }
  }

  @Test
  fun `unknown analytics versions and malformed properties fail closed`() {
    try {
      ApiModels.validateAnalyticsEnvelope(event(version = 2))
      fail("version accepted")
    } catch (_: UnsupportedVersionException) {
      // Expected.
    }

    val api = AfterchimeApi("https://example.test", false)
    assertEquals(
      ApiFailure.UnsupportedSchemaVersion,
      api.submitAnalyticsBatch("""{"events":[${event(version = 2)}]}""", "sample-token").failure,
    )

    val invalidLaunch = JSONObject(event()).apply {
      getJSONObject("properties").put("launch_type", "boiling")
    }
    assertRejected { ApiModels.validateAnalyticsEnvelope(invalidLaunch.toString()) }

    val missingLocale = JSONObject(event()).apply { remove("locale") }
    assertRejected { ApiModels.validateAnalyticsEnvelope(missingLocale.toString()) }
  }

  @Test
  fun `base URL policy permits only secure or development loopback origins`() {
    assertNotNull(AfterchimeApi.validatedBaseUrl("https://api.example.com", false))
    assertNotNull(AfterchimeApi.validatedBaseUrl("http://10.0.2.2:3000", true))
    assertNotNull(AfterchimeApi.validatedBaseUrl("http://127.0.0.1:3000", true))
    assertNotNull(AfterchimeApi.validatedBaseUrl("http://localhost:3000", true))
    assertNotNull(AfterchimeApi.validatedBaseUrl("http://[::1]:3000", true))

    assertNull(AfterchimeApi.validatedBaseUrl("http://example.com", true))
    assertNull(AfterchimeApi.validatedBaseUrl("http://localhost", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://user:pass@example.com", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://example.com/path", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://example.com?query=value", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://example.com/#fragment", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://:443", false))
    assertNull(AfterchimeApi.validatedBaseUrl("https://example.com:0", false))
  }

  @Test
  fun `transport is injectable bounded inert and does not follow redirects`() {
    var opens = 0
    lateinit var connection: FakeConnection
    val api = AfterchimeApi(
      "https://example.test",
      false,
      ConnectionFactory { url ->
        opens += 1
        FakeConnection(url).also { connection = it }
      },
    )
    assertEquals(0, opens)

    val idempotencyKey = "123e4567-e89b-42d3-a456-426614174000"
    val result = api.register(RegistrationRequest("1.0.0", 35, "phone"), idempotencyKey)

    assertEquals(ApiFailure.Http, result.failure)
    assertEquals(1, opens)
    assertEquals("https", connection.url.protocol)
    assertEquals("/v1/installations", connection.url.path)
    assertEquals("POST", connection.requestMethod)
    assertEquals(idempotencyKey, connection.header("Idempotency-Key"))
    assertTrue(connection.writtenBody.contains("\"appVersion\":\"1.0.0\""))
    assertFalse(connection.instanceFollowRedirects)
    assertTrue(connection.connectTimeout in 1..15_000)
    assertTrue(connection.readTimeout in 1..15_000)

    val inertRefreshValue = "x".repeat(43)
    api.refresh(RefreshRequest(inertRefreshValue))
    assertEquals("/v1/auth/refresh", connection.url.path)
    assertEquals("POST", connection.requestMethod)

    api.logout(LogoutRequest(inertRefreshValue))
    assertEquals("/v1/auth/logout", connection.url.path)
    assertEquals("POST", connection.requestMethod)
  }

  @Test
  fun `failures do not retain response token URL header or request text`() {
    val responseText = "response-body-sentinel"
    val refreshValue = "z".repeat(43)
    val baseUrl = "https://sensitive-origin.invalid"
    val api = AfterchimeApi(
      baseUrl,
      false,
      ConnectionFactory { FakeConnection(it, 500, responseText) },
    )

    val result = api.refresh(RefreshRequest(refreshValue))
    val rendered = result.toString()

    assertEquals(ApiFailure.Http, result.failure)
    assertFalse(rendered.contains(responseText))
    assertFalse(rendered.contains(refreshValue))
    assertFalse(rendered.contains(baseUrl))
    assertFalse(rendered.contains("Authorization"))
    assertFalse(rendered.contains("Bearer"))
  }

  private fun event(version: Int = 1): String =
    """{"event_id":"123e4567-e89b-42d3-a456-426614174000","event_name":"app_opened","schema_version":$version,"installation_id":"123e4567-e89b-42d3-a456-426614174000","occurred_at":"2026-01-01T00:00:00Z","local_date":"2026-01-01","timezone_offset_minutes":0,"app_version":"1.0.0","version_code":1,"build_channel":"dev","android_api_level":35,"device_class":"phone","locale":"en-US","consent_scope_version":1,"properties":{"launch_type":"cold"}}"""

  private fun fixtures(kind: String): List<File> {
    val workingDirectory = System.getProperty("user.dir") ?: throw AssertionError("working directory is unavailable")
    var candidate = File(workingDirectory).canonicalFile
    while (true) {
      val directory = File(candidate, "contracts/fixtures/$kind")
      if (directory.isDirectory) {
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
          ?.sortedBy { file -> file.name }
          ?: throw AssertionError("unable to list shared $kind fixtures")
      }
      candidate = candidate.parentFile ?: throw AssertionError("shared fixture directory was not found")
    }
  }

  private fun assertRejected(block: () -> Unit) {
    try {
      block()
      fail("expected rejection")
    } catch (_: RuntimeException) {
      // Expected.
    }
  }

  private class FakeConnection(
    url: URL,
    private val status: Int = 302,
    private val response: String = "",
  ) : HttpURLConnection(url) {
    private val headers = mutableMapOf<String, String>()
    private val output = ByteArrayOutputStream()

    val writtenBody: String
      get() = output.toString(Charsets.UTF_8.name())

    fun header(name: String): String? = headers[name]

    override fun connect() {
      connected = true
    }

    override fun disconnect() {
      connected = false
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = status

    override fun getInputStream(): ByteArrayInputStream = ByteArrayInputStream(response.toByteArray())

    override fun getOutputStream(): OutputStream = output

    override fun setRequestProperty(key: String?, value: String?) {
      if (key != null && value != null) headers[key] = value
    }
  }
}
