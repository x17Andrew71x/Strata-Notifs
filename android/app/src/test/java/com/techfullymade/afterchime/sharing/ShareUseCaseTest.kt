package com.techfullymade.afterchime.sharing

import android.content.ContextWrapper
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.techfullymade.afterchime.render.World
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareUseCaseTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun `share request contains image stream read grant and approved summary only`() {
    val directory = File(context.cacheDir, "specimen-exports").apply { mkdirs() }
    val file = File(directory, "specimen-review.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    try {
      val request = ShareUseCase(context).createShareIntent(
        context,
        file,
        "Afterchime specimen — Geode, Rare",
      )
      assertEquals(android.content.Intent.ACTION_SEND, request.action)
      assertEquals("image/png", request.type)
      assertEquals(setOf(android.content.Intent.EXTRA_STREAM, android.content.Intent.EXTRA_TEXT), request.extras?.keySet())
      assertEquals("Afterchime specimen — Geode, Rare", request.getStringExtra(android.content.Intent.EXTRA_TEXT))
      assertTrue(request.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
      val privateMarkers = listOf("private-test-id", "notification-total-17", "account-id-42", "source-token-xyz")
      privateMarkers.forEach { marker ->
        assertFalse(request.toUri(0).contains(marker))
        assertFalse(request.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().contains(marker))
        assertFalse(file.name.contains(marker))
      }
      val stream = IntentCompat.getParcelableExtra(request, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
      assertTrue(stream.toString().startsWith("content://"))
    } finally {
      file.delete()
      directory.delete()
    }
  }

  @Test
  fun `share fails safely for an unrevealed specimen`() {
    val outcome = ShareUseCase(context).share(unrevealedSpecimen(), World.PRIMEVAL_STRATA)
    assertEquals(ShareOutcome.Failed, outcome)
  }

  @Test
  fun `chooser failure returns safe failure without changing specimen data`() {
    val specimen = revealedSpecimen()
    val before = specimen.copy()
    val directory = File(context.cacheDir, "specimen-exports")
    val failingContext = object : ContextWrapper(context) {
      override fun startActivity(intent: android.content.Intent) {
        throw IllegalStateException("source-token-xyz notification-total-17 account-id-42")
      }
    }

    val outcome = ShareUseCase(failingContext).share(specimen, World.PRIMEVAL_STRATA)

    assertEquals(ShareOutcome.Failed, outcome)
    assertEquals(before, specimen)
    assertFalse(outcome.toString().contains("source-token-xyz"))
    directory.listFiles()?.forEach { it.delete() }
    directory.delete()
  }

  private fun revealedSpecimen() = com.techfullymade.afterchime.domain.MuseumSpecimen(
    id = "private-test-id",
    anchoredLocalDate = java.time.LocalDate.of(2026, 10, 2),
    generatorVersion = 1,
    createdAtEpochMillis = 1,
    revealedAtEpochMillis = 2,
    family = com.techfullymade.afterchime.generation.Family.GEODE,
    tier = com.techfullymade.afterchime.generation.Tier.RARE,
    visual = com.techfullymade.afterchime.generation.VisualParameters(32, 7, 30, 42, 14),
  )

  private fun unrevealedSpecimen() = com.techfullymade.afterchime.domain.MuseumSpecimen(
    id = "private-test-id",
    anchoredLocalDate = null,
    generatorVersion = 1,
    createdAtEpochMillis = 1,
    revealedAtEpochMillis = null,
    family = com.techfullymade.afterchime.generation.Family.GEODE,
    tier = com.techfullymade.afterchime.generation.Tier.RARE,
    visual = com.techfullymade.afterchime.generation.VisualParameters(32, 7, 30, 42, 14),
  )
}
