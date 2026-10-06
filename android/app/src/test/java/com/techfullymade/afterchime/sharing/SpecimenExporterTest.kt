package com.techfullymade.afterchime.sharing

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.techfullymade.afterchime.domain.CollectibleState
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.generation.Family
import com.techfullymade.afterchime.generation.Tier
import com.techfullymade.afterchime.generation.VisualParameters
import com.techfullymade.afterchime.render.World
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpecimenExporterTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun `each world exports bounded decodable metadata free png without stable identifier`() {
    World.values().forEach { world ->
      val file = SpecimenExporter(context).export(specimen(), world)
      try {
        val bytes = file.readBytes()
        assertTrue(bytes.size <= 8 * 1024 * 1024)
        assertTrue(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null)
        val privateMarkers = listOf("private-test-id", "notification-total-17", "account-id-42", "source-token-xyz")
        privateMarkers.forEach { marker ->
          assertFalse(file.name.contains(marker))
          assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(marker))
        }
        val types = pngChunkTypes(bytes)
        assertFalse(types.contains("tEXt"))
        assertFalse(types.contains("iTXt"))
        assertFalse(types.contains("zTXt"))
      } finally {
        file.delete()
      }
    }
  }

  @Test
  fun `default export has no date string and explicit date flag is rejected`() {
    val file = SpecimenExporter(context).export(specimen(), World.PRIMEVAL_STRATA)
    try {
      assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("2026-10-02"))
      org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
        SpecimenExporter(context).export(specimen(), World.PRIMEVAL_STRATA, includeDateLabel = true)
      }
    } finally {
      file.delete()
    }
  }

  private fun pngChunkTypes(bytes: ByteArray): List<String> {
    val types = mutableListOf<String>()
    var offset = 8
    while (offset + 12 <= bytes.size) {
      val length = ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)
      types.add(String(bytes, offset + 4, 4, Charsets.US_ASCII))
      offset += 12 + length
      if (types.last() == "IEND") break
    }
    return types
  }

  private fun specimen() = MuseumSpecimen(
    id = "private-test-id",
    anchoredLocalDate = LocalDate.of(2026, 10, 2),
    generatorVersion = 1,
    createdAtEpochMillis = 1_759_420_800_000L,
    revealedAtEpochMillis = 1_759_507_200_000L,
    collectibleState = CollectibleState.ORDINARY,
    family = Family.GEODE,
    tier = Tier.RARE,
    visual = VisualParameters(32, 7, 30, 42, 14),
  )
}
