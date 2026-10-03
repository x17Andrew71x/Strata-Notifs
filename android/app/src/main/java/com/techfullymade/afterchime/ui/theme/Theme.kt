package com.techfullymade.afterchime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AfterchimeColors = darkColorScheme(
  primary = FossilMint,
  onPrimary = Basalt,
  primaryContainer = FossilMintDark,
  onPrimaryContainer = Bone,
  secondary = Sand,
  onSecondary = Basalt,
  background = Basalt,
  onBackground = Bone,
  surface = BasaltRaised,
  onSurface = Bone,
  surfaceVariant = StrataLine,
  onSurfaceVariant = Slate,
)

@Composable
fun AfterchimeTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = AfterchimeColors,
    content = content,
  )
}
