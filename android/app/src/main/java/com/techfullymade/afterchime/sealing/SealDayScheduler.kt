package com.techfullymade.afterchime.sealing

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal data class SealDayWorkPlan(
  val uniqueName: String,
  val workerClass: Class<out androidx.work.ListenableWorker>,
  val oneTimePolicy: ExistingWorkPolicy? = null,
  val periodicIntervalHours: Long? = null,
  val periodicPolicy: ExistingPeriodicWorkPolicy? = null,
)

internal fun interface SealDayWorkEnqueuer {
  fun enqueue(plan: SealDayWorkPlan)
}

/** Schedules local sealing catch-up and bounded periodic reconciliation. */
internal class SealDayScheduler(
  private val enqueuer: SealDayWorkEnqueuer,
) {
  constructor(workManager: WorkManager) : this(SealDayWorkEnqueuer { plan ->
    if (plan.periodicIntervalHours == null) {
      workManager.enqueueUniqueWork(
        plan.uniqueName,
        requireNotNull(plan.oneTimePolicy),
        OneTimeWorkRequestBuilder<SealDayWorker>().build(),
      )
    } else {
      workManager.enqueueUniquePeriodicWork(
        plan.uniqueName,
        requireNotNull(plan.periodicPolicy),
        PeriodicWorkRequestBuilder<SealDayWorker>(plan.periodicIntervalHours, TimeUnit.HOURS).build(),
      )
    }
  })

  fun schedule() {
    enqueuer.enqueue(
      SealDayWorkPlan(
        uniqueName = CATCH_UP_WORK_NAME,
        workerClass = SealDayWorker::class.java,
        oneTimePolicy = ExistingWorkPolicy.KEEP,
      ),
    )
    enqueuer.enqueue(
      SealDayWorkPlan(
        uniqueName = PERIODIC_WORK_NAME,
        workerClass = SealDayWorker::class.java,
        periodicIntervalHours = PERIODIC_INTERVAL_HOURS,
        periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
      ),
    )
  }

  private companion object {
    const val CATCH_UP_WORK_NAME = "seal-day-catch-up"
    const val PERIODIC_WORK_NAME = "seal-day-periodic"
    const val PERIODIC_INTERVAL_HOURS = 24L
  }
}
