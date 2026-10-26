package com.techfullymade.afterchime.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExhibitsContractTest {
  @Test
  fun `strict exhibits state exposes percentage and private eligibility without raw global counters`() {
    val state = JSONObject()
      .put("status", "ready")
      .put("featured", exhibit("returning-light-2027", 5646, 560, 900, 47))
      .put("archive", exhibit("long-night-2026", 1824, 240, 1500, null))
      .put(
        "past",
        JSONArray().put(
          JSONObject()
            .put("id", "monsoon-choir-2026")
            .put("title", "Monsoon Choir")
            .put("seasonLabel", "Summer 2026")
            .put("imagePath", "/exhibits/monsoon-choir-placeholder-c754a2db5e27.webp")
            .put("unlocked", true)
            .put("lockedReason", JSONObject.NULL),
        ),
      )
      .put("todayContributedUnits", 0)
      .put("dailyContributionCap", 30)
      .put("version", 1)

    val parsed = ApiModels.parseExhibitsState(state.toString())

    assertEquals(5646, parsed.featured?.progressBasisPoints)
    assertEquals(560, parsed.featured?.personalContribution)
    assertEquals(30, parsed.dailyContributionCap)
    state.put("targetUnits", 5000)
    assertThrows(IllegalArgumentException::class.java) {
      ApiModels.parseExhibitsState(state.toString())
    }
  }

  @Test
  fun `contribution encoder accepts only a bounded local-date amount`() {
    assertEquals(
      setOf("local_date", "units"),
      JSONObject(ApiModels.encodeExhibitContribution("2026-10-26", 18)).keys().asSequence().toSet(),
    )
    assertThrows(IllegalArgumentException::class.java) {
      ApiModels.encodeExhibitContribution("2026-10-26", 0)
    }
  }

  private fun exhibit(
    id: String,
    progress: Int,
    personal: Int,
    minimum: Int,
    days: Int?,
  ) = JSONObject()
    .put("id", id)
    .put("title", if (id.startsWith("returning")) "The Returning Light" else "The Long Night")
    .put("seasonLabel", if (id.startsWith("returning")) "Spring 2027" else "Winter 2026")
    .put(
      "imagePath",
      if (id.startsWith("returning")) {
        "/exhibits/returning-light-placeholder-9b0223afcfc7.webp"
      } else {
        "/exhibits/long-night-placeholder-65aa588c66bf.webp"
      },
    )
    .put("progressBasisPoints", progress)
    .put("personalContribution", personal)
    .put("personalMinimum", minimum)
    .put("daysRemaining", days ?: JSONObject.NULL)
    .put("communityComplete", false)
    .put("qualified", false)
    .put("unlocked", false)
    .put("closed", false)
}
