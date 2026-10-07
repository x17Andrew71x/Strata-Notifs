package com.techfullymade.afterchime.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.MuseumSpecimen
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One permitted prominent action for the present formation state. */
enum class TodayPrimaryAction {
  ENABLE_ACCESS,
  REVEAL,
  NONE,
}

/** Immutable, privacy-reduced state consumed by the Today renderer. */
data class TodayUiState(
  val snapshot: FormationSnapshot,
  val revealInFlight: Boolean = false,
) {
  val primaryAction: TodayPrimaryAction
    get() = when {
      revealInFlight -> TodayPrimaryAction.NONE
      snapshot.sealedSpecimen?.revealedAtEpochMillis == null &&
        snapshot.sealedSpecimen != null -> TodayPrimaryAction.REVEAL
      snapshot.observation == FormationObservation.AwaitingAccess ||
        snapshot.observation == FormationObservation.Disconnected ||
        snapshot.observation == FormationObservation.Revoked -> TodayPrimaryAction.ENABLE_ACCESS
      else -> TodayPrimaryAction.NONE
    }

  val specimen: MuseumSpecimen?
    get() = snapshot.sealedSpecimen

  companion object {
    fun from(snapshot: FormationSnapshot, revealInFlight: Boolean = false) = TodayUiState(
      snapshot = snapshot,
      revealInFlight = revealInFlight,
    )
  }
}

/**
 * Owns the current local formation only. It accepts no network, analytics, notification or identity
 * dependency, so UI cannot cross the reduced repository boundary.
 */
class TodayViewModel(
  private val repository: FormationRepository,
  private val localDate: LocalDate = LocalDate.now(),
  private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
  companion object {
    fun factory(repository: FormationRepository): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(TodayViewModel::class.java))
          return TodayViewModel(repository) as T
        }
      }
  }

  private val revealInFlight = MutableStateFlow(false)

  val uiState: StateFlow<TodayUiState> = combine(
    repository.observe(localDate),
    revealInFlight,
  ) { snapshot, isRevealing ->
    TodayUiState.from(snapshot, isRevealing)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
    initialValue = TodayUiState.from(
      FormationSnapshot(
        localDate = localDate,
        observation = FormationObservation.AwaitingAccess,
        layers = emptyList(),
        sealedSpecimen = null,
      ),
    ),
  )

  fun reveal() {
    val specimen = uiState.value.specimen ?: return
    if (uiState.value.primaryAction != TodayPrimaryAction.REVEAL || revealInFlight.value) return

    revealInFlight.value = true
    viewModelScope.launch {
      try {
        repository.reveal(specimen.id, clock.millis())
      } finally {
        revealInFlight.value = false
      }
    }
  }
}
