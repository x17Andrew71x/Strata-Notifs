package com.techfullymade.afterchime.sync

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal data class OnlineSyncWorkPlan(
  val uniqueName: String,
  val oneTimePolicy: ExistingWorkPolicy? = null,
  val periodicPolicy: ExistingPeriodicWorkPolicy? = null,
  val periodicIntervalHours: Long? = null,
  val requiresNetwork: Boolean = true,
) {
  init {
    require((oneTimePolicy == null) xor (periodicPolicy == null))
    require((periodicIntervalHours == null) == (periodicPolicy == null))
    require(periodicIntervalHours == null || periodicIntervalHours >= 12)
    require(requiresNetwork)
  }
}

internal fun interface OnlineSyncWorkEnqueuer {
  fun enqueue(plan: OnlineSyncWorkPlan)
}

/** Schedules bounded immediate synchronization and a quiet periodic catch-up. */
internal class OnlineSyncScheduler(
  private val enqueuer: OnlineSyncWorkEnqueuer,
) {
  constructor(workManager: WorkManager) : this(OnlineSyncWorkEnqueuer { plan ->
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    if (plan.periodicIntervalHours == null) {
      workManager.enqueueUniqueWork(
        plan.uniqueName,
        requireNotNull(plan.oneTimePolicy),
        OneTimeWorkRequestBuilder<OnlineSyncWorker>().setConstraints(constraints).build(),
      )
    } else {
      workManager.enqueueUniquePeriodicWork(
        plan.uniqueName,
        requireNotNull(plan.periodicPolicy),
        PeriodicWorkRequestBuilder<OnlineSyncWorker>(plan.periodicIntervalHours, TimeUnit.HOURS)
          .setConstraints(constraints)
          .build(),
      )
    }
  })

  fun schedule() {
    scheduleImmediate()
    enqueuer.enqueue(PERIODIC_PLAN)
  }

  fun scheduleImmediate() {
    enqueuer.enqueue(IMMEDIATE_PLAN)
  }

  internal companion object {
    val IMMEDIATE_PLAN = OnlineSyncWorkPlan(
      uniqueName = "afterchime-online-sync-now-v1",
      oneTimePolicy = ExistingWorkPolicy.REPLACE,
    )
    val PERIODIC_PLAN = OnlineSyncWorkPlan(
      uniqueName = "afterchime-online-sync-periodic-v1",
      periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
      periodicIntervalHours = 12,
    )
  }
}
