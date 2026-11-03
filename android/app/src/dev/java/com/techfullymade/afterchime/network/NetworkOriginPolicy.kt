package com.techfullymade.afterchime.network

internal object NetworkOriginPolicy {
  private val loopbackHosts = setOf("10.0.2.2", "127.0.0.1", "localhost", "[::1]", "::1")

  fun validatedBaseUrl(value: String) = validateAfterchimeBaseUrl(value) { host ->
    host in loopbackHosts
  }
}
