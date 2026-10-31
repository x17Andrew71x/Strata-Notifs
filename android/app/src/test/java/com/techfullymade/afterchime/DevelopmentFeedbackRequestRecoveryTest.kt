package com.techfullymade.afterchime

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevelopmentFeedbackRequestRecoveryTest {
  @Test
  fun `API unauthorized rotates the identity and retries the exact operation once`() = runBlocking {
    val tokens = mutableListOf<String>()
    var recoveries = 0

    val result = executeDevelopmentFeedbackRequest(
      tokenProvider = { "rejected-access" },
      unauthorizedTokenProvider = {
        recoveries += 1
        "replacement-access"
      },
      request = { token ->
        tokens += token
        if (token == "rejected-access") {
          ApiResult.failure(ApiFailure.Unauthorized)
        } else {
          ApiResult.success("visible")
        }
      },
    )

    assertEquals("visible", result?.value)
    assertEquals(listOf("rejected-access", "replacement-access"), tokens)
    assertEquals(1, recoveries)
  }

  @Test
  fun `uncertain failure preserves the current identity without a retry`() = runBlocking {
    var recoveries = 0
    var requests = 0

    val result = executeDevelopmentFeedbackRequest(
      tokenProvider = { "current-access" },
      unauthorizedTokenProvider = {
        recoveries += 1
        "replacement-access"
      },
      request = {
        requests += 1
        ApiResult.failure<String>(ApiFailure.Network)
      },
    )

    assertEquals(ApiFailure.Network, result?.failure)
    assertEquals(1, requests)
    assertEquals(0, recoveries)
  }

  @Test
  fun `a second unauthorized response stops without a third request`() = runBlocking {
    val tokens = mutableListOf<String>()

    val result = executeDevelopmentFeedbackRequest(
      tokenProvider = { "rejected-access" },
      unauthorizedTokenProvider = { "replacement-access" },
      request = { token ->
        tokens += token
        ApiResult.failure<String>(ApiFailure.Unauthorized)
      },
    )

    assertEquals(ApiFailure.Unauthorized, result?.failure)
    assertEquals(listOf("rejected-access", "replacement-access"), tokens)
  }

  @Test
  fun `missing identity does not touch the API`() = runBlocking {
    var requests = 0
    val result = executeDevelopmentFeedbackRequest(
      tokenProvider = { null },
      unauthorizedTokenProvider = { error("must not recover before an authenticated rejection") },
      request = {
        requests += 1
        ApiResult.success("unexpected")
      },
    )

    assertNull(result)
    assertEquals(0, requests)
  }
}
