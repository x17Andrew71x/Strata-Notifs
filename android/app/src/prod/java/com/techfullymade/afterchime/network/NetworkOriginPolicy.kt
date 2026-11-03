package com.techfullymade.afterchime.network

internal object NetworkOriginPolicy {
  fun validatedBaseUrl(value: String) = validateAfterchimeBaseUrl(value) { false }
}
