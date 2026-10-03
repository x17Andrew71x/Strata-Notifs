package com.techfullymade.afterchime.domain

import androidx.room.withTransaction
import com.techfullymade.afterchime.capture.CoarseNotificationCategory
import com.techfullymade.afterchime.data.local.AfterchimeDatabase
import com.techfullymade.afterchime.data.local.entity.ListenerAccessState
import com.techfullymade.afterchime.data.local.entity.ObservationState
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Safe renderer input for one live local notification layer; source identity never crosses this API. */
data class FormationLayer(
  val localHour: Int,
  val category: CoarseNotificationCategory,
  val sourceColourRgb: Int,
)

/** Presentable local observation state, including sealed-day truth. */
sealed interface FormationObservation {
  data object AwaitingAccess : FormationObservation

  data object Active : FormationObservation

  data object Disconnected : FormationObservation

  data object Revoked : FormationObservation

  data object SealedObserved : FormationObservation

  data object SealedUnobserved : FormationObservation
}

data class FormationSnapshot(
  val localDate: LocalDate,
  val observation: FormationObservation,
  val layers: List<FormationLayer>,
  val sealedSpecimen: MuseumSpecimen?,
)

sealed interface RevealResult {
  data class Revealed(
    val revealedAtEpochMillis: Long,
  ) : RevealResult

  data class AlreadyRevealed(
    val revealedAtEpochMillis: Long,
  ) : RevealResult

  data object NotFound : RevealResult
}

/** UI-facing formation boundary. Its implementation is local-only for offline gameplay. */
interface FormationRepository {
  fun observe(localDate: LocalDate): Flow<FormationSnapshot>

  suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult
}

/** Room-backed formation repository. No network client or analytics service is accepted or used. */
class LocalFormationRepository(
  private val database: AfterchimeDatabase,
) : FormationRepository {
  override fun observe(localDate: LocalDate): Flow<FormationSnapshot> {
    val localDateString = localDate.toString()
    val liveInputs = combine(
      database.reducedNotificationDao().observeForLocalDate(localDateString),
      database.listenerAccessStateDao().observe(localDateString),
    ) { notifications, accessState ->
      LocalFormationInputs(
        layers = notifications.map { notification ->
          FormationLayer(
            localHour = notification.localHour,
            category = notification.category,
            sourceColourRgb = notification.sourceColourRgb,
          )
        },
        accessState = accessState?.latestState,
      )
    }
    val sealedInputs = combine(
      database.daySummaryDao().observe(localDateString),
      database.specimenDao().observeWithOutputForAnchoredLocalDate(localDateString),
    ) { summary, specimen ->
      SealedFormationInputs(
        observationState = summary?.observationState,
        specimen = specimen?.toMuseumSpecimen(),
      )
    }

    return combine(liveInputs, sealedInputs) { live, sealed ->
      FormationSnapshot(
        localDate = localDate,
        observation = sealed.observationState.toFormationObservation(live.accessState),
        layers = live.layers,
        sealedSpecimen = sealed.specimen,
      )
    }
  }

  override suspend fun reveal(specimenId: String, revealedAtEpochMillis: Long): RevealResult {
    require(specimenId.isNotBlank())
    require(revealedAtEpochMillis >= 0)
    return database.withTransaction {
      val specimenDao = database.specimenDao()
      val current = specimenDao.getBySpecimenId(specimenId) ?: return@withTransaction RevealResult.NotFound
      val previouslyRevealedAt = current.revealedAtEpochMillis
      if (previouslyRevealedAt != null) {
        return@withTransaction RevealResult.AlreadyRevealed(previouslyRevealedAt)
      }
      require(revealedAtEpochMillis >= current.createdAtEpochMillis)

      if (specimenDao.markRevealedIfUnrevealed(specimenId, revealedAtEpochMillis) == 1) {
        RevealResult.Revealed(revealedAtEpochMillis)
      } else {
        val afterConcurrentReveal = specimenDao.getBySpecimenId(specimenId)?.revealedAtEpochMillis
        if (afterConcurrentReveal != null) {
          RevealResult.AlreadyRevealed(afterConcurrentReveal)
        } else {
          error("A local specimen changed before its reveal transaction completed")
        }
      }
    }
  }

  private fun ObservationState?.toFormationObservation(
    currentAccessState: ListenerAccessState?,
  ): FormationObservation = when (this) {
    ObservationState.OBSERVED -> FormationObservation.SealedObserved
    ObservationState.UNOBSERVED -> FormationObservation.SealedUnobserved
    null -> when (currentAccessState) {
      ListenerAccessState.ACTIVE -> FormationObservation.Active
      ListenerAccessState.DISCONNECTED -> FormationObservation.Disconnected
      ListenerAccessState.REVOKED -> FormationObservation.Revoked
      null -> FormationObservation.AwaitingAccess
    }
  }

  private data class LocalFormationInputs(
    val layers: List<FormationLayer>,
    val accessState: ListenerAccessState?,
  )

  private data class SealedFormationInputs(
    val observationState: ObservationState?,
    val specimen: MuseumSpecimen?,
  )
}
