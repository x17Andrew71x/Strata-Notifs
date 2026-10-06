package com.techfullymade.afterchime.sealing

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SealDaySchedulerTest {
  @Test
  fun `repeated scheduling requests the same unique worker policies and interval`() {
    val plans = mutableListOf<SealDayWorkPlan>()
    val scheduler = SealDayScheduler(SealDayWorkEnqueuer { plan -> plans.add(plan) })
    val catchUp = SealDayWorkPlan(
      uniqueName = "seal-day-catch-up",
      workerClass = SealDayWorker::class.java,
      oneTimePolicy = ExistingWorkPolicy.KEEP,
    )
    val periodic = SealDayWorkPlan(
      uniqueName = "seal-day-periodic",
      workerClass = SealDayWorker::class.java,
      periodicIntervalHours = 24L,
      periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
    )

    scheduler.schedule()
    scheduler.schedule()

    assertEquals(listOf(catchUp, periodic, catchUp, periodic), plans)
  }
}
