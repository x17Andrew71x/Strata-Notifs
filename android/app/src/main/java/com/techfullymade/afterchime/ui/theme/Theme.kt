package com.techfullymade.afterchime.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

fun afterchimeColorScheme(highContrast: Boolean): ColorScheme = darkColorScheme(
  primary = FossilMint,
  onPrimary = Basalt,
  primaryContainer = FossilMintDark,
  onPrimaryContainer = Bone,
  secondary = Copper,
  onSecondary = Basalt,
  tertiary = Sand,
  onTertiary = Basalt,
  background = Basalt,
  onBackground = if (highContrast) Bone else Slate,
  surface = BasaltRaised,
  onSurface = Bone,
  surfaceVariant = StrataLine,
  onSurfaceVariant = if (highContrast) Bone else Slate,
  outline = if (highContrast) Bone else StrataLine,
  error = Oxide,
  onError = Basalt,
)

@Composable
fun AfterchimeTheme(
  reduceMotion: Boolean = false,
  highContrast: Boolean = false,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalAfterchimeMotion provides if (reduceMotion) AfterchimeMotion.reduced else AfterchimeMotion.standard,
  ) {
    MaterialTheme(
      colorScheme = afterchimeColorScheme(highContrast),
      typography = AfterchimeTypography,
      shapes = AfterchimeShapes,
      content = content,
    )
  }
}
