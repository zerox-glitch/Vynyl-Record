package com.vynylrecord.app.core.audio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.model.toArgbInt
import java.io.File
import java.io.FileOutputStream

/**
 * The cover artwork, drawn on the device with the platform canvas.
 *
 * This is the same drawing code the 3D turntable's label texture uses, at a different size — one
 * renderer, two consumers. That is deliberate: the picture beside a record in the library has to be the
 * picture on the record in the player, or the library looks like a mockup of itself.
 *
 * Everything is procedural. There is no downloaded template, no bundled font file beyond the platform's
 * own families, and no network call: the bitmap is drawn from the record's own metadata and the vinyl
 * style's colours.
 */
object ArtworkRenderer {

    const val FILE_NAME = "cover.png"

    /** 1024 is the size that gets shared; the export sheet writes the same pixels to a chosen folder. */
    const val DEFAULT_SIZE = 1024

    fun render(record: Record, size: Int = DEFAULT_SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, size.toFloat(), record)
        return bitmap
    }

    /** Renders and writes a PNG, returning the file when it worked. */
    fun write(file: File, record: Record, size: Int = DEFAULT_SIZE): File? = try {
        file.parentFile?.mkdirs()
        val bitmap = render(record, size)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        file
    } catch (error: Exception) {
        null
    }

    /**
     * Draws the sleeve: a dark ground, the record seen face-on, and the label with the record's own
     * text on it.
     *
     * Split out from [render] so the same drawing can be used for a preview at any size — including the
     * live preview in the Studio's appearance step, which runs on every style change and must not
     * allocate a 1024-pixel bitmap each time.
     */
    fun draw(canvas: Canvas, size: Float, record: Record) {
        val style = VinylStyleId.fromId(record.styledId.id)
        val centre = size / 2f
        val discRadius = size * 0.44f
        val labelRadius = discRadius * 0.36f

        // ---- ground: a warm vignette rather than a flat fill, so the sleeve has depth
        val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centre * 0.72f,
                centre * 0.62f,
                size * 0.78f,
                intArrayOf(0xFF241F1A.toInt(), 0xFF14110F.toInt(), 0xFF0C0A09.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size, size, ground)

        // ---- the disc
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centre - discRadius * 0.35f,
                centre - discRadius * 0.35f,
                discRadius * 1.45f,
                intArrayOf(
                    lighten(style.baseColor.toArgbInt(), 0.22f),
                    style.baseColor.toArgbInt(),
                    darken(style.baseColor.toArgbInt(), 0.35f),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(centre, centre, discRadius, discPaint)

        // ---- grooves: concentric rings, tighter towards the edge, which is how a record is cut
        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = style.grooveColor.toArgbInt()
            strokeWidth = maxOf(1f, size * 0.0012f)
            alpha = 150
        }
        var radius = labelRadius * 1.06f
        var index = 0
        while (radius < discRadius * 0.985f) {
            groovePaint.alpha = if (index % 3 == 0) 170 else 90
            canvas.drawCircle(centre, centre, radius, groovePaint)
            // Bands tighten as they approach the label, matching a real lead-out.
            val step = discRadius * (0.006f + 0.004f * (1f - radius / discRadius))
            radius += step.coerceAtLeast(size * 0.0016f)
            index++
        }

        // ---- the run-out and the edge bevel
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.5f, size * 0.0022f)
            color = style.brassAccent.toArgbInt()
            alpha = 120
        }
        canvas.drawCircle(centre, centre, discRadius * 0.995f, rim)

        // ---- the sheen: a soft diagonal highlight across the whole disc
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                centre - discRadius,
                centre - discRadius,
                centre + discRadius,
                centre + discRadius,
                intArrayOf(
                    Color.TRANSPARENT,
                    0x22FFFFFF,
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.28f, 0.45f, 0.62f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(centre, centre, discRadius, sheen)

        // ---- the label
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centre,
                centre,
                labelRadius * 1.3f,
                intArrayOf(lighten(style.labelColor.toArgbInt(), 0.14f), style.labelColor.toArgbInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(centre, centre, labelRadius, labelPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, size * 0.0016f)
            color = style.brassAccent.toArgbInt()
            alpha = 190
        }
        canvas.drawCircle(centre, centre, labelRadius * 0.92f, ringPaint)
        canvas.drawCircle(centre, centre, labelRadius * 0.66f, ringPaint)

        // ---- the label's text
        val titleSize = labelRadius * 0.30f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = warmPaperOn(style.labelColor.toArgbInt())
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = titleSize
        }
        val title = record.displayTitle.uppercase()
        val fitted = fitText(title, labelRadius * 1.5f, titlePaint)
        canvas.drawText(fitted, centre, centre - labelRadius * 0.10f, titlePaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = warmPaperOn(style.labelColor.toArgbInt())
            alpha = 220
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = labelRadius * 0.145f
        }
        val dedication = record.dedicationLine
        if (dedication.isNotBlank()) {
            canvas.drawText(fitText(dedication, labelRadius * 1.5f, bodyPaint), centre, centre + labelRadius * 0.16f, bodyPaint)
        }
        val occasion = listOfNotNull(
            record.occasion.label.takeIf { it.isNotBlank() },
            record.occasionDate.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (occasion.isNotBlank()) {
            bodyPaint.textSize = labelRadius * 0.115f
            bodyPaint.alpha = 170
            canvas.drawText(fitText(occasion, labelRadius * 1.5f, bodyPaint), centre, centre + labelRadius * 0.40f, bodyPaint)
        }
        if (record.senderName.isNotBlank()) {
            bodyPaint.textSize = labelRadius * 0.105f
            bodyPaint.alpha = 150
            canvas.drawText(
                fitText("from ${record.senderName}", labelRadius * 1.4f, bodyPaint),
                centre,
                centre + labelRadius * 0.62f,
                bodyPaint,
            )
        }

        // ---- side indicator, bottom-left of the label, the way a pressing marks its sides
        if (record.sideALabel.isNotBlank()) {
            val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.brassAccent.toArgbInt()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = labelRadius * 0.13f
            }
            canvas.drawText(record.sideALabel.uppercase(), centre, centre + labelRadius * 0.84f, sidePaint)
        }

        // ---- the spindle hole
        canvas.drawCircle(centre, centre, labelRadius * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0C0A09.toInt()
        })
        canvas.drawCircle(
            centre,
            centre,
            labelRadius * 0.045f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, size * 0.0014f)
                color = style.brassAccent.toArgbInt()
            },
        )

        // ---- the tonearm, parked over the edge of the disc: the mark that makes it a *record player*
        drawTonearm(canvas, size, centre, discRadius, style)
    }

    private fun drawTonearm(canvas: Canvas, size: Float, centre: Float, discRadius: Float, style: VinylStyleId) {
        val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.brassAccent.toArgbInt()
            strokeWidth = maxOf(2f, size * 0.006f)
            strokeCap = Paint.Cap.ROUND
        }
        // Pivot at the upper right, stylus near the record's lead-in: the rest position of a real deck,
        // which is what makes the picture read as a turntable rather than as a disc on a dark square.
        val pivotX = centre + discRadius * 0.98f
        val pivotY = centre - discRadius * 1.12f
        val tipX = centre + discRadius * 0.62f
        val tipY = centre - discRadius * 0.60f
        canvas.drawLine(pivotX, pivotY, tipX, tipY, armPaint)

        // Counterweight behind the pivot, and the pivot bearing itself.
        canvas.drawCircle(
            pivotX + size * 0.030f,
            pivotY - size * 0.012f,
            size * 0.016f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2521.toInt() },
        )
        canvas.drawCircle(
            pivotX,
            pivotY,
            size * 0.022f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.brassAccent.toArgbInt() },
        )

        // The headshell: a small wedge at the stylus end, angled along the arm.
        val headshell = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - size * 0.020f, tipY - size * 0.024f)
            lineTo(tipX + size * 0.016f, tipY - size * 0.014f)
            close()
        }
        canvas.drawPath(
            headshell,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8DCC4.toInt() },
        )
        canvas.drawCircle(
            tipX,
            tipY,
            size * 0.005f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1614.toInt() },
        )
    }

    /** Text that fits the space it is given, with an ellipsis rather than a clipped second line. */
    private fun fitText(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    /** Cream text on a dark label, near-black on a light one: the label must always be readable. */
    private fun warmPaperOn(labelColor: Int): Int {
        val luminance = (
            0.2126 * Color.red(labelColor) + 0.7152 * Color.green(labelColor) + 0.0722 * Color.blue(labelColor)
            ) / 255f
        return if (luminance > 0.55f) 0xFF14110D.toInt() else 0xFFFEF3C7.toInt()
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}
