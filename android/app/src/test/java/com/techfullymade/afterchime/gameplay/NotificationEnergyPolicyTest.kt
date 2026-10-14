package com.techfullymade.afterchime.gameplay

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationEnergyPolicyTest {
  @Test
  fun `four captured inputs produce three accepted objects at the five minute boundary`() {
    val captured = listOf(
      Input(id = "source-a-first", source = "source-a", occurredAtEpochMillis = 0L),
      Input(id = "source-a-minute-one", source = "source-a", occurredAtEpochMillis = 60_000L),
      Input(id = "source-b-minute-one", source = "source-b", occurredAtEpochMillis = 60_000L),
      Input(id = "source-a-minute-five", source = "source-a", occurredAtEpochMillis = 300_000L),
    )

    val accepted = NotificationEnergyPolicy.accepted(
      inputs = captured,
      sourceKey = Input::source,
      occurredAtEpochMillis = Input::occurredAtEpochMillis,
    )

    assertEquals(
      listOf(captured[0], captured[2], captured[3]),
      accepted,
    )
  }

  @Test
  fun `eligibility is evaluated in event time order rather than input order`() {
    val first = Input(id = "first", source = "source", occurredAtEpochMillis = 0L)
    val blocked = Input(id = "blocked", source = "source", occurredAtEpochMillis = 299_999L)
    val boundary = Input(id = "boundary", source = "source", occurredAtEpochMillis = 300_000L)

    val accepted = NotificationEnergyPolicy.accepted(
      inputs = listOf(boundary, blocked, first),
      sourceKey = Input::source,
      occurredAtEpochMillis = Input::occurredAtEpochMillis,
    )

    assertEquals(listOf(first, boundary), accepted)
  }

  private data class Input(
    val id: String,
    val source: String,
    val occurredAtEpochMillis: Long,
  )
}
