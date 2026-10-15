package com.techfullymade.afterchime.sync

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnlineSyncWorkerTest {
  @Test
  fun `missing runtime retries without invoking work`() = runBlocking {
    assertEquals(OnlineWorkerDecision.RETRY, onlineWorkerDecision(null))
  }

  @Test
  fun `coordinator outcomes map exactly to worker decisions`() = runBlocking {
    assertEquals(
      OnlineWorkerDecision.SUCCESS,
      onlineWorkerDecision(OnlineSyncRunner { OnlineSyncOutcome.SUCCESS }),
    )
    assertEquals(
      OnlineWorkerDecision.RETRY,
      onlineWorkerDecision(OnlineSyncRunner { OnlineSyncOutcome.RETRY }),
    )
    assertEquals(
      OnlineWorkerDecision.FAILURE,
      onlineWorkerDecision(OnlineSyncRunner { OnlineSyncOutcome.FAILURE }),
    )
  }

  @Test
  fun `unexpected coordinator failure retries but cancellation propagates`() {
    runBlocking {
      assertEquals(
        OnlineWorkerDecision.RETRY,
        onlineWorkerDecision(OnlineSyncRunner { error("transient boundary failure") }),
      )
    }
    assertThrows(CancellationException::class.java) {
      runBlocking {
        onlineWorkerDecision(OnlineSyncRunner { throw CancellationException("cancelled") })
      }
    }
  }
}
