package com.techfullymade.afterchime.sealing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * WorkManager boundary for scheduled and launch catch-up sealing.
 *
 * Task 7 installs the Keystore-backed runtime dependency. Until then, retrying is safer than
 * generating a specimen with temporary or process-local secret material.
 */
class SealDayWorker(
  appContext: Context,
  workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
  override suspend fun doWork(): Result {
    val useCase = SealDayRuntimeRegistry.useCase ?: return Result.retry()
    return try {
      useCase.sealEligibleDays()
      Result.success()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      Result.retry()
    }
  }
}

/** Process-local dependency registry; it intentionally never stores the raw secret itself. */
object SealDayRuntimeRegistry {
  @Volatile
  var useCase: SealDayUseCase? = null
}
