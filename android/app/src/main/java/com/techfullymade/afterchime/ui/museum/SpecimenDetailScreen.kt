package com.techfullymade.afterchime.ui.museum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * Presentation for one revealed local specimen. Favourite protection is an explicit local action;
 * destructive collection actions arrive only with their transactional ownership and confirmation flows.
 */
@Composable
fun SpecimenDetailScreen(
  specimen: MuseumSpecimen,
  world: World = World.PRIMEVAL_STRATA,
  onBack: () -> Unit,
  onSetLocked: (Boolean) -> Unit,
  onBeginCombine: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val family = specimen.family.displayName()
  val tier = specimen.tier.displayName()
  val sealedDate = specimen.anchoredLocalDate
    ?.let(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)::format)
  val screenDescription = stringResource(R.string.specimen_detail_description)

  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("specimen-detail")
      .semantics { contentDescription = screenDescription }
      .verticalScroll(rememberScrollState())
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.content),
  ) {
    TextButton(
      onClick = onBack,
      modifier = Modifier.testTag("specimen-detail-back"),
    ) {
      Text(stringResource(R.string.back))
    }
    Text(
      text = stringResource(R.string.specimen_detail_title),
      style = MaterialTheme.typography.headlineLarge,
    )
    AfterchimeSurface(
      modifier = Modifier.fillMaxWidth(),
      tonalElevation = AfterchimeElevation.raised,
    ) {
      Column(
        modifier = Modifier.padding(AfterchimeSpacing.content),
        verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.content),
      ) {
        WorldRenderers.forWorld(world).Render(
          model = specimen.toRenderModel(),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 224.dp)
            .testTag("specimen-detail-renderer"),
        )
        DetailFact(
          label = stringResource(R.string.specimen_detail_family),
          value = family,
        )
        DetailFact(
          label = stringResource(R.string.specimen_detail_tier),
          value = tier,
        )
        DetailFact(
          label = stringResource(
            if (sealedDate == null) R.string.specimen_detail_restored else R.string.specimen_detail_sealed,
          ),
          value = sealedDate ?: stringResource(R.string.museum_restored),
          modifier = Modifier.testTag("specimen-detail-sealed-date"),
        )
        if (specimen.isLocked) {
          DetailFact(
            label = stringResource(R.string.specimen_detail_lock),
            value = stringResource(R.string.specimen_detail_locked),
          )
        }
        TextButton(
          onClick = { onSetLocked(!specimen.isLocked) },
          modifier = Modifier.testTag("specimen-detail-lock"),
        ) {
          Text(
            if (specimen.isLocked) {
              stringResource(R.string.specimen_detail_unlock)
            } else {
              stringResource(R.string.specimen_detail_lock)
            },
          )
        }
        if (
          specimen.revealedAtEpochMillis != null &&
          !specimen.isLocked &&
          specimen.collectibleState != com.techfullymade.afterchime.domain.CollectibleState.CENTRE_PIECE
        ) {
          TextButton(
            onClick = onBeginCombine,
            modifier = Modifier.testTag("specimen-detail-combine"),
          ) {
            Text(stringResource(R.string.specimen_detail_combine))
          }
        }
      }
    }
  }
}

/** Safe fallback when a saved route outlives local specimen ownership. */
@Composable
fun MissingSpecimenDetailScreen(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("specimen-detail-missing")
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.content),
  ) {
    TextButton(
      onClick = onBack,
      modifier = Modifier.testTag("specimen-detail-back"),
    ) {
      Text(stringResource(R.string.back))
    }
    Text(
      text = stringResource(R.string.specimen_detail_missing_title),
      style = MaterialTheme.typography.headlineLarge,
    )
    Text(
      text = stringResource(R.string.specimen_detail_missing_body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

@Composable
private fun DetailFact(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

private fun Enum<*>.displayName(): String = name
  .lowercase(Locale.ROOT)
  .split('_')
  .joinToString(separator = " ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
