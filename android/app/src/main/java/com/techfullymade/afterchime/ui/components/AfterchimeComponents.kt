package com.techfullymade.afterchime.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.techfullymade.afterchime.ui.theme.AfterchimeElevation

@Composable
fun AfterchimeSurface(
  modifier: Modifier = Modifier,
  tonalElevation: Dp = AfterchimeElevation.flat,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = tonalElevation,
    content = content,
  )
}

@Composable
fun AfterchimePrimaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = modifier.heightIn(min = 48.dp),
    shape = MaterialTheme.shapes.small,
    colors = ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
    ),
    content = content,
  )
}
