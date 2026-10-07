package com.techfullymade.afterchime.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.FormationObservation
import com.techfullymade.afterchime.ui.components.AfterchimePrimaryButton
import com.techfullymade.afterchime.ui.theme.AfterchimeSpacing

/** Root content for a local daily formation. It consumes only [TodayUiState]'s reduced snapshot. */
@Composable
fun TodayScreen(
  state: TodayUiState,
  onEnableNotificationAccess: () -> Unit,
  onReveal: (String) -> Unit,
  isAppInForeground: Boolean = true,
  reduceMotion: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val snapshot = state.snapshot
  val readyCopy = if (snapshot.observation == FormationObservation.SealedObserved) {
    null
  } else {
    state.specimen
      ?.takeIf { specimen -> specimen.revealedAtEpochMillis == null }
      ?.anchoredLocalDate
      ?.takeIf { anchoredDate -> anchoredDate < snapshot.localDate }
      ?.let { anchoredDate ->
        if (anchoredDate == snapshot.localDate.minusDays(1)) ReadyCopy.YESTERDAY else ReadyCopy.PRIOR_DAY
      }
  }
  val copy = todayCopy(
    observation = snapshot.observation,
    revealed = state.specimen?.revealedAtEpochMillis != null,
    readyCopy = readyCopy,
  )
  val screenDescription = stringResource(R.string.today_formation_description)
  val motionEnabled = isAppInForeground && !reduceMotion
  Column(
    modifier = modifier
      .fillMaxSize()
      .semantics { contentDescription = screenDescription }
      .padding(
        horizontal = AfterchimeSpacing.screenHorizontal,
        vertical = AfterchimeSpacing.screenVertical,
      ),
    verticalArrangement = Arrangement.spacedBy(AfterchimeSpacing.content),
  ) {
    Text(
      text = stringResource(copy.title),
      style = MaterialTheme.typography.headlineLarge,
    )
    Text(
      text = stringResource(copy.body),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyLarge,
    )

    if (readyCopy == null && snapshot.observation != FormationObservation.SealedUnobserved) {
      FormationCanvas(
        layers = snapshot.layers,
        motionEnabled = motionEnabled,
      )
    }

    RevealSequence(
      specimen = state.specimen,
      reducedMotion = reduceMotion,
    )

    Spacer(modifier = Modifier.weight(1f))
    when (state.primaryAction) {
      TodayPrimaryAction.ENABLE_ACCESS -> AfterchimePrimaryButton(
        onClick = onEnableNotificationAccess,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          stringResource(
            if (snapshot.observation == FormationObservation.AwaitingAccess) {
              R.string.enable_notification_access
            } else {
              R.string.open_notification_settings
            },
          ),
        )
      }

      TodayPrimaryAction.REVEAL -> AfterchimePrimaryButton(
        onClick = { state.specimen?.id?.let(onReveal) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(stringResource(R.string.reveal_specimen))
      }

      TodayPrimaryAction.NONE -> Unit
    }
  }
}

private data class TodayCopy(
  val title: Int,
  val body: Int,
)

private enum class ReadyCopy {
  YESTERDAY,
  PRIOR_DAY,
}

private fun todayCopy(
  observation: FormationObservation,
  revealed: Boolean,
  readyCopy: ReadyCopy?,
): TodayCopy = when {
  readyCopy == ReadyCopy.YESTERDAY -> TodayCopy(
    title = R.string.yesterday_specimen_ready_title,
    body = R.string.prior_specimen_ready_body,
  )
  readyCopy == ReadyCopy.PRIOR_DAY -> TodayCopy(
    title = R.string.prior_specimen_ready_title,
    body = R.string.prior_specimen_ready_body,
  )
  else -> todayCopy(observation, revealed)
}

private fun todayCopy(
  observation: FormationObservation,
  revealed: Boolean,
): TodayCopy = when (observation) {
  FormationObservation.AwaitingAccess -> TodayCopy(
    title = R.string.today_title,
    body = R.string.awaiting_access_body,
  )
  FormationObservation.Active -> TodayCopy(
    title = R.string.today_forming_title,
    body = R.string.today_quiet_body,
  )
  FormationObservation.Disconnected -> TodayCopy(
    title = R.string.observation_paused_title,
    body = R.string.observation_paused_body,
  )
  FormationObservation.Revoked -> TodayCopy(
    title = R.string.observation_paused_title,
    body = R.string.observation_revoked_body,
  )
  FormationObservation.SealedUnobserved -> TodayCopy(
    title = R.string.unobserved_day_title,
    body = R.string.unobserved_day_body,
  )
  FormationObservation.SealedObserved -> TodayCopy(
    title = if (revealed) R.string.today_revealed_title else R.string.sealed_formation_title,
    body = if (revealed) R.string.today_revealed_body else R.string.sealed_formation_body,
  )
}
