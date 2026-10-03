package com.techfullymade.afterchime.generation

import java.time.LocalDate

/** Safe, renderer-neutral output of one versioned deterministic generation pass. */
data class Specimen(
  val generatorVersion: Int,
  val anchoredLocalDate: LocalDate,
  val family: Family,
  val tier: Tier,
  val visual: VisualParameters,
) {
  init {
    require(generatorVersion > 0)
  }
}

/** Compact visual inputs; no notification payload or source identity survives generation. */
data class VisualParameters(
  val hueDegrees: Int,
  val strataCount: Int,
  val inclusionDensityPercent: Int,
  val reliefPercent: Int,
  val rotationDegrees: Int,
) {
  init {
    require(hueDegrees in 0..359)
    require(strataCount in 4..16)
    require(inclusionDensityPercent in 0..100)
    require(reliefPercent in 30..100)
    require(rotationDegrees in 0..359)
  }
}

sealed interface GenerationResult {
  data class Generated(
    val specimen: Specimen,
  ) : GenerationResult

  /** A missing observation interval never turns into a synthetic quiet-day specimen. */
  data object Unobserved : GenerationResult
}
