package com.techfullymade.afterchime.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import com.techfullymade.afterchime.onlineDeviceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSyncSchedulerTest {
  @Test
  fun `full schedule emits one replacing immediate plan and one kept periodic plan`() {
    val plans = mutableListOf<OnlineSyncWorkPlan>()
    OnlineSyncScheduler(OnlineSyncWorkEnqueuer(plans::add)).schedule()

    assertEquals(2, plans.size)
    val immediate = plans[0]
    val periodic = plans[1]
    assertEquals(ExistingWorkPolicy.REPLACE, immediate.oneTimePolicy)
    assertEquals(null, immediate.periodicPolicy)
    assertEquals(null, immediate.periodicIntervalHours)
    assertTrue(immediate.requiresNetwork)
    assertEquals(ExistingPeriodicWorkPolicy.KEEP, periodic.periodicPolicy)
    assertEquals(null, periodic.oneTimePolicy)
    assertEquals(12L, periodic.periodicIntervalHours)
    assertTrue(periodic.requiresNetwork)
    assertNotEquals(immediate.uniqueName, periodic.uniqueName)
  }

  @Test
  fun `preference changes enqueue only immediate synchronization`() {
    val plans = mutableListOf<OnlineSyncWorkPlan>()
    OnlineSyncScheduler(OnlineSyncWorkEnqueuer(plans::add)).scheduleImmediate()

    assertEquals(listOf(OnlineSyncScheduler.IMMEDIATE_PLAN), plans)
  }

  @Test
  fun `registration device class is bounded to phone or tablet`() {
    assertEquals("phone", onlineDeviceClass(0))
    assertEquals("phone", onlineDeviceClass(599))
    assertEquals("tablet", onlineDeviceClass(600))
    assertEquals("tablet", onlineDeviceClass(1_200))
  }
}
