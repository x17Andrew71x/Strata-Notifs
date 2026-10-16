package com.techfullymade.afterchime.devfeedback

import android.graphics.Bitmap
import android.graphics.Color
import java.security.MessageDigest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
class DevFeedbackScreenshotTest {
  @Test
  fun `encodes a bounded WebP screenshot with exact digest and dimensions`() {
    val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.rgb(24, 31, 28))
      setPixel(20, 30, Color.rgb(159, 232, 207))
    }

    val screenshot = encodeDevFeedbackScreenshot(bitmap, maxBytes = 256 * 1024)

    assertNotNull(screenshot)
    requireNotNull(screenshot)
    assertEquals("image/webp", screenshot.mediaType)
    assertEquals(320, screenshot.width)
    assertEquals(640, screenshot.height)
    assertTrue(screenshot.bytes.isNotEmpty())
    assertTrue(screenshot.bytes.size <= 256 * 1024)
    assertEquals(
      screenshot.sha256,
      MessageDigest.getInstance("SHA-256").digest(screenshot.bytes).joinToString("") { "%02x".format(it) },
    )
  }

  @Test
  fun `downscales oversized view captures without changing caller bitmap`() {
    val bitmap = Bitmap.createBitmap(2_000, 1_000, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.DKGRAY)
    }

    val screenshot = encodeDevFeedbackScreenshot(bitmap, maxBytes = 256 * 1024, maxWidth = 1_080)

    requireNotNull(screenshot)
    assertEquals(1_080, screenshot.width)
    assertEquals(540, screenshot.height)
    assertEquals(2_000, bitmap.width)
    assertEquals(1_000, bitmap.height)
    assertTrue(screenshot.bytes.isNotEmpty())
  }

  @Test
  fun `rejects impossible or unbounded screenshot requests`() {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    assertEquals(null, encodeDevFeedbackScreenshot(bitmap, maxBytes = 8))
  }
}
