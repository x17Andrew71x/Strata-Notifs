package com.techfullymade.afterchime.generation

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt

/**
 * Pure v1 specimen generation over only the approved local day summary.
 *
 * The caller supplies the device-local secret; it is used only to derive a date-bound seed and is
 * never represented in the result. The canonical summary deliberately contains aggregate shape only.
 */
object GeneratorV1 {
  const val VERSION = 1

  fun generate(
    summary: DaySummary,
    localSecret: ByteArray,
  ): GenerationResult {
    require(localSecret.size == LOCAL_SECRET_BYTES) { "generator secret must be exactly 32 bytes" }
    if (summary.observationCompleteness == ObservationCompleteness.UNOBSERVED) {
      return GenerationResult.Unobserved
    }

    val entropy = entropy(summary, localSecret)
    return GenerationResult.Generated(
      Specimen(
        generatorVersion = VERSION,
        anchoredLocalDate = summary.localDate,
        family = familyFor(summary),
        tier = Tier.fromRoll(word(entropy, 0) % TIER_ROLL_BOUND),
        visual = visualFor(summary, entropy),
      ),
    )
  }

  private fun entropy(
    summary: DaySummary,
    localSecret: ByteArray,
  ): ByteArray {
    val dailySeed = hmac(
      key = localSecret,
      payload = DAILY_SEED_DOMAIN + summary.localDate.toString().toByteArray(UTF_8),
    )
    return hmac(
      key = dailySeed,
      payload = canonicalSummary(summary).toByteArray(UTF_8),
    )
  }

  private fun familyFor(summary: DaySummary): Family = when {
    summary.trueNotificationCount == 0 -> Family.TRACE_PLATE
    summary.trueNotificationCount >= 6 && summary.categoryCounts.size == 1 && summary.sourceDiversity <= 1 -> Family.COPROLITE
    summary.longestObservedQuietIntervalMinutes.toLong() * PERCENT >= summary.dayDurationMinutes.toLong() * GEODE_QUIET_PERCENT &&
      summary.dayNotificationCount > 0 && summary.nightNotificationCount > 0 -> Family.GEODE
    summary.longestObservedQuietIntervalMinutes.toLong() * PERCENT >= summary.dayDurationMinutes.toLong() * AMBER_QUIET_PERCENT -> Family.AMBER
    summary.trueNotificationCount <= 2 && summary.maxEventsInFifteenMinuteWindow == summary.trueNotificationCount -> Family.SHARK_TOOTH
    summary.maxEventsInFifteenMinuteWindow >= TRACKWAY_BURST_MINIMUM -> Family.TRACKWAY
    summary.categoryCounts.size >= FERN_CATEGORY_MINIMUM && summary.sourceDiversity >= FERN_SOURCE_MINIMUM -> Family.FERN_IMPRINT
    summary.hourlyCounts.size >= TRILOBITE_HOUR_MINIMUM && summary.peakHourlyCount <= TRILOBITE_PEAK_MAXIMUM -> Family.TRILOBITE
    summary.trueNotificationCount >= AMMONITE_COUNT_MINIMUM && summary.peakHourlyCount <= AMMONITE_PEAK_MAXIMUM -> Family.AMMONITE
    else -> Family.METEORITE_FRAGMENT
  }

  private fun visualFor(
    summary: DaySummary,
    entropy: ByteArray,
  ): VisualParameters = VisualParameters(
    hueDegrees = word(entropy, 1) % HUE_BOUND,
    strataCount = minOf(
      MAX_STRATA_COUNT,
      MIN_STRATA_COUNT + minOf(MAX_PRESSURE_STRATA, sqrt(summary.gamePressure.toDouble()).toInt()) + word(entropy, 2) % STRATA_VARIATION,
    ),
    inclusionDensityPercent = minOf(
      PERCENT,
      ((summary.trueNotificationCount.toLong() * PERCENT) / (summary.trueNotificationCount.toLong() + DENSITY_SOFTENER)).toInt() + word(entropy, 3) % DENSITY_VARIATION,
    ),
    reliefPercent = MIN_RELIEF_PERCENT + word(entropy, 4) % RELIEF_VARIATION,
    rotationDegrees = word(entropy, 5) % HUE_BOUND,
  )

  private fun canonicalSummary(summary: DaySummary): String = listOf(
    summary.localDate.toString(),
    summary.trueNotificationCount.toString(),
    summary.gamePressure.toString(),
    summary.hourlyCounts.toSortedMap().entries.joinToString(",") { (hour, count) -> "$hour:$count" },
    summary.categoryCounts.entries.sortedBy { (category, _) -> category.name }.joinToString(",") { (category, count) -> "${category.name}:$count" },
    summary.sourceDiversity.toString(),
    summary.dayDurationMinutes.toString(),
    summary.longestObservedQuietIntervalMinutes.toString(),
    summary.peakHourlyCount.toString(),
    summary.maxEventsInFifteenMinuteWindow.toString(),
    summary.dayNotificationCount.toString(),
    summary.nightNotificationCount.toString(),
  ).joinToString("\u0000")

  private fun hmac(
    key: ByteArray,
    payload: ByteArray,
  ): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(payload)
  }

  private fun word(
    entropy: ByteArray,
    index: Int,
  ): Int {
    val offset = index * WORD_BYTES
    return ((entropy[offset].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or (entropy[offset + 1].toInt() and BYTE_MASK)
  }

  private const val LOCAL_SECRET_BYTES = 32
  private const val WORD_BYTES = 2
  private const val BYTE_MASK = 0xff
  private const val TIER_ROLL_BOUND = 1_000
  private const val HUE_BOUND = 360
  private const val PERCENT = 100
  private const val MIN_STRATA_COUNT = 4
  private const val MAX_STRATA_COUNT = 16
  private const val MAX_PRESSURE_STRATA = 10
  private const val STRATA_VARIATION = 3
  private const val DENSITY_SOFTENER = 20
  private const val DENSITY_VARIATION = 10
  private const val MIN_RELIEF_PERCENT = 30
  private const val RELIEF_VARIATION = 71
  private const val GEODE_QUIET_PERCENT = 55
  private const val AMBER_QUIET_PERCENT = 40
  private const val TRACKWAY_BURST_MINIMUM = 4
  private const val FERN_CATEGORY_MINIMUM = 4
  private const val FERN_SOURCE_MINIMUM = 4
  private const val TRILOBITE_HOUR_MINIMUM = 5
  private const val TRILOBITE_PEAK_MAXIMUM = 2
  private const val AMMONITE_COUNT_MINIMUM = 8
  private const val AMMONITE_PEAK_MAXIMUM = 3
  private val DAILY_SEED_DOMAIN = "afterchime:generator:v1\u0000".toByteArray(UTF_8)
}
