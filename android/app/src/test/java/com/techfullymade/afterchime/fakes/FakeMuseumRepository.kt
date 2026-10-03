package com.techfullymade.afterchime.fakes

import com.techfullymade.afterchime.domain.MuseumRepository
import com.techfullymade.afterchime.domain.MuseumSpecimen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Mutable test double for museum UI tests; production code remains Room-backed. */
class FakeMuseumRepository(
  specimens: List<MuseumSpecimen> = emptyList(),
) : MuseumRepository {
  private val mutableSpecimens = MutableStateFlow(specimens)

  override val specimens: Flow<List<MuseumSpecimen>> = mutableSpecimens

  fun replace(specimens: List<MuseumSpecimen>) {
    mutableSpecimens.value = specimens
  }
}
