package com.techfullymade.afterchime.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.techfullymade.afterchime.R
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.render.World
import java.io.File
import java.util.Locale

sealed interface ShareOutcome {
  data object Shared : ShareOutcome
  data object Failed : ShareOutcome
}

/** Local export and chooser seam; failures deliberately carry no exception or specimen data. */
class ShareUseCase(
  private val context: Context,
  private val exporter: SpecimenExporter = SpecimenExporter(context),
) {
  fun share(specimen: MuseumSpecimen, world: World): ShareOutcome = try {
    val file = exporter.export(specimen, world)
    val request = createShareIntent(context, file, safeSummary(specimen))
    context.startActivity(Intent.createChooser(request, context.getString(R.string.specimen_share_chooser)))
    ShareOutcome.Shared
  } catch (_: Exception) {
    ShareOutcome.Failed
  }

  internal fun createShareIntent(context: Context, file: File, summary: String): Intent {
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      file,
    )
    return Intent(Intent.ACTION_SEND).apply {
      type = "image/png"
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_TEXT, summary)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private fun safeSummary(specimen: MuseumSpecimen): String =
    context.getString(R.string.specimen_share_summary, specimen.family.name.displayName(), specimen.tier.name.displayName())

  private fun String.displayName(): String = lowercase(Locale.ROOT)
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
