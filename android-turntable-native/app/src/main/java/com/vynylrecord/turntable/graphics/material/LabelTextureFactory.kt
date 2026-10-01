package com.vynylrecord.turntable.graphics.material

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.vynylrecord.turntable.model.ColorPacking
import com.vynylrecord.turntable.model.LabelRendererSpec
import com.vynylrecord.turntable.model.LabelTextLayout
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.VinylStyle
import kotlin.random.Random

/**
 * Draws the record's paper label with the platform Canvas and hands back a bitmap for upload.
 *
 * No server, no network, no external artwork: the whole label - ring geometry, typography, the
 * spindle hole, the wordmark and even the paper grain - is generated locally at the requested
 * resolution. The renderer only regenerates it when [RecordMetadata] or the vinyl style actually
 * changes (`RecordMetadata.rendersSameLabelAs`), so this never runs per frame.
 *
 * Alpha channel note: the bitmap keeps `alpha = 255` on the paper and fades to 0 outside the label
 * circle. The shader uses that alpha to reduce the label's specular response, which is what stops
 * printed paper from looking like glazed ceramic under the key light.
 */
object LabelTextureFactory {

    private const val GRAIN_DOTS_PER_MEGAPIXEL = 2400
    private const val GRAIN_SEED = 0x5EED

    /**
     * Renders the label.
     *
     * @param sizePx square bitmap edge, normally [com.vynylrecord.turntable.graphics.RenderQuality.labelTextureSize].
     */
    fun create(metadata: RecordMetadata, style: VinylStyle, sizePx: Int): Bitmap {
        val size = sizePx.coerceIn(128, 4096)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = style.palette
        val normalized = metadata.normalized()

        val centre = size * 0.5f
        val labelRadius = size * LabelRendererSpec.LABEL_RADIUS

        drawPaper(canvas, size, centre, labelRadius, palette)
        drawRings(canvas, size, centre, palette)
        drawTypography(canvas, size, normalized, palette, style)
        drawWordmark(canvas, size, palette)
        drawSpindleHole(canvas, size, centre, palette)
        drawGrain(canvas, size, centre, labelRadius)

        return bitmap
    }

    // ------------------------------------------------------------------ layers

    private fun drawPaper(canvas: Canvas, size: Float, centre: Float, radius: Float, palette: com.vynylrecord.turntable.model.VinylPalette) {
        // Outside the circle stays fully transparent so the disc edge never fringes.
        val paper = Paint(Paint.ANTI_ALIAS_FLAG)
        paper.shader = RadialGradient(
            centre, centre, radius,
            intArrayOf(
                palette.labelSecondary,
                palette.labelPrimary,
                darken(palette.labelPrimary, 0.45f),
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centre, centre, radius, paper)

        // Off-centre sheen so the paper reads as printed stock rather than a flat fill.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        sheen.shader = LinearGradient(
            size * 0.12f, size * 0.08f, size * 0.9f, size * 0.95f,
            intArrayOf(withAlpha(Color.WHITE, 26), withAlpha(Color.WHITE, 0), withAlpha(Color.BLACK, 30)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centre, centre, radius, sheen)
    }

    private fun drawRings(canvas: Canvas, size: Float, centre: Float, palette: com.vynylrecord.turntable.model.VinylPalette) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = withAlpha(palette.labelRing, 190)
        }
        val strokeScale = size / 1024f
        ring.strokeWidth = 3.4f * strokeScale
        canvas.drawCircle(centre, centre, size * LabelRendererSpec.RING_OUTER_RADIUS, ring)

        ring.strokeWidth = 1.3f * strokeScale
        ring.color = withAlpha(palette.labelInk, 70)
        canvas.drawCircle(centre, centre, size * LabelRendererSpec.RING_INNER_RADIUS, ring)

        ring.strokeWidth = 2.2f * strokeScale
        ring.color = withAlpha(palette.labelRing, 150)
        canvas.drawCircle(centre, centre, size * LabelRendererSpec.RING_THIN_RADIUS, ring)

