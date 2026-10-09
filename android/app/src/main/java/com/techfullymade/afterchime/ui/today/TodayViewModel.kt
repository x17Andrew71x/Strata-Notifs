package com.techfullymade.afterchime.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationRepository
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.domain.MuseumSpecimen
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
  val digInFlight: Boolean = false,
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
    fun from(
      snapshot: FormationSnapshot,
      revealInFlight: Boolean = false,
      digInFlight: Boolean = false,
    ) = TodayUiState(
      snapshot = snapshot,
      revealInFlight = revealInFlight,
      digInFlight = digInFlight,
    )
  }
}

/**
 * Owns the current local formation only. It accepts no network, analytics, notification or identity
 * dependency, so UI cannot cross the reduced repository boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
  private val repository: FormationRepository,
  initialLocalDate: LocalDate = LocalDate.now(),
  private val clock: Clock = Clock.systemUTC(),
  private val timeZone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
  companion object {
    private const val MINIMUM_ROLLOVER_DELAY_MILLIS = 1_000L

    fun factory(repository: FormationRepository): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(TodayViewModel::class.java))
          return TodayViewModel(repository) as T
        }
      }
  }

  private val localDate = MutableStateFlow(initialLocalDate)
  private val revealInFlight = MutableStateFlow(false)
  private val digInFlight = MutableStateFlow(false)

  val uiState: StateFlow<TodayUiState> = combine(
    localDate.flatMapLatest(repository::observe),
    revealInFlight,
    digInFlight,
  ) { snapshot, isRevealing, isDigging ->
    TodayUiState.from(snapshot, isRevealing, isDigging)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
    initialValue = TodayUiState.from(
      FormationSnapshot(
        localDate = initialLocalDate,
        observation = FormationObservation.AwaitingAccess,
        layers = emptyList(),
        sealedSpecimen = null,
      ),
    ),
  )

  init {
    viewModelScope.launch {
      while (isActive) {
        val now = Instant.now(clock).atZone(timeZone)
        val nextDay = now.toLocalDate().plusDays(1).atStartOfDay(timeZone)
        delay(maxOf(MINIMUM_ROLLOVER_DELAY_MILLIS, Duration.between(now, nextDay).toMillis()))
        refreshDate()
      }
    }
  }

  fun refreshDate() {
    localDate.value = Instant.now(clock).atZone(timeZone).toLocalDate()
  }

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

  fun dig(tileIndex: Int): Boolean {
    val excavation = uiState.value.snapshot.excavation ?: return false
    if (
      excavation.completed ||
      tileIndex in excavation.dugTiles ||
      excavation.energyAvailable < excavation.tileEnergyCost ||
      digInFlight.value
    ) return false

    digInFlight.value = true
    viewModelScope.launch {
      try {
        repository.dig(localDate.value, tileIndex, clock.millis())
      } finally {
        digInFlight.value = false
      }
    }
    return true
  }
}
