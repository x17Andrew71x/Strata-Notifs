package com.techfullymade.afterchime.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.techfullymade.afterchime.domain.MuseumSpecimen
import com.techfullymade.afterchime.render.RenderViewport
import com.techfullymade.afterchime.render.RenderPrimitive
import com.techfullymade.afterchime.render.World
import com.techfullymade.afterchime.render.WorldRenderers
import com.techfullymade.afterchime.render.toRenderModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Writes a renderer-only, metadata-free PNG into a FileProvider-confined cache directory. */
class SpecimenExporter(private val context: Context) {
  fun export(specimen: MuseumSpecimen, world: World, includeDateLabel: Boolean = false): File {
    require(specimen.revealedAtEpochMillis != null)
    require(!includeDateLabel) { "Date labels are not available for specimen exports" }
    val viewport = RenderViewport(widthPx = 1024, heightPx = 1024)
    val plan = WorldRenderers.forWorld(world).createPlan(specimen.toRenderModel(), viewport)
    require(plan.primitives.size <= MAX_PRIMITIVES)
    val bitmap = Bitmap.createBitmap(viewport.widthPx, viewport.heightPx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      canvas.drawColor(plan.backgroundArgb.toInt())
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
      plan.primitives.forEach { primitive ->
        when (primitive) {
          is RenderPrimitive.Rect -> {
            paint.color = primitive.argb.toInt()
            canvas.drawRect(
              scale(primitive.left, viewport.widthPx),
              scale(primitive.top, viewport.heightPx),
              scale(primitive.left + primitive.width, viewport.widthPx),
              scale(primitive.top + primitive.height, viewport.heightPx),
              paint,
            )
          }
          is RenderPrimitive.Circle -> {
            paint.color = primitive.argb.toInt()
            canvas.drawCircle(
              scale(primitive.centreX, viewport.widthPx),
              scale(primitive.centreY, viewport.heightPx),
              scale(primitive.radius, minOf(viewport.widthPx, viewport.heightPx)),
              paint,
            )
          }
          is RenderPrimitive.Line -> {
            paint.color = primitive.argb.toInt()
            paint.strokeWidth = scale(primitive.width, minOf(viewport.widthPx, viewport.heightPx))
            canvas.drawLine(
              scale(primitive.startX, viewport.widthPx),
              scale(primitive.startY, viewport.heightPx),
              scale(primitive.endX, viewport.widthPx),
              scale(primitive.endY, viewport.heightPx),
              paint.apply { style = Paint.Style.STROKE },
            )
            paint.style = Paint.Style.FILL
          }
        }
      }
      val output = ByteArrayOutputStream()
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      val bytes = output.toByteArray()
      require(bytes.size <= MAX_PNG_BYTES)
      val directory = File(context.cacheDir, EXPORT_DIRECTORY)
      check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
      check(directory.exists() || directory.mkdir())
      val file = File(directory, "specimen-${UUID.randomUUID()}.png")
      file.outputStream().use { it.write(bytes) }
      return file
    } finally {
      bitmap.recycle()
    }
  }

  private fun scale(value: Int, dimension: Int): Float =
    value.toFloat() * dimension / NORMALIZED_SIZE

  private companion object {
    const val EXPORT_DIRECTORY = "specimen-exports"
    const val NORMALIZED_SIZE = 10_000
    const val MAX_PRIMITIVES = 512
    const val MAX_PNG_BYTES = 8 * 1024 * 1024
  }
}
