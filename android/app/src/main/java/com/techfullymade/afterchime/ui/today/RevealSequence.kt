package com.techfullymade.afterchime.ui.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.ui.components.AfterchimeSurface
import com.techfullymade.afterchime.ui.theme.AfterchimeElevation
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing

/** Reveal presentation starts only after local persistence has marked the specimen revealed. */
@Composable
fun RevealSequence(
  specimen: MuseumSpecimen?,
  reducedMotion: Boolean,
  modifier: Modifier = Modifier,
) {
  val isRevealed = specimen?.revealedAtEpochMillis != null
  AnimatedVisibility(
    visible = isRevealed,
    modifier = modifier.fillMaxWidth().testTag("reveal-sequence"),
    enter = if (reducedMotion) EnterTransition.None else fadeIn(),
  ) {
    AfterchimeSurface(tonalElevation = AfterchimeElevation.raised) {
      Column(modifier = Modifier.padding(AfterchimeSpacing.content)) {
        Text(
          text = stringResource(R.string.specimen_revealed_title),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = specimen?.family?.displayName().orEmpty(),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

private fun com.techfullymade.afterchime.generation.Family.displayName(): String = name
  .lowercase()
  .split('_')
  .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }
