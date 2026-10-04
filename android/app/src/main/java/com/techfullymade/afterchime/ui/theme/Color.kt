package com.techfullymade.afterchime.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

val Basalt = Color(0xFF0B0D0C)
val BasaltRaised = Color(0xFF141816)
val StrataLine = Color(0xFF29312D)
val FossilMint = Color(0xFF65C6B4)
val FossilMintDark = Color(0xFF21483F)
val Copper = Color(0xFFC9824A)
val Sand = Color(0xFFD9B779)
val Bone = Color(0xFFEAE7DE)
val Slate = Color(0xFFA8B0AB)
val Oxide = Color(0xFFDC6F61)

/** WCAG contrast ratio for opaque sRGB tokens. */
fun contrastRatio(foreground: Color, background: Color): Float {
  val lighter = maxOf(foreground.relativeLuminance(), background.relativeLuminance())
  val darker = minOf(foreground.relativeLuminance(), background.relativeLuminance())
  return (lighter + 0.05f) / (darker + 0.05f)
}

private fun Color.relativeLuminance(): Float =
  (0.2126f * red.linearizedSrgb()) +
    (0.7152f * green.linearizedSrgb()) +
    (0.0722f * blue.linearizedSrgb())

private fun Float.linearizedSrgb(): Float =
  if (this <= 0.04045f) this / 12.92f else ((this + 0.055f) / 1.055f).pow(2.4f)
