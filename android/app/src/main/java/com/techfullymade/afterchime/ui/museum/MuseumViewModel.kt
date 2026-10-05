package com.techfullymade.afterchime.ui.museum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Tier
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
}

/**
 * Local museum presenter. It takes no network, analytics, account, or notification dependency.
 * Selection survives filter changes but is cleared when the local collection no longer owns it.
 */
class MuseumViewModel(
  private val repository: MuseumRepository,
) : ViewModel() {
  private val tierFilter = MutableStateFlow(MuseumTierFilter.ALL)
  private val selectedSpecimenId = MutableStateFlow<String?>(null)

  val uiState: StateFlow<MuseumUiState> = combine(
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
