package com.techfullymade.afterchime.network

/** Explicit factory only: constructing it has no network or process-wide side effects. */
object NetworkModule {
  fun create(baseUrl: String): AfterchimeApi = AfterchimeApi(baseUrl)
}
