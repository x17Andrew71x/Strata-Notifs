package com.techfullymade.afterchime.ui.museum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.WeeklyDisplay
import com.techfullymade.afterchime.domain.WeeklyDisplaySlot
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderers
import com.techfullymade.afterchime.render.toRenderModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Presents an already arranged weekly display in the explicitly selected cosmetic world. */
@Composable
fun WeeklyDisplayScreen(
  display: WeeklyDisplay,
  world: World,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    display.days.forEachIndexed { index, slot ->
      WeeklyDisplayDay(slot = slot, world = world, index = index + 1)
    }
  }
}

@Composable
private fun WeeklyDisplayDay(
  slot: WeeklyDisplaySlot,
  world: World,
  index: Int,
) {
  val locale = LocalLocale.current.platformLocale
  val date = slot.date.format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
  )
  val title = when (slot) {
    is WeeklyDisplaySlot.Sealed -> stringResource(R.string.weekly_display_sealed)
    is WeeklyDisplaySlot.Missing -> stringResource(R.string.weekly_display_missing)
    is WeeklyDisplaySlot.Unobserved -> stringResource(R.string.weekly_display_unobserved)
  }
  val description = when (slot) {
    is WeeklyDisplaySlot.Sealed -> stringResource(R.string.weekly_display_sealed_semantics, index, date)
    is WeeklyDisplaySlot.Missing -> stringResource(R.string.weekly_display_missing_semantics, index, date)
    is WeeklyDisplaySlot.Unobserved -> stringResource(R.string.weekly_display_unobserved_semantics, index, date)
  }

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("weekly-display-day-$index")
      .semantics(mergeDescendants = true) { contentDescription = description },
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall)
      Text(text = date, style = MaterialTheme.typography.bodySmall)
      when (slot) {
        is WeeklyDisplaySlot.Missing -> Text(text = stringResource(R.string.weekly_display_missing_body))
        is WeeklyDisplaySlot.Unobserved -> Text(text = stringResource(R.string.weekly_display_unobserved_body))
        is WeeklyDisplaySlot.Sealed -> Unit
      }
      if (slot is WeeklyDisplaySlot.Sealed) {
        WorldRenderers.forWorld(world).Render(
          model = slot.specimen.toRenderModel(),
          modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        )
      }
    }
  }
}