        // Catalogue scale marks around the rim, like the tick marks on a pressed label.
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.labelInk, 60)
            strokeWidth = 1.6f * strokeScale
        }
        val tickRadius = size * LabelRendererSpec.RING_OUTER_RADIUS
        for (i in 0 until 48) {
            val angle = Math.toRadians((i * 7.5).toDouble())
            val inner = tickRadius - 7f * strokeScale
            val outer = tickRadius - (if (i % 4 == 0) 20f else 13f) * strokeScale
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            canvas.drawLine(
                centre + cos * inner, centre + sin * inner,
                centre + cos * outer, centre + sin * outer,
                tick,
            )
        }
    }

    private fun drawTypography(
        canvas: Canvas,
        size: Float,
        metadata: RecordMetadata,
        palette: com.vynylrecord.turntable.model.VinylPalette,
        style: VinylStyle,
    ) {
        val budget = size * LabelRendererSpec.TEXT_WIDTH_BUDGET
        val ink = palette.labelInk

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = size * LabelRendererSpec.TITLE_SIZE
            letterSpacing = 0.02f
        }
        val titleLines = LabelTextLayout.layoutTitle(
            title = metadata.title,
            maxWidth = budget,
            maxLines = 2,
            measure = { titlePaint.measureText(it) },
        )
        var baseline = size * LabelRendererSpec.TITLE_FIRST_LINE_Y
        for (line in titleLines) {
            canvas.drawText(line, size * 0.5f, baseline, titlePaint)
            baseline += size * LabelRendererSpec.TITLE_LINE_SPACING
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(ink, 225)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = size * LabelRendererSpec.DEDICATION_SIZE
            letterSpacing = 0.03f
        }
        val dedication = LabelTextLayout.ellipsize(metadata.dedicationLine(), budget, { bodyPaint.measureText(it) })
        canvas.drawText(dedication, size * 0.5f, size * LabelRendererSpec.DEDICATION_Y, bodyPaint)

        // Rule under the dedication block.
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.labelRing, 150)
            strokeWidth = 1.8f * (size / 1024f)
        }
        canvas.drawLine(size * 0.34f, size * 0.545f, size * 0.66f, size * 0.545f, rule)

        // Side badge: a filled pill with the side letter.
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.labelRing, 210)
            style = Paint.Style.FILL
        }
        val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darken(palette.labelPrimary, 0.55f)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = size * LabelRendererSpec.SIDE_SIZE
            letterSpacing = 0.14f
        }
        val sideLabel = LabelRendererSpec.sideLabel(metadata.side)
        val badgeWidth = badgeText.measureText(sideLabel) + size * 0.075f
        val badgeHeight = size * 0.062f
        val badgeCentreY = size * LabelRendererSpec.SIDE_BADGE_Y
        val badgeRect = RectF(
            size * 0.5f - badgeWidth * 0.5f,
            badgeCentreY - badgeHeight * 0.5f,
            size * 0.5f + badgeWidth * 0.5f,
            badgeCentreY + badgeHeight * 0.5f,
        )
        canvas.drawRoundRect(badgeRect, badgeHeight * 0.5f, badgeHeight * 0.5f, badgePaint)
        canvas.drawText(sideLabel, size * 0.5f, badgeCentreY + size * 0.017f, badgeText)

        val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(ink, 205)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = size * LabelRendererSpec.SIGNATURE_SIZE
            letterSpacing = 0.04f
        }
        canvas.drawText(
            LabelTextLayout.ellipsize(metadata.signatureLine(), budget, { signaturePaint.measureText(it) }),
            size * 0.5f,
            size * LabelRendererSpec.SIGNATURE_Y,
            signaturePaint,
        )

        metadata.date?.let { date ->
            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(palette.labelRing, 220)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = size * LabelRendererSpec.CATALOGUE_SIZE
                letterSpacing = 0.12f
            }
            canvas.drawText(
                LabelTextLayout.ellipsize(date, budget, { datePaint.measureText(it) }),
                size * 0.5f,
                size * LabelRendererSpec.DATE_Y,
                datePaint,
            )
        }

        metadata.catalogue?.takeIf { it.isNotBlank() }?.let { catalogue ->
            val cataloguePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(ink, 130)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = size * LabelRendererSpec.CATALOGUE_SIZE
                letterSpacing = 0.30f
            }
            canvas.drawText(
                LabelTextLayout.ellipsize(catalogue.uppercase(), budget, { cataloguePaint.measureText(it) }),
                size * 0.5f,
                size * LabelRendererSpec.CATALOGUE_Y,
                cataloguePaint,
            )
        }

        // Accent bar: ties the label to the style's accent colour.
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 220)
            strokeWidth = 3.2f * (size / 1024f)
        }
        canvas.drawLine(size * 0.42f, size * 0.605f, size * 0.58f, size * 0.605f, accent)
        accent.alpha = 90 + (120 * style.accentStrength).toInt()
        canvas.drawLine(size * 0.455f, size * 0.575f, size * 0.545f, size * 0.575f, accent)
    }

    /** Small drawn record glyph plus the wordmark. */
    private fun drawWordmark(canvas: Canvas, size: Float, palette: com.vynylrecord.turntable.model.VinylPalette) {
        val scale = size / 1024f
        val markCentreX = size * 0.5f - size * 0.085f
        val markCentreY = size * LabelRendererSpec.MARK_Y
        val glyphRadius = size * 0.026f

        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.labelInk, 235) }
        canvas.drawCircle(markCentreX, markCentreY, glyphRadius, glyph)
        glyph.color = withAlpha(palette.labelPrimary, 255)
        canvas.drawCircle(markCentreX, markCentreY, glyphRadius * 0.42f, glyph)
        glyph.color = withAlpha(palette.labelRing, 235)
        canvas.drawCircle(markCentreX, markCentreY, glyphRadius * 0.14f, glyph)

        val armStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.labelInk, 200)
            strokeWidth = 2.4f * scale
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(
            markCentreX + glyphRadius * 0.9f, markCentreY - glyphRadius * 0.95f,
            markCentreX + glyphRadius * 0.15f, markCentreY - glyphRadius * 0.1f,
            armStroke,
        )

        val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.labelInk, 215)
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = size * LabelRendererSpec.MARK_SIZE
            letterSpacing = 0.24f
        }
        val text = LabelRendererSpec.WORDMARK
        val bounds = Rect()
        wordmark.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, markCentreX + glyphRadius * 1.35f, markCentreY + bounds.height() * 0.5f, wordmark)
    }

    private fun drawSpindleHole(canvas: Canvas, size: Float, centre: Float, palette: com.vynylrecord.turntable.model.VinylPalette) {
        val holeRadius = size * LabelRendererSpec.HOLE_RADIUS
        // The mesh punches the physical hole; here we only darken the rim so the punch reads.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = withAlpha(darken(palette.labelPrimary, 0.3f), 220)
            strokeWidth = 6f * (size / 1024f)
        }
        canvas.drawCircle(centre, centre, holeRadius * 1.35f, rim)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(palette.labelPrimary, 0.35f) }
        canvas.drawCircle(centre, centre, holeRadius * 1.1f, hole)
    }

    /** Deterministic paper grain: same seed, same dots, at any texture size. */
    private fun drawGrain(canvas: Canvas, size: Float, centre: Float, radius: Float) {
        val megapixels = (size * size) / 1_000_000f
        val dots = (GRAIN_DOTS_PER_MEGAPIXEL * megapixels).toInt().coerceIn(200, 12_000)
        val random = Random(GRAIN_SEED)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotScale = size / 1024f
        for (i in 0 until dots) {
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            // sqrt keeps the distribution uniform across the disc rather than clustered at the centre.
            val distance = kotlin.math.sqrt(random.nextFloat()) * radius
            val x = centre + kotlin.math.cos(angle) * distance
            val y = centre + kotlin.math.sin(angle) * distance
            grain.color = if (random.nextFloat() < 0.5f) {
                withAlpha(Color.WHITE, random.nextInt(6, 22))
            } else {
                withAlpha(Color.BLACK, random.nextInt(6, 26))
            }
            canvas.drawCircle(x, y, (0.6f + random.nextFloat() * 1.5f) * dotScale, grain)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255),
    )

    /** Exposed so callers can build a representative label without a Bitmap (tests, previews). */
    fun previewInk(style: VinylStyle): Int = ColorPacking.lerpArgb(
        style.palette.labelPrimary,
        style.palette.labelInk,
        0.7f,
    )
}
