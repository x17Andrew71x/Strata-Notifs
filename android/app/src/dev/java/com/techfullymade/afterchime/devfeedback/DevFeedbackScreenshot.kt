package com.techfullymade.afterchime.devfeedback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DevFeedbackScreenshot(
  val mediaType: String,
  val bytes: ByteArray,
  val sha256: String,
  val width: Int,
  val height: Int,
)

internal fun captureDevFeedbackScreenshot(
  view: View,
  maxBytes: Int = MAX_SCREENSHOT_BYTES,
  maxWidth: Int = MAX_SCREENSHOT_WIDTH,
): DevFeedbackScreenshot? {
  if (view.width <= 0 || view.height <= 0) return null
  val scale = minOf(1.0, maxWidth.toDouble() / view.width.toDouble())
  val width = max(1, (view.width * scale).roundToInt())
  val height = max(1, (view.height * scale).roundToInt())
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  return try {
    val canvas = Canvas(bitmap)
    canvas.scale(width.toFloat() / view.width.toFloat(), height.toFloat() / view.height.toFloat())
    view.draw(canvas)
    encodeDevFeedbackScreenshot(bitmap, maxBytes, maxWidth)
  } finally {
    bitmap.recycle()
  }
}

internal fun encodeDevFeedbackScreenshot(
  bitmap: Bitmap,
  maxBytes: Int = MAX_SCREENSHOT_BYTES,
  maxWidth: Int = MAX_SCREENSHOT_WIDTH,
): DevFeedbackScreenshot? {
  if (maxBytes !in 1_024..MAX_SCREENSHOT_BYTES || maxWidth !in 1..MAX_SCREENSHOT_WIDTH) return null
  if (bitmap.width <= 0 || bitmap.height <= 0) return null
  val scale = minOf(1.0, maxWidth.toDouble() / bitmap.width.toDouble())
  val width = max(1, (bitmap.width * scale).roundToInt())
  val height = max(1, (bitmap.height * scale).roundToInt())
  val candidate = if (width == bitmap.width && height == bitmap.height) {
    bitmap
  } else {
    Bitmap.createScaledBitmap(bitmap, width, height, true)
  }
  try {
    for (quality in listOf(78, 68, 56, 44, 32)) {
      val output = ByteArrayOutputStream()
      if (!candidate.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output)) continue
      val bytes = output.toByteArray()
      if (bytes.isEmpty() || bytes.size > maxBytes) continue
      return DevFeedbackScreenshot(
        mediaType = "image/webp",
        bytes = bytes,
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        width = width,
        height = height,
      )
    }
  } finally {
    if (candidate !== bitmap) candidate.recycle()
  }
  return null
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

internal const val MAX_SCREENSHOT_BYTES = 768 * 1_024
internal const val MAX_SCREENSHOT_WIDTH = 1_080
