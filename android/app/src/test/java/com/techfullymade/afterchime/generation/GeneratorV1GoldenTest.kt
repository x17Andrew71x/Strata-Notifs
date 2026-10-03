package com.techfullymade.afterchime.generation

import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratorV1GoldenTest {
  @Test
  fun `generator v1 matches committed privacy-safe golden vectors`() {
    goldenFixtures().forEach { fixture ->
      val vector = JSONObject(fixture.readText())
      val actual = GeneratorV1.generate(
        summary = summary(vector.getJSONObject("summary")),
        localSecret = hex(vector.getString("local_secret_hex")),
      )
      val expected = vector.getJSONObject("expected")

      when (expected.getString("status")) {
        "generated" -> {
          val specimen = generated(actual)
          assertEquals(GeneratorV1.VERSION, specimen.generatorVersion)
          assertEquals(Family.valueOf(expected.getString("family")), specimen.family)
          assertEquals(Tier.valueOf(expected.getString("tier")), specimen.tier)
          val visual = expected.getJSONObject("visual")
          assertEquals(visual.getInt("hue_degrees"), specimen.visual.hueDegrees)
          assertEquals(visual.getInt("strata_count"), specimen.visual.strataCount)
          assertEquals(visual.getInt("inclusion_density_percent"), specimen.visual.inclusionDensityPercent)
          assertEquals(visual.getInt("relief_percent"), specimen.visual.reliefPercent)
          assertEquals(visual.getInt("rotation_degrees"), specimen.visual.rotationDegrees)
        }
        "unobserved" -> assertEquals(GenerationResult.Unobserved, actual)
        else -> throw AssertionError("unknown expected generator status in ${fixture.name}")
      }
    }
  }

  @Test
  fun `every observed pattern class can reach every deterministic tier`() {
    observedPatterns().forEach { (family, summary) ->
      val specimens = (0 until 2_048).map { index ->
        generated(GeneratorV1.generate(summary, secretFor(index)))
      }

      assertEquals(family, specimens.first().family)
      assertEquals(Tier.entries.toSet(), specimens.map(Specimen::tier).toSet())
    }
  }

  @Test
  fun `more notifications are not monotonically rarer`() {
    val sparse = summary(
      localDate = "2026-10-10",
      trueNotificationCount = 2,
      gamePressure = 2,
      hourlyCounts = mapOf(10 to 2),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 2),
      sourceDiversity = 2,
      longestObservedQuietIntervalMinutes = 600,
      peakHourlyCount = 2,
      maxEventsInFifteenMinuteWindow = 2,
      dayNotificationCount = 2,
    )
    val noisy = summary(
      localDate = "2026-10-10",
      trueNotificationCount = 80,
      gamePressure = 80,
      hourlyCounts = mapOf(10 to 20, 11 to 20, 12 to 20, 13 to 20),
      categoryCounts = mapOf(
        CoarseNotificationCategory.MESSAGE to 40,
        CoarseNotificationCategory.SOCIAL to 40,
      ),
      sourceDiversity = 8,
      longestObservedQuietIntervalMinutes = 300,
      peakHourlyCount = 20,
      maxEventsInFifteenMinuteWindow = 20,
      dayNotificationCount = 80,
    )
    val fixedSecret = ByteArray(32) { 3 }

    val sparseTier = generated(GeneratorV1.generate(sparse, fixedSecret)).tier
    val noisyTier = generated(GeneratorV1.generate(noisy, fixedSecret)).tier

    assertTrue("higher count unexpectedly produced a strictly rarer tier", sparseTier.ordinal > noisyTier.ordinal)
  }

  @Test
  fun `unobserved input cannot become a quiet trace specimen`() {
    val unobserved = summary(
      localDate = "2026-10-11",
      observationCompleteness = ObservationCompleteness.UNOBSERVED,
      trueNotificationCount = 0,
      gamePressure = 0,
      longestObservedQuietIntervalMinutes = 0,
    )

    assertEquals(GenerationResult.Unobserved, GeneratorV1.generate(unobserved, ByteArray(32)))
  }

  private fun observedPatterns(): List<Pair<Family, DaySummary>> = listOf(
    Family.TRACE_PLATE to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 0,
      gamePressure = 0,
      longestObservedQuietIntervalMinutes = 1_440,
    ),
    Family.COPROLITE to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 6,
      gamePressure = 6,
      hourlyCounts = mapOf(10 to 6),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 6),
      sourceDiversity = 1,
      longestObservedQuietIntervalMinutes = 300,
      peakHourlyCount = 6,
      maxEventsInFifteenMinuteWindow = 6,
      dayNotificationCount = 6,
    ),
    Family.GEODE to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 4,
      gamePressure = 4,
      hourlyCounts = mapOf(2 to 2, 11 to 2),
      categoryCounts = mapOf(CoarseNotificationCategory.EMAIL to 2, CoarseNotificationCategory.MESSAGE to 2),
      sourceDiversity = 2,
      longestObservedQuietIntervalMinutes = 800,
      peakHourlyCount = 2,
      maxEventsInFifteenMinuteWindow = 2,
      dayNotificationCount = 2,
      nightNotificationCount = 2,
    ),
    Family.AMBER to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 3,
      gamePressure = 3,
      hourlyCounts = mapOf(8 to 1, 9 to 1, 10 to 1),
      categoryCounts = mapOf(CoarseNotificationCategory.EMAIL to 2, CoarseNotificationCategory.MESSAGE to 1),
      sourceDiversity = 2,
      longestObservedQuietIntervalMinutes = 700,
      peakHourlyCount = 1,
      maxEventsInFifteenMinuteWindow = 1,
      dayNotificationCount = 3,
    ),
    Family.SHARK_TOOTH to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 1,
      gamePressure = 1,
      hourlyCounts = mapOf(10 to 1),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 1),
      sourceDiversity = 1,
      longestObservedQuietIntervalMinutes = 100,
      peakHourlyCount = 1,
      maxEventsInFifteenMinuteWindow = 1,
      dayNotificationCount = 1,
    ),
    Family.TRACKWAY to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 4,
      gamePressure = 4,
      hourlyCounts = mapOf(10 to 4),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 2, CoarseNotificationCategory.SOCIAL to 2),
      sourceDiversity = 2,
      longestObservedQuietIntervalMinutes = 200,
      peakHourlyCount = 4,
      maxEventsInFifteenMinuteWindow = 4,
      dayNotificationCount = 4,
    ),
    Family.FERN_IMPRINT to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 4,
      gamePressure = 4,
      hourlyCounts = mapOf(1 to 1, 8 to 1, 12 to 1, 23 to 1),
      categoryCounts = mapOf(
        CoarseNotificationCategory.ALARM to 1,
        CoarseNotificationCategory.EMAIL to 1,
        CoarseNotificationCategory.EVENT to 1,
        CoarseNotificationCategory.MESSAGE to 1,
      ),
      sourceDiversity = 4,
      longestObservedQuietIntervalMinutes = 200,
      peakHourlyCount = 1,
      maxEventsInFifteenMinuteWindow = 1,
      dayNotificationCount = 2,
      nightNotificationCount = 2,
    ),
    Family.TRILOBITE to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 5,
      gamePressure = 5,
      hourlyCounts = mapOf(7 to 1, 9 to 1, 11 to 1, 13 to 1, 15 to 1),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 5),
      sourceDiversity = 3,
      longestObservedQuietIntervalMinutes = 200,
      peakHourlyCount = 1,
      maxEventsInFifteenMinuteWindow = 1,
      dayNotificationCount = 5,
    ),
    Family.AMMONITE to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 8,
      gamePressure = 8,
      hourlyCounts = mapOf(7 to 2, 9 to 2, 11 to 2, 13 to 2),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 4, CoarseNotificationCategory.SOCIAL to 4),
      sourceDiversity = 3,
      longestObservedQuietIntervalMinutes = 200,
      peakHourlyCount = 2,
      maxEventsInFifteenMinuteWindow = 2,
      dayNotificationCount = 8,
    ),
    Family.METEORITE_FRAGMENT to summary(
      localDate = "2026-10-01",
      trueNotificationCount = 5,
      gamePressure = 5,
      hourlyCounts = mapOf(10 to 3, 11 to 2),
      categoryCounts = mapOf(CoarseNotificationCategory.MESSAGE to 3, CoarseNotificationCategory.SOCIAL to 2),
      sourceDiversity = 2,
      longestObservedQuietIntervalMinutes = 100,
      peakHourlyCount = 3,
      maxEventsInFifteenMinuteWindow = 2,
      dayNotificationCount = 5,
    ),
  )

  private fun goldenFixtures(): List<File> {
    val workingDirectory = System.getProperty("user.dir") ?: throw AssertionError("working directory is unavailable")
    var candidate = File(workingDirectory).canonicalFile
    while (true) {
      val directory = File(candidate, "contracts/fixtures/generator-v1")
      if (directory.isDirectory) {
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
          ?.sortedBy(File::getName)
          ?: throw AssertionError("unable to list generator fixtures")
      }
      candidate = candidate.parentFile ?: throw AssertionError("generator fixture directory was not found")
    }
  }

  private fun summary(json: JSONObject): DaySummary = summary(
    localDate = json.getString("local_date"),
    observationCompleteness = ObservationCompleteness.valueOf(json.getString("observation_completeness")),
    trueNotificationCount = json.getInt("true_notification_count"),
    gamePressure = json.getInt("game_pressure"),
    hourlyCounts = json.getJSONObject("hourly_counts").intEntries().associate { (hour, count) -> hour.toInt() to count },
    categoryCounts = json.getJSONObject("category_counts").intEntries().associate { (category, count) -> CoarseNotificationCategory.valueOf(category) to count },
    sourceDiversity = json.getInt("source_diversity"),
    dayDurationMinutes = json.getInt("day_duration_minutes"),
    longestObservedQuietIntervalMinutes = json.getInt("longest_observed_quiet_interval_minutes"),
    peakHourlyCount = json.getInt("peak_hourly_count"),
    maxEventsInFifteenMinuteWindow = json.getInt("max_events_in_fifteen_minute_window"),
    dayNotificationCount = json.getInt("day_notification_count"),
    nightNotificationCount = json.getInt("night_notification_count"),
  )

  private fun summary(
    localDate: String,
    observationCompleteness: ObservationCompleteness = ObservationCompleteness.OBSERVED,
    trueNotificationCount: Int,
    gamePressure: Int,
    hourlyCounts: Map<Int, Int> = emptyMap(),
    categoryCounts: Map<CoarseNotificationCategory, Int> = emptyMap(),
    sourceDiversity: Int = 0,
    dayDurationMinutes: Int = 1_440,
    longestObservedQuietIntervalMinutes: Int,
    peakHourlyCount: Int = 0,
    maxEventsInFifteenMinuteWindow: Int = 0,
    dayNotificationCount: Int = 0,
    nightNotificationCount: Int = 0,
  ): DaySummary = DaySummary(
    localDate = LocalDate.parse(localDate),
    observationCompleteness = observationCompleteness,
    trueNotificationCount = trueNotificationCount,
    gamePressure = gamePressure,
    hourlyCounts = hourlyCounts,
    categoryCounts = categoryCounts,
    sourceDiversity = sourceDiversity,
    dayDurationMinutes = dayDurationMinutes,
    longestObservedQuietIntervalMinutes = longestObservedQuietIntervalMinutes,
    peakHourlyCount = peakHourlyCount,
    maxEventsInFifteenMinuteWindow = maxEventsInFifteenMinuteWindow,
    dayNotificationCount = dayNotificationCount,
    nightNotificationCount = nightNotificationCount,
  )

  private fun JSONObject.intEntries(): List<Pair<String, Int>> {
    val keys = keys()
    return buildList {
      while (keys.hasNext()) {
        val key = keys.next()
        add(key to getInt(key))
      }
    }
  }

  private fun generated(result: GenerationResult): Specimen = when (result) {
    is GenerationResult.Generated -> result.specimen
    GenerationResult.Unobserved -> throw AssertionError("expected a generated specimen")
  }

  private fun secretFor(index: Int): ByteArray = ByteArray(32) { position ->
    ((index ushr ((position % 4) * 8)) xor (position * 31)).toByte()
  }

  private fun hex(value: String): ByteArray {
    require(value.matches(Regex("[0-9a-f]{64}")))
    return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
  }
}
