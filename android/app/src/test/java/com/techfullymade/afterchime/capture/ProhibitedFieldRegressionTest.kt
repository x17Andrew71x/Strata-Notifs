package com.techfullymade.afterchime.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ProhibitedFieldRegressionTest {
  @Test
  fun `reducer source never inspects content-bearing notification members`() {
    val source = reducerSource()
    val prohibitedMembers = listOf(
      ".extras",
      ".actions",
      ".tickerText",
      ".remoteInput",
      ".media",
      ".messages",
      ".key",
    )

    prohibitedMembers.forEach { member ->
      assertFalse("reducer must not inspect $member", source.contains(member))
    }
  }

  private fun reducerSource(): String {
    var candidate = File(System.getProperty("user.dir") ?: throw AssertionError("working directory is unavailable"))
      .canonicalFile
    while (true) {
      val source = File(
        candidate,
        "android/app/src/main/java/com/techfullymade/afterchime/capture/NotificationReducer.kt",
      )
      if (source.isFile) {
        return source.readText()
      }
      candidate = candidate.parentFile ?: throw AssertionError("NotificationReducer source was not found")
    }
  }
}
