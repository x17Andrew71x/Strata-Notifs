package com.techfullymade.afterchime.ui.museum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderers
import com.techfullymade.afterchime.render.toRenderModel
import com.techfullymade.afterchime.ui.components.AfterchimeSurface
import com.techfullymade.afterchime.ui.theme.AfterchimeElevation
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Local museum browse surface. It intentionally offers selection rather than irreversible actions;
 * lock, combine, donation, and sharing belong to their dedicated subsequent flows.
 */
@Composable
fun MuseumScreen(
  state: MuseumUiState,
  onTierFilterSelected: (MuseumTierFilter) -> Unit,
  onSpecimenSelected: (String) -> Unit,
  onCombineSpecimenSelected: (String) -> Unit = {},
  onCancelCombine: () -> Unit = {},
  onReviewCombine: () -> Unit = {},
  onDismissCombineConfirmation: () -> Unit = {},
  onConfirmCombine: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val screenDescription = stringResource(R.string.museum_collection_description)
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("root-content-museum")
      .semantics { contentDescription = screenDescription }
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.content),
  ) {
    Text(
      text = stringResource(R.string.museum_title),
      style = MaterialTheme.typography.headlineLarge,
    )
    if (state.isCombining) {
      CombineSelectionHeader(
        state = state,
        onCancel = onCancelCombine,
        onReview = onReviewCombine,
      )
    }
    if (!state.hasRevealedSpecimens) {
      MuseumEmptyState()
    } else {
      TierFilters(
        selected = state.tierFilter,
        onSelected = onTierFilterSelected,
      )
      if (state.visibleSpecimens.isEmpty()) {
        MuseumFilteredEmptyState()
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 152.dp),
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentPadding = PaddingValues(bottom = AfterchimeSpacing.section),
          horizontalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
          verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
        ) {
          items(
            items = state.visibleSpecimens,
            key = MuseumSpecimen::id,
          ) { specimen ->
            MuseumSpecimenCard(
              specimen = specimen,
              selected = if (state.isCombining) {
                specimen.id in state.combineSelectionIds
              } else {
                specimen.id == state.selectedSpecimenId
              },
              enabled = !state.isCombining || state.canSelectForCombine(specimen),
              onClick = {
                if (state.isCombining) {
                  onCombineSpecimenSelected(specimen.id)
                } else {
                  onSpecimenSelected(specimen.id)
                }
              },
            )
          }
        }
      }
    }
  }
  state.combineConfirmationOutputState?.let { outputState ->
    CombineDialog(
      outputState = outputState,
      onConfirm = onConfirmCombine,
      onDismiss = onDismissCombineConfirmation,
    )
  }
}

@Composable
private fun CombineSelectionHeader(
  state: MuseumUiState,
  onCancel: () -> Unit,
  onReview: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
  ) {
    Text(
      text = stringResource(R.string.museum_combine_selection_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Text(
      text = stringResource(R.string.museum_combine_selection_body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyLarge,
    )
    Text(
      text = stringResource(R.string.museum_combine_selected_count, state.combineSelectionIds.size),
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.testTag("museum-combine-selection-count"),
    )
    TextButton(
      onClick = onCancel,
      modifier = Modifier.testTag("museum-combine-cancel"),
    ) {
      Text(stringResource(R.string.museum_combine_cancel))
    }
    Button(
      onClick = onReview,
      enabled = state.combineOutputState != null && !state.isCombineSubmitting,
      modifier = Modifier.testTag("museum-combine-review"),
    ) {
      Text(stringResource(R.string.museum_combine_review))
    }
  }
}

@Composable
private fun MuseumEmptyState() {
  Column(
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
  ) {
    Text(
      text = stringResource(R.string.museum_empty_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Text(
      text = stringResource(R.string.museum_empty_body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
private fun MuseumFilteredEmptyState() {
  Column(
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
  ) {
    Text(
      text = stringResource(R.string.museum_filtered_empty_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Text(
      text = stringResource(R.string.museum_filtered_empty_body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
private fun TierFilters(
  selected: MuseumTierFilter,
  onSelected: (MuseumTierFilter) -> Unit,
) {
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
  ) {
    items(MuseumTierFilter.entries.size) { index ->
      val filter = MuseumTierFilter.entries[index]
      FilterChip(
        selected = selected == filter,
        onClick = { onSelected(filter) },
        label = { Text(stringResource(filter.labelRes)) },
        modifier = Modifier.testTag("museum-filter-${filter.name.lowercase(Locale.ROOT)}"),
      )
    }
  }
}

@Composable
private fun MuseumSpecimenCard(
  specimen: MuseumSpecimen,
  selected: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val family = specimen.family.displayName()
  val tier = specimen.tier.displayName()
  val date = specimen.anchoredLocalDate
    ?.let(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)::format)
    ?: stringResource(R.string.museum_restored)
  val description = if (specimen.anchoredLocalDate == null) {
    stringResource(R.string.museum_restored_specimen_description, family, tier)
  } else {
    stringResource(R.string.museum_specimen_description, family, tier, date)
  }
  AfterchimeSurface(
    modifier = Modifier
      .widthIn(min = 152.dp)
      .testTag("museum-specimen-${specimen.id}")
      .semantics {
        contentDescription = description
        this.selected = selected
      }
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    tonalElevation = if (selected) AfterchimeElevation.raised else AfterchimeElevation.flat,
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.controlGap),
      modifier = Modifier.padding(AfterchimeSpacing.controlGap),
    ) {
      WorldRenderers.forWorld(World.PRIMEVAL_STRATA).Render(
        model = specimen.toRenderModel(),
        modifier = Modifier
          .fillMaxWidth()
          .height(112.dp),
      )
      Text(
        text = family,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = tier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = date,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}

private fun Enum<*>.displayName(): String = name
  .lowercase(Locale.ROOT)
  .split('_')
  .joinToString(separator = " ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
