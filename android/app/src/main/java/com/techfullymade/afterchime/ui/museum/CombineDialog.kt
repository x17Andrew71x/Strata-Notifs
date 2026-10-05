package com.techfullymade.afterchime.ui.museum

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.CollectibleState

/** Explicit confirmation for the local, irreversible three-to-one collection mutation. */
@Composable
fun CombineDialog(
  outputState: CollectibleState,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val outputName = when (outputState) {
    CollectibleState.ORDINARY -> error("An ordinary specimen cannot be a combine output")
    CollectibleState.RESTORED -> stringResource(R.string.collectible_state_restored)
    CollectibleState.CENTRE_PIECE -> stringResource(R.string.collectible_state_centre_piece)
  }
  AlertDialog(
    modifier = Modifier.testTag("combine-dialog"),
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.combine_dialog_title)) },
    text = { Text(stringResource(R.string.combine_dialog_body, outputName)) },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("combine-dialog-cancel"),
      ) {
        Text(stringResource(R.string.combine_dialog_cancel))
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier.testTag("combine-dialog-confirm"),
      ) {
        Text(stringResource(R.string.combine_dialog_confirm))
      }
    },
  )
}