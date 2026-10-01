package com.vynylrecord.app.core.graphics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId
import com.vynylrecord.app.core.model.toArgbInt

/**
 * The label pressed onto the record, drawn on the device and uploaded once.
 *
 * This is the part of the 3D scene that is actually *about the user's record*: the title, the dedication and
 * the occasion, typeset on the label exactly as they are on the sleeve, drawn with the same platform canvas
 * the artwork uses. The object spinning on the platter and the picture in the library are therefore the same
 * record rather than two interpretations of it.
 *
 * ## One upload per change, not one per frame
 *
 * Uploading a bitmap to the GPU costs a few milliseconds, which would be a visible hitch every frame. The
 * texture is therefore keyed on a revision number that the player bumps whenever the record's text changes,
 * and on the style — so a record being played costs two comparisons per frame and nothing else. The bitmap
 * itself is reused between uploads rather than reallocated, which is what keeps a metadata edit from handing
 * a megabyte to the collector.
 */
internal class LabelTexture {

    var textureId: Int = 0
        private set

    var isReady: Boolean = false
        private set

    private var uploadedRevision: Long = Long.MIN_VALUE
    private var uploadedStyle: VinylStyleId? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    /** The paint objects are reused across uploads: this runs on a metadata edit, not on a frame. */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * Ensures the GPU texture matches the record.
     *
     * @param revision bumped by the player when the record's text changes
     * @return true when a texture is bound and current
     */
    fun sync(record: Record?, style: VinylStyleId, revision: Long, size: Int = TEXTURE_SIZE): Boolean {
        if (record == null) return false
        if (isReady && revision == uploadedRevision && style == uploadedStyle && textureSize == size) return true
        val target = ensureBitmap(size)
        drawLabel(target, record, style)
        upload(target)
        uploadedRevision = revision
        uploadedStyle = style
        textureSize = size
        return isReady
    }

    private var textureSize = 0

    private fun ensureBitmap(size: Int): Bitmap {
        val existing = bitmap
        if (existing != null && existing.width == size && !existing.isRecycled) return existing
        existing?.recycle()
        val created = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap = created
        canvas = Canvas(created)
        return created
    }

    /** Draws the label's face: the styled disc, two brass rings, and the record's own words. */
    private fun drawLabel(target: Bitmap, record: Record, style: VinylStyleId) {
        val surface = canvas ?: Canvas(target).also { canvas = it }
        val size = target.width.toFloat()
        val centre = size / 2f
        val radius = centre
        surface.drawColor(Color.TRANSPARENT)

        val labelArgb = style.labelColor.toArgbInt()
        val brassArgb = style.brassAccent.toArgbInt()

        fill.shader = RadialGradient(
            centre * 0.86f,
            centre * 0.82f,
            radius * 1.25f,
            intArrayOf(lighten(labelArgb, 0.18f), labelArgb, darken(labelArgb, 0.26f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        surface.drawCircle(centre, centre, radius, fill)
        fill.shader = null

        // The brass rings: the outer one is the label's edge, the inner one frames the text.
        stroke.color = brassArgb
        stroke.strokeWidth = size * 0.012f
        stroke.alpha = 235
        surface.drawCircle(centre, centre, radius * 0.93f, stroke)
        stroke.strokeWidth = size * 0.005f
        stroke.alpha = 165
        surface.drawCircle(centre, centre, radius * 0.72f, stroke)

        val ink = readableOn(labelArgb)

        // The title: serif, upper case, the largest thing on the label.
        text.color = ink
        text.alpha = 255
        text.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        text.textSize = size * 0.135f
        val title = if (record.title.isBlank()) "VOICE MEMORY" else record.title.uppercase()
        surface.drawText(fit(title, radius * 1.34f, text), centre, centre - radius * 0.06f, text)

        // The dedication, and who it is for.
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = size * 0.058f
        text.alpha = 235
        val dedication = record.dedicationLine
        if (dedication.isNotBlank()) {
            surface.drawText(fit(dedication, radius * 1.32f, text), centre, centre + radius * 0.14f, text)
        }

        val occasionLine = record.subtitleLine
        if (occasionLine.isNotBlank()) {
            text.textSize = size * 0.048f
            text.alpha = 190
            surface.drawText(fit(occasionLine, radius * 1.32f, text), centre, centre + radius * 0.32f, text)
        }

        // The side and the app's own mark, in the mono face: catalogue marks, not sentences.
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.textSize = size * 0.046f
        text.color = brassArgb
        text.alpha = 240
        surface.drawText(record.sideALabel.uppercase().ifBlank { "SIDE A" }, centre, centre + radius * 0.58f, text)

        // The spindle hole, punched through the drawing so the texture has a visible centre.
        fill.color = 0xFF0C0A09.toInt()
        fill.shader = null
        surface.drawCircle(centre, centre, radius * 0.062f, fill)
        stroke.color = brassArgb
        stroke.strokeWidth = size * 0.006f
        stroke.alpha = 220
        surface.drawCircle(centre, centre, radius * 0.062f, stroke)
    }

    private fun upload(source: Bitmap) {
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textureId = ids[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        try {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
            // Mipmaps matter here: the label is a small part of most frames, and without them the type
            // shimmers as the deck turns.
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            isReady = true
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "the label texture could not be uploaded", error)
            isReady = false
        } finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        isReady = false
        uploadedRevision = Long.MIN_VALUE
        uploadedStyle = null
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    private fun lighten(color: Int, amount: Float): Int = Color.argb(
        255,
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255),
    )

    private fun darken(color: Int, amount: Float): Int = Color.argb(
        255,
        (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255),
        (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255),
    )

    /** Cream on a dark label, near-black on a pale one: the label always has to be readable. */
    private fun readableOn(labelColor: Int): Int {
        val luminance = (
            0.2126 * Color.red(labelColor) +
                0.7152 * Color.green(labelColor) +
                0.0722 * Color.blue(labelColor)
            ) / 255f
        return if (luminance > 0.55f) 0xFF171310.toInt() else 0xFFFEF3C7.toInt()
    }

    /** Truncates with an ellipsis so a long title stays on the label rather than running off it. */
    private fun fit(value: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end).trimEnd() + "…"
    }

    companion object {
        /** 512 is enough for a label that fills a quarter of a 1080p frame and costs a quarter of the memory. */
        const val TEXTURE_SIZE = 512

        private const val TAG = "VynylLabelTexture"
    }
}
