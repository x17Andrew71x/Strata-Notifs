package com.techfullymade.afterchime.identity

import com.techfullymade.afterchime.network.AfterchimeApi
import com.techfullymade.afterchime.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface ProfileTokenProvider {
  suspend fun accessToken(): String?
}

internal fun interface ProfileActivityApiGateway {
  fun markOpened(bearerToken: String): ApiResult<Unit>
}

internal class NetworkProfileActivityApi(
  private val api: AfterchimeApi,
) : ProfileActivityApiGateway {
  override fun markOpened(bearerToken: String): ApiResult<Unit> = api.markOpened(bearerToken)
}

/** Records a foreground app open without coupling profile activity to community consent. */
internal class ProfileActivityRepository(
  private val api: ProfileActivityApiGateway,
  private val tokenProvider: ProfileTokenProvider,
) {
  suspend fun recordOpened(): Boolean = withContext(Dispatchers.IO) {
    val token = tokenProvider.accessToken() ?: return@withContext false
    api.markOpened(token).value != null
  }
}
