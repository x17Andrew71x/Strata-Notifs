package com.techfullymade.afterchime.ui.museum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.CombineCandidate
import com.techfullymade.afterchime.domain.CombineEligibility
import com.techfullymade.afterchime.domain.CombineRequest
import com.techfullymade.afterchime.domain.CombineResult
import com.techfullymade.afterchime.domain.CombineSpecimensUseCase
import com.techfullymade.afterchime.domain.CollectibleState
import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Tier
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The only collection filters currently backed by sealed local specimen output. */
enum class MuseumTierFilter(
  val tier: Tier?,
  val labelRes: Int,
) {
  ALL(tier = null, labelRes = R.string.museum_filter_all),
  COMMON(tier = Tier.COMMON, labelRes = R.string.tier_common),
  UNCOMMON(tier = Tier.UNCOMMON, labelRes = R.string.tier_uncommon),
  RARE(tier = Tier.RARE, labelRes = R.string.tier_rare),
  EXCEPTIONAL(tier = Tier.EXCEPTIONAL, labelRes = R.string.tier_exceptional),
  SINGULAR(tier = Tier.SINGULAR, labelRes = R.string.tier_singular),
}

/** Immutable local-only state for browsing the museum collection. */
data class MuseumUiState(
  val specimens: List<MuseumSpecimen> = emptyList(),
  val tierFilter: MuseumTierFilter = MuseumTierFilter.ALL,
  val selectedSpecimenId: String? = null,
  val combineSelectionIds: List<String> = emptyList(),
  val combineConfirmationOutputState: CollectibleState? = null,
  val isCombineSubmitting: Boolean = false,
) {
  val hasRevealedSpecimens: Boolean
    get() = specimens.any { it.revealedAtEpochMillis != null }

  /**
   * Queued sealed specimens remain in the reveal flow, not the museum. The result is sorted here
   * rather than trusting storage order so all local repository implementations present consistently.
   */
  val visibleSpecimens: List<MuseumSpecimen>
    get() = specimens.asSequence()
      .filter { it.revealedAtEpochMillis != null }
      .filter { tierFilter.tier == null || it.tier == tierFilter.tier }
      .sortedWith(compareByDescending<MuseumSpecimen> { it.createdAtEpochMillis }.thenBy { it.id })
      .toList()

  val isCombining: Boolean
    get() = combineSelectionIds.isNotEmpty()

  val combineSelection: List<MuseumSpecimen>
    get() = combineSelectionIds.mapNotNull { id -> specimens.firstOrNull { it.id == id } }

  val combineOutputState: CollectibleState?
    get() = combineOutputFor(combineSelection)

  fun canSelectForCombine(specimen: MuseumSpecimen): Boolean {
    if (!isCombining || isCombineSubmitting || combineConfirmationOutputState != null) return false
    if (specimen.id in combineSelectionIds) return true
    if (combineSelectionIds.size >= COMBINE_SIZE || specimen.revealedAtEpochMillis == null || specimen.isLocked) {
      return false
    }
    val seed = combineSelection.firstOrNull() ?: return false
    return specimen.family == seed.family &&
      specimen.tier == seed.tier &&
      specimen.collectibleState == seed.collectibleState
  }
}

private data class CombineSession(
  val inputIds: List<String>,
  val confirmationOutputState: CollectibleState? = null,
  val isSubmitting: Boolean = false,
  val request: CombineRequest? = null,
)

/**
 * Local museum presenter. It takes no network, analytics, account, or notification dependency.
 * Selection survives filter changes but is cleared when the local collection no longer owns it.
 */
