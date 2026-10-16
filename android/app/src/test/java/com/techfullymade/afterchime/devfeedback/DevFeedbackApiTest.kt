package com.techfullymade.afterchime.devfeedback

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ConnectionFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevFeedbackApiTest {
  @Test
  fun `submission sends only strict context screenshot and idempotency fields`() {
    lateinit var connection: FakeConnection
    val api = DevFeedbackApi(
      "https://example.test",
      true,
      ConnectionFactory { url -> FakeConnection(url, 201, stateJson()).also { connection = it } },
    )

    val result = api.submit(draft(), screenshot(), "sample-token")

    assertNull(result.failure)
    assertEquals("/v1/dev-feedback/items", connection.url.path)
    assertEquals("POST", connection.requestMethod)
    assertEquals("Bearer sample-token", connection.header("Authorization"))
    assertEquals(draft().idempotencyKey, connection.header("Idempotency-Key"))
    assertFalse(connection.instanceFollowRedirects)
    val body = JSONObject(connection.writtenBody)
    assertEquals(setOf("title", "description", "context", "screenshot"), body.keys().asSequence().toSet())
    assertEquals(
      setOf("route", "shellRevision", "appVersion", "selection"),
      body.getJSONObject("context").keys().asSequence().toSet(),
    )
    assertEquals(
      setOf("mediaType", "contentBase64", "sha256", "width", "height"),
      body.getJSONObject("screenshot").keys().asSequence().toSet(),
    )
    assertEquals("queued", result.value?.item?.status)
  }

  @Test
  fun `list amendment and acceptance use fixed scoped routes`() {
    val connections = mutableListOf<FakeConnection>()
    val api = DevFeedbackApi(
      "https://example.test",
      true,
      ConnectionFactory { url -> FakeConnection(url, 200, stateJson()).also(connections::add) },
    )
    val itemId = "11111111-1111-4111-8111-111111111111"

    assertNull(api.list("sample-token").failure)
    assertNull(api.amend(itemId, draft(), screenshot(), "sample-token").failure)
    assertNull(api.accept(itemId, "sample-token").failure)

    assertEquals(
      listOf(
        "/v1/dev-feedback/items",
        "/v1/dev-feedback/items/$itemId/amendments",
        "/v1/dev-feedback/items/$itemId/accept",
      ),
      connections.map { it.url.path },
    )
    assertEquals(listOf("GET", "POST", "POST"), connections.map(HttpURLConnection::getRequestMethod))
  }

  @Test
  fun `response parser rejects expanded unsorted or malformed work items`() {
    val parsed = DevFeedbackApi.parseState(stateJson())
    assertEquals(1L, parsed.items.single().queuePosition)

    val expanded = JSONObject(stateJson()).put("leaseId", "private")
    assertTrue(runCatching { DevFeedbackApi.parseState(expanded.toString()) }.isFailure)
    val malformed = JSONObject(stateJson()).apply {
      getJSONArray("items").getJSONObject(0).put("status", "deployed_maybe")
    }
    assertTrue(runCatching { DevFeedbackApi.parseState(malformed.toString()) }.isFailure)
  }

  @Test
  fun `invalid identity token item id and oversized input fail without network`() {
    var opens = 0
    val api = DevFeedbackApi(
      "https://example.test",
      true,
      ConnectionFactory { url -> opens += 1; FakeConnection(url, 200, stateJson()) },
    )

    assertEquals(ApiFailure.MalformedResponse, api.list("bad token").failure)
    assertEquals(ApiFailure.MalformedResponse, api.accept("not-an-id", "sample-token").failure)
    val oversized = draft().copy(description = "x".repeat(1_200_000))
    assertEquals(ApiFailure.MalformedResponse, api.submit(oversized, screenshot(), "sample-token").failure)
    assertEquals(0, opens)
  }

  private fun draft() = DevFeedbackDraft(
    idempotencyKey = "22222222-2222-4222-8222-222222222222",
    title = "Tighten the action",
    description = "Reduce the empty space above it.",
    route = "today",
    shellRevision = "a".repeat(64),
    appVersion = "0.1.10-dev-debug",
    selection = JSONObject()
      .put("kind", "element")
      .put("rect", JSONObject().put("x", 10).put("y", 20).put("width", 120).put("height", 44))
      .put("viewportWidth", 360)
      .put("viewportHeight", 800)
      .put("devId", "excavation-action")
      .put("tag", "button")
      .put("role", "button")
      .put("accessibleLabel", "Excavate tile 1")
      .put("visibleLabel", "Excavate")
      .put("domPath", "[data-dev-id=excavation-action]"),
  )

  private fun screenshot() = DevFeedbackScreenshot(
    mediaType = "image/webp",
    bytes = byteArrayOf(1, 2, 3, 4),
    sha256 = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
    width = 360,
    height = 800,
  )

  private fun stateJson(): String {
    val item = JSONObject()
      .put("id", "11111111-1111-4111-8111-111111111111")
      .put("queuePosition", 1)
      .put("revision", 1)
      .put("title", "Tighten the action")
      .put("description", "Reduce the empty space above it.")
      .put("status", "queued")
      .put("note", "Queued in submission order.")
      .put("createdAt", "2026-10-16T00:00:00Z")
      .put("updatedAt", "2026-10-16T00:00:00Z")
    return JSONObject()
      .put("item", item)
      .put("items", org.json.JSONArray().put(item))
      .toString()
  }

  private class FakeConnection(
    url: URL,
    private val status: Int,
    private val response: String,
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
