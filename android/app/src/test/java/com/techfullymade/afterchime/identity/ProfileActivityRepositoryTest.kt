package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.ApiFailure
import com.techfullymade.afterchime.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileActivityRepositoryTest {
  @Test
  fun `foreground app open records general profile activity with the current session`() = runBlocking {
    val api = FakeProfileActivityApi()
    val repository = ProfileActivityRepository(
      api = api,
      tokenProvider = ProfileTokenProvider { "access-token" },
    )

    assertTrue(repository.recordOpened())
    assertEquals(listOf("access-token"), api.tokens)
  }

  @Test
  fun `offline or unauthenticated foreground open remains harmless`() = runBlocking {
    val noSession = ProfileActivityRepository(
      api = FakeProfileActivityApi(),
      tokenProvider = ProfileTokenProvider { null },
    )
    assertFalse(noSession.recordOpened())

    val unavailableApi = FakeProfileActivityApi().apply {
      result = ApiResult.failure(ApiFailure.Network)
    }
    val unavailable = ProfileActivityRepository(
      api = unavailableApi,
      tokenProvider = ProfileTokenProvider { "access-token" },
    )
    assertFalse(unavailable.recordOpened())
  }

  private class FakeProfileActivityApi : ProfileActivityApiGateway {
    val tokens = mutableListOf<String>()
    var result: ApiResult<Unit> = ApiResult.success(Unit)

    override fun markOpened(bearerToken: String): ApiResult<Unit> {
      tokens += bearerToken
      return result
    }
  }
}
