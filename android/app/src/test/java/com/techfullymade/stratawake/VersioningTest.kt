package com.techfullymade.stratawake

import org.junit.Assert.assertEquals
import org.junit.Test

class VersioningTest {
  @Test
  fun `semver maps to monotonic version code`() {
    assertEquals(1000, versionCode(0, 1, 0))
    assertEquals(10_012, versionCode(0, 10, 12))
    assertEquals(1_002_003, versionCode(1, 2, 3))
  }

  private fun versionCode(major: Int, minor: Int, patch: Int): Int {
    require(major in 0..999)
    require(minor in 0..999)
    require(patch in 0..999)
    return major * 1_000_000 + minor * 1_000 + patch
  }
}
