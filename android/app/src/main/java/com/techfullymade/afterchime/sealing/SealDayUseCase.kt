package com.techfullymade.afterchime.sealing

import com.techfullymade.afterchime.capture.ReducedNotification
import com.techfullymade.afterchime.data.local.entity.DaySummaryEntity
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import com.techfullymade.afterchime.data.local.entity.SpecimenEntity
import com.techfullymade.afterchime.data.local.entity.SpecimenOutputEntity
import com.techfullymade.afterchime.generation.DaySummarizer
import com.techfullymade.afterchime.generation.GenerationResult
import com.techfullymade.afterchime.generation.GeneratorV1
import com.techfullymade.afterchime.generation.ObservationCompleteness
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Supplies the device-local generator secret without allowing it into persisted sealing state. */
fun interface LocalSecretProvider {
  fun load(): ByteArray
}

/** Approved inputs for one anchored local day. Notification objects are already privacy-reduced. */
data class SealDayInputs(
  val notifications: List<ReducedNotification>,
  val latestListenerAccessState: ListenerAccessState?,
  val accessWasInterrupted: Boolean,
)

/** The immutable, renderer-neutral output associated with a persisted specimen identity. */
data class SealedSpecimen(
  val record: SpecimenEntity,
  val output: SpecimenOutputEntity,
) {
  init {
    require(record.specimenId == output.specimenId)
  }
}

/** A complete, transaction-bound local sealing write. */
data class SealedDay(
  val localDate: LocalDate,
  val summary: DaySummaryEntity,
  val specimen: SealedSpecimen?,
) {
  init {
    require(summary.localDate == localDate.toString())
    require(specimen == null || specimen.record.anchoredLocalDate == localDate.toString())
  }
}

sealed interface SealDayPersistence {
  data object Persisted : SealDayPersistence

  data object AlreadySealed : SealDayPersistence
}

/**
 * Storage boundary for day sealing. Implementations must make [persist] one atomic transaction.
 */
interface SealDayStore {
  suspend fun candidateLocalDatesBefore(exclusiveDate: LocalDate): List<LocalDate>

  suspend fun inputsFor(localDate: LocalDate): SealDayInputs

  suspend fun persist(sealedDay: SealedDay): SealDayPersistence
}

sealed interface SealDayOutcome {
  val localDate: LocalDate

  data class Sealed(
    override val localDate: LocalDate,
    val observationState: ObservationState,
    val specimenId: String?,
  ) : SealDayOutcome

  data class AlreadySealed(
    override val localDate: LocalDate,
  ) : SealDayOutcome
}

/**
 * Seals every known completed local day in deterministic order.
 *
 * A day is observed only when its listener evidence is uninterrupted and active. Any missing,
 * disconnected, or revoked evidence remains an unobserved non-specimen day. Concurrent workers are
 * harmless because the final write is guarded by the local date's unique summary/specimen keys.
 */
class SealDayUseCase(
  private val store: SealDayStore,
  private val localSecretProvider: LocalSecretProvider,
  private val clock: Clock,
  private val timeZone: ZoneId,
  private val generator: (com.techfullymade.afterchime.generation.DaySummary, ByteArray) -> GenerationResult = GeneratorV1::generate,
) {
  suspend fun sealEligibleDays(): List<SealDayOutcome> {
    val exclusiveDate = Instant.now(clock).atZone(timeZone).toLocalDate()
    val candidateDates = store.candidateLocalDatesBefore(exclusiveDate)
      .asSequence()
      .filter { it < exclusiveDate }
      .distinct()
      .sorted()
      .toList()
    val outcomes = mutableListOf<SealDayOutcome>()
    for (localDate in candidateDates) {
      outcomes += seal(localDate)
    }
    return outcomes
  }

  private suspend fun seal(localDate: LocalDate): SealDayOutcome {
    val inputs = store.inputsFor(localDate)
    val observationCompleteness = inputs.observationCompleteness()
    val summary = DaySummarizer.summarize(
      localDate = localDate,
      timeZone = timeZone,
      observationCompleteness = observationCompleteness,
      notifications = inputs.notifications,
    )
    val createdAtEpochMillis = Instant.now(clock).toEpochMilli()
    val generated = if (observationCompleteness == ObservationCompleteness.OBSERVED) {
      generator(summary, localSecretProvider.load())
    } else {
      GenerationResult.Unobserved
    }
    val generatedSpecimen = (generated as? GenerationResult.Generated)?.specimen
    val sealedDay = SealedDay(
      localDate = localDate,
      summary = DaySummaryEntity(
        localDate = localDate.toString(),
        timezoneOffsetMinutes = localDate.atStartOfDay(timeZone).offset.totalSeconds / SECONDS_PER_MINUTE,
        observationState = observationCompleteness.toEntityState(),
        generatorVersion = generatedSpecimen?.generatorVersion ?: GeneratorV1.VERSION,
        createdAtEpochMillis = createdAtEpochMillis,
      ),
      specimen = generatedSpecimen?.let { generated ->
        val specimenId = specimenIdFor(generated.anchoredLocalDate, generated.generatorVersion)
        SealedSpecimen(
          record = SpecimenEntity(
            specimenId = specimenId,
            anchoredLocalDate = generated.anchoredLocalDate.toString(),
            generatorVersion = generated.generatorVersion,
            createdAtEpochMillis = createdAtEpochMillis,
            revealedAtEpochMillis = null,
          ),
          output = SpecimenOutputEntity(
            specimenId = specimenId,
            family = generated.family,
            tier = generated.tier,
            hueDegrees = generated.visual.hueDegrees,
            strataCount = generated.visual.strataCount,
            inclusionDensityPercent = generated.visual.inclusionDensityPercent,
            reliefPercent = generated.visual.reliefPercent,
            rotationDegrees = generated.visual.rotationDegrees,
          ),
        )
      },
    )

    return when (store.persist(sealedDay)) {
      SealDayPersistence.Persisted -> SealDayOutcome.Sealed(
        localDate = localDate,
        observationState = sealedDay.summary.observationState,
        specimenId = sealedDay.specimen?.record?.specimenId,
      )

      SealDayPersistence.AlreadySealed -> SealDayOutcome.AlreadySealed(localDate)
    }
  }

  private fun SealDayInputs.observationCompleteness(): ObservationCompleteness =
    if (latestListenerAccessState == ListenerAccessState.ACTIVE && !accessWasInterrupted) {
      ObservationCompleteness.OBSERVED
    } else {
      ObservationCompleteness.UNOBSERVED
    }

  private fun ObservationCompleteness.toEntityState(): ObservationState =
    when (this) {
      ObservationCompleteness.OBSERVED -> ObservationState.OBSERVED
      ObservationCompleteness.UNOBSERVED -> ObservationState.UNOBSERVED
    }

  private fun specimenIdFor(
    localDate: LocalDate,
    generatorVersion: Int,
  ): String = MessageDigest.getInstance("SHA-256")
    .digest("$SPECIMEN_ID_DOMAIN\u0000$localDate\u0000$generatorVersion".toByteArray(UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

  private companion object {
    const val SPECIMEN_ID_DOMAIN = "afterchime:sealed-specimen"
    const val SECONDS_PER_MINUTE = 60
    const val BYTE_MASK = 0xff
  }
}
