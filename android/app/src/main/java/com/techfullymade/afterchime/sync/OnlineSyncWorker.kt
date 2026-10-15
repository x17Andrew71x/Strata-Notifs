package com.techfullymade.afterchime.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object OnlineSyncRuntimeRegistry {
  @Volatile var runner: OnlineSyncRunner? = null
}

internal enum class OnlineWorkerDecision { SUCCESS, RETRY, FAILURE }

internal suspend fun onlineWorkerDecision(runner: OnlineSyncRunner?): OnlineWorkerDecision {
  runner ?: return OnlineWorkerDecision.RETRY
  return try {
    when (runner.sync()) {
      OnlineSyncOutcome.SUCCESS -> OnlineWorkerDecision.SUCCESS
      OnlineSyncOutcome.RETRY -> OnlineWorkerDecision.RETRY
      OnlineSyncOutcome.FAILURE -> OnlineWorkerDecision.FAILURE
    }
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (_: Exception) {
    OnlineWorkerDecision.RETRY
  }
}

/** Network-constrained WorkManager boundary; all blocking identity/HTTP work stays on IO. */
class OnlineSyncWorker(
  appContext: Context,
  workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    when (onlineWorkerDecision(OnlineSyncRuntimeRegistry.runner)) {
      OnlineWorkerDecision.SUCCESS -> Result.success()
      OnlineWorkerDecision.RETRY -> Result.retry()
      OnlineWorkerDecision.FAILURE -> Result.failure()
    }
  }
}
