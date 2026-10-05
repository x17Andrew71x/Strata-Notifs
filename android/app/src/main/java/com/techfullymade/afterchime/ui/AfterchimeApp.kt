package com.techfullymade.afterchime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.domain.FormationSnapshot
import com.techfullymade.afterchime.ui.navigation.AfterchimeNavGraph
import com.techfullymade.afterchime.ui.navigation.RootDestination
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing
import com.techfullymade.afterchime.ui.theme.Basalt
import com.techfullymade.afterchime.ui.today.TodayScreen
import com.techfullymade.afterchime.ui.today.TodayUiState
import java.time.LocalDate

private val afterchimeNavGraphSaver = Saver<AfterchimeNavGraph, List<String>>(
  save = { graph ->
    graph.encodeSavedState().let { state ->
      listOfNotNull(
        state[AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY],
        state[AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY],
      )
    }
  },
  restore = { values ->
    val state = when (values.size) {
      1 -> mapOf(AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to values[0])
      2 -> mapOf(
        AfterchimeNavGraph.SAVED_ROOT_ROUTE_KEY to values[0],
        AfterchimeNavGraph.SAVED_NESTED_ROUTE_KEY to values[1],
      )
      else -> null
    }
    state?.let { runCatching { AfterchimeNavGraph.decodeSavedState(it) }.getOrNull() }
  },
)

@Composable
fun AfterchimeApp(
  onEnableNotificationAccess: () -> Unit,
  todayContent: (@Composable () -> Unit)? = null,
  museumContent: (@Composable () -> Unit)? = null,
  initialNavigation: AfterchimeNavGraph = AfterchimeNavGraph.initial(),
) {
  var navigation by rememberSaveable(stateSaver = afterchimeNavGraphSaver) {
    mutableStateOf(initialNavigation)
  }
  BackHandler(enabled = !navigation.isAtRoot) {
    navigation = navigation.back()
  }

  Scaffold(
    containerColor = Basalt,
    bottomBar = {
      if (navigation.isAtRoot) {
        RootNavigationBar(
          selectedRoot = navigation.selectedRoot,
          onRootSelected = { root -> navigation = navigation.selectRoot(root) },
        )
      }
    },
  ) { insets ->
    if (!navigation.isAtRoot) {
      NestedPlaceholder(
        modifier = Modifier.padding(insets),
        onBack = { navigation = navigation.back() },
      )
    } else {
      when (navigation.selectedRoot) {
        RootDestination.TODAY -> {
          if (todayContent == null) {
            TodayRoot(
              modifier = Modifier.padding(insets),
              onEnableNotificationAccess = onEnableNotificationAccess,
            )
          } else {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .testTag("root-content-today"),
            ) {
              todayContent()
            }
          }
        }

        RootDestination.MUSEUM -> {
          if (museumContent == null) {
            RootPlaceholder(
              destination = navigation.selectedRoot,
              modifier = Modifier.padding(insets),
            )
          } else {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .testTag("root-content-museum"),
            ) {
              museumContent()
            }
          }
        }

        RootDestination.COMMUNITY,
        RootDestination.MORE -> RootPlaceholder(
          destination = navigation.selectedRoot,
          modifier = Modifier.padding(insets),
        )
      }
    }
  }
}

@Composable
private fun RootNavigationBar(
  selectedRoot: RootDestination,
  onRootSelected: (RootDestination) -> Unit,
) {
  NavigationBar {
    AfterchimeNavGraph.roots.forEach { destination ->
      NavigationBarItem(
        modifier = Modifier
          .testTag("root-${destination.route}")
          .semantics { contentDescription = destination.talkBackLabel },
        selected = selectedRoot == destination,
        onClick = { onRootSelected(destination) },
        icon = { Text(destination.talkBackLabel.take(1)) },
        label = { Text(destination.talkBackLabel) },
        alwaysShowLabel = true,
      )
    }
  }
}

@Composable
private fun TodayRoot(
  modifier: Modifier,
  onEnableNotificationAccess: () -> Unit,
) {
  TodayScreen(
    state = TodayUiState.from(
      FormationSnapshot(
        localDate = LocalDate.now(),
        observation = FormationObservation.AwaitingAccess,
        layers = emptyList(),
        sealedSpecimen = null,
      ),
    ),
    onEnableNotificationAccess = onEnableNotificationAccess,
    onReveal = {},
    modifier = modifier.testTag("root-content-today"),
  )
}

@Composable
private fun RootPlaceholder(
  destination: RootDestination,
  modifier: Modifier,
) {
  val title = when (destination) {
    RootDestination.TODAY -> stringResource(R.string.today_title)
    RootDestination.MUSEUM -> stringResource(R.string.museum_title)
    RootDestination.COMMUNITY -> stringResource(R.string.community_title)
    RootDestination.MORE -> stringResource(R.string.more_title)
  }
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("root-content-${destination.route}")
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun NestedPlaceholder(
  modifier: Modifier,
  onBack: () -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .testTag("nested-content")
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
  ) {
    TextButton(
      onClick = onBack,
      modifier = Modifier.testTag("nested-back"),
    ) {
      Text(stringResource(R.string.back))
    }
    Text(
      text = stringResource(R.string.detail_title),
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.SemiBold,
    )
  }
}