class MuseumViewModel(
  private val repository: MuseumRepository,
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
  private val tierFilter = MutableStateFlow(MuseumTierFilter.ALL)
  private val selectedSpecimenId = MutableStateFlow<String?>(null)
  private val combineSession = MutableStateFlow<CombineSession?>(null)

  private val collectionState = combine(
    repository.specimens,
    tierFilter,
    selectedSpecimenId,
  ) { specimens, selectedFilter, selectedId ->
    MuseumUiState(
      specimens = specimens,
      tierFilter = selectedFilter,
      selectedSpecimenId = selectedId?.takeIf { id ->
        specimens.any { it.id == id && it.revealedAtEpochMillis != null }
      },
    )
  }

  val uiState: StateFlow<MuseumUiState> = combine(collectionState, combineSession) { state, session ->
    state.copy(
      combineSelectionIds = session?.inputIds.orEmpty(),
      combineConfirmationOutputState = session?.confirmationOutputState,
      isCombineSubmitting = session?.isSubmitting == true,
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
    initialValue = MuseumUiState(),
  )

  fun selectTierFilter(filter: MuseumTierFilter) {
    tierFilter.value = filter
  }

  fun selectSpecimen(specimenId: String) {
    if (uiState.value.visibleSpecimens.any { it.id == specimenId }) {
      selectedSpecimenId.value = specimenId
    }
  }

  fun setSpecimenLocked(specimenId: String, locked: Boolean) {
    val isRevealedMuseumSpecimen = uiState.value.specimens.any { specimen ->
      specimen.id == specimenId && specimen.revealedAtEpochMillis != null
    }
    if (isRevealedMuseumSpecimen) {
      viewModelScope.launch {
        repository.setLocked(specimenId, locked)
      }
    }
  }

  /** Starts the explicit local combine flow from one unlocked ordinary or restored specimen. */
  fun beginCombine(specimenId: String) {
    val specimen = uiState.value.specimens.firstOrNull { it.id == specimenId } ?: return
    if (
      specimen.revealedAtEpochMillis == null ||
      specimen.isLocked ||
      specimen.collectibleState == CollectibleState.CENTRE_PIECE
    ) {
      return
    }
    tierFilter.value = MuseumTierFilter.ALL
    combineSession.value = CombineSession(inputIds = listOf(specimenId))
  }

  fun toggleCombineSpecimen(specimenId: String) {
    val session = combineSession.value ?: return
    if (session.isSubmitting || session.confirmationOutputState != null) return
    if (specimenId in session.inputIds) {
      if (session.inputIds.size == 1) return
      combineSession.value = session.copy(inputIds = session.inputIds - specimenId, request = null)
      return
    }
    val candidate = uiState.value.specimens.firstOrNull { it.id == specimenId } ?: return
    if (!canAddToCombine(session.inputIds, candidate)) return
    combineSession.value = session.copy(inputIds = session.inputIds + specimenId, request = null)
  }

  fun cancelCombine() {
    combineSession.value = null
  }

  fun reviewCombine() {
    val session = combineSession.value ?: return
    val outputState = currentCombineOutput(session.inputIds) ?: return
    combineSession.value = session.copy(
      confirmationOutputState = outputState,
      request = session.request ?: CombineRequest(
        mutationId = newId(),
        outputItemId = newId(),
        inputItemIds = session.inputIds,
        createdAtEpochMillis = nowEpochMillis(),
      ),
    )
  }

  fun dismissCombineConfirmation() {
    combineSession.value = combineSession.value?.copy(
      confirmationOutputState = null,
      request = null,
    )
  }

  /** Submits only after explicit confirmation; Room owns the durable idempotent transaction. */
  fun confirmCombine() {
    val session = combineSession.value ?: return
    val outputState = session.confirmationOutputState ?: return
    if (session.isSubmitting || currentCombineOutput(session.inputIds) != outputState) return
    val request = session.request ?: return
    combineSession.value = session.copy(confirmationOutputState = null, isSubmitting = true)
    viewModelScope.launch {
      try {
        when (repository.combine(request)) {
          is CombineResult.Combined,
          is CombineResult.AlreadyCombined -> combineSession.value = null

          is CombineResult.Ineligible,
          CombineResult.MutationConflict,
          CombineResult.OutputConflict -> {
            clearRejectedCombine(request)
          }
        }
      } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        restoreCombineRetry(request, outputState)
      }
    }
  }

  private fun clearRejectedCombine(request: CombineRequest) {
    val current = combineSession.value ?: return
    if (current.request == request) {
      combineSession.value = current.copy(
        confirmationOutputState = null,
        isSubmitting = false,
        request = null,
      )
    }
  }

  private fun restoreCombineRetry(request: CombineRequest, outputState: CollectibleState) {
    val current = combineSession.value ?: return
    if (current.request == request) {
      combineSession.value = current.copy(
        confirmationOutputState = outputState,
        isSubmitting = false,
      )
    }
  }

  private fun canAddToCombine(inputIds: List<String>, candidate: MuseumSpecimen): Boolean {
    if (inputIds.size >= COMBINE_SIZE || candidate.revealedAtEpochMillis == null || candidate.isLocked) return false
    val selected = inputIds.mapNotNull { id -> uiState.value.specimens.firstOrNull { it.id == id } }
    val seed = selected.firstOrNull() ?: return false
    return candidate.family == seed.family &&
      candidate.tier == seed.tier &&
      candidate.collectibleState == seed.collectibleState
  }

  private fun currentCombineOutput(inputIds: List<String>): CollectibleState? =
    combineOutputFor(inputIds.mapNotNull { id -> uiState.value.specimens.firstOrNull { it.id == id } })

  companion object {
    fun factory(repository: MuseumRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MuseumViewModel::class.java)) {
          "Unexpected ViewModel class: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return MuseumViewModel(repository) as T
      }
    }
  }
}

private fun combineOutputFor(selection: List<MuseumSpecimen>): CollectibleState? =
  (CombineSpecimensUseCase()(selection.map { specimen ->
    CombineCandidate(specimen, specimen.collectibleState)
  }) as? CombineEligibility.Eligible)?.outputState

private const val COMBINE_SIZE = 3
