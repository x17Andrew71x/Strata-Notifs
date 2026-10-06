package com.techfullymade.afterchime.ui.worlds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.catalog.DevelopmentWorldState
import com.techfullymade.afterchime.catalog.WorldCatalog
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing

@Composable
fun WorldsScreen(
  state: DevelopmentWorldState,
  developmentControlsEnabled: Boolean,
  onBack: () -> Unit,
  onOwnForDevelopment: (World) -> Unit,
  onSelect: (World) -> Unit,
  onResetDevelopment: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxSize().testTag("worlds-screen")
      .verticalScroll(rememberScrollState())
      .padding(horizontal = AfterchimeSpacing.screenHorizontal, vertical = AfterchimeSpacing.screenVertical),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("worlds-back")) {
      Text(stringResource(R.string.back))
    }
    Text(stringResource(R.string.worlds_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    WorldCatalog.entries.forEach { entry ->
      val world = entry.world
      val owned = world in state.ownedWorlds
      val selected = world == state.selectedWorld
      val label = world.displayName()
      Column(modifier = Modifier.fillMaxWidth().testTag("world-row-${world.name.lowercase()}")) {
        Text(
          text = stringResource(
            if (selected) R.string.world_selected else if (owned) R.string.world_owned else R.string.world_not_owned,
            label,
          ),
          modifier = Modifier.semantics { contentDescription = "$label, ${if (selected) "selected" else if (owned) "owned" else "not owned"}" },
          style = MaterialTheme.typography.titleMedium,
        )
        when {
          selected -> Text(stringResource(R.string.world_currently_selected))
          owned && developmentControlsEnabled -> TextButton(onClick = { onSelect(world) }, modifier = Modifier.testTag("world-select-${world.name.lowercase()}")) {
            Text(stringResource(R.string.world_apply, label))
          }
          developmentControlsEnabled -> TextButton(onClick = { onOwnForDevelopment(world) }, modifier = Modifier.testTag("world-unlock-${world.name.lowercase()}")) {
            Text(stringResource(R.string.world_development_unlock, label))
          }
        }
      }
    }
    if (developmentControlsEnabled) {
      TextButton(onClick = onResetDevelopment, modifier = Modifier.testTag("world-development-reset")) {
        Text(stringResource(R.string.world_development_reset))
      }
    }
  }
}

private fun World.displayName(): String = name.lowercase().split('_')
  .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
