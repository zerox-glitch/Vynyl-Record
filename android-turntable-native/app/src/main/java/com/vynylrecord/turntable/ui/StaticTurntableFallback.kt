package com.vynylrecord.turntable.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.TonearmGeometry
import com.vynylrecord.turntable.model.TurntableSpec
import com.vynylrecord.turntable.model.VinylPalette
import com.vynylrecord.turntable.player.TurntableUiState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The no-OpenGL-ES-3.0 path.
 *
 * A device without ES 3.0 cannot run the shader pipeline, so the record player is drawn with the
 * plain Compose canvas instead: a top-down illustration built from the *same* [TurntableSpec]
 * dimensions and [TonearmGeometry] the 3D scene uses. The arm really does sit on its rest, at the
 * lead-in groove, or wherever the current playback progress puts it, and the platter turns only
 * while the transport is playing - and not at all when reduced motion is on.
 *
 * Every control still works, because the controls are Compose and the controller is the same
 * instance the 3D path would use. Only the lighting, the shadows and the drag-to-orbit camera are
 * missing.
 */
@Composable
fun StaticTurntableFallback(
    uiState: TurntableUiState,
    modifier: Modifier = Modifier,
) {
    val palette = uiState.vinylStyle.palette
    val titlePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val bodyPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
    }

    val spinning = uiState.showAsPlaying && !uiState.reducedMotion
    val transition = rememberInfiniteTransition(label = "fallback")
    val animatedTurn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fallbackTurn",
    )
    val recordAngle = if (spinning) animatedTurn else 0f

    Canvas(modifier = modifier) {
        val deckAspect = TurntableSpec.PLINTH_WIDTH / TurntableSpec.PLINTH_DEPTH
        val plinthWidth = minOf(size.width * 0.92f, size.height * 0.92f * deckAspect)
        val plinthHeight = plinthWidth / deckAspect
        val scale = plinthWidth / TurntableSpec.PLINTH_WIDTH
        val plinthCentre = Offset(size.width * 0.5f, size.height * 0.5f - plinthHeight * 0.03f)

        // Studio backdrop: warm at the top, dusty rose through the middle, deep stone at the base.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(palette.backdropTop),
                0.55f to Color(palette.backdropMid),
                1f to Color(palette.backdropBottom),
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0.6f to Color.Transparent,
                1f to Color(0xCC0C0A09),
            ),
        )

        fun screenX(metres: Float): Float = plinthCentre.x + metres * scale
        fun screenY(metres: Float): Float = plinthCentre.y + metres * scale

        val platterCentre = Offset(
            screenX(TurntableSpec.PLATTER_CENTER_X),
            screenY(TurntableSpec.PLATTER_CENTER_Z),
        )
        val pivot = Offset(
            screenX(TurntableSpec.TONEARM_PIVOT_X),
            screenY(TurntableSpec.TONEARM_PIVOT_Z),
        )

        // Contact shadow under the plinth.
        drawOval(
            color = Color(0x66000000),
            topLeft = Offset(plinthCentre.x - plinthWidth * 0.5f, plinthCentre.y + plinthHeight * 0.28f),
            size = Size(plinthWidth, plinthHeight * 0.34f),
        )

        drawPlinth(plinthCentre, plinthWidth, plinthHeight, scale)
        drawPlatter(platterCentre, scale, animatedTurn, spinning)
        drawRecord(
            centre = platterCentre,
            scale = scale,
            angle = recordAngle,
            palette = palette,
            title = uiState.metadata.title,
            dedication = uiState.metadata.dedicationLine(),
            titlePaint = titlePaint,
            bodyPaint = bodyPaint,
        )
        drawSpindle(platterCentre, scale)
        drawTonearm(
            pivot = pivot,
            scale = scale,
            angleDegrees = armAngleFor(uiState),
            lowered = uiState.visualPhase == VisualPhase.PLAYING ||
                uiState.visualPhase == VisualPhase.SEEKING ||
                uiState.visualPhase == VisualPhase.NEEDLE_LOWERING,
        )
        drawControls(plinthCentre, scale, uiState)
    }
}

/** Where the arm is, based on the same maths the animator uses. */
private fun armAngleFor(state: TurntableUiState): Float =
    when (state.visualPhase) {
        VisualPhase.IDLE,
        VisualPhase.LOADING_RECORD,
        VisualPhase.RECORD_SETTLING,
        VisualPhase.PLATTER_STARTING,
        VisualPhase.NEEDLE_LIFTING,
        VisualPhase.TONEARM_RETURNING,
        VisualPhase.PLATTER_STOPPING,
        VisualPhase.COMPLETED,
        -> TurntableSpec.TONEARM_REST_ANGLE_DEG

        VisualPhase.ERROR -> TurntableSpec.TONEARM_REST_ANGLE_DEG
        VisualPhase.TONEARM_MOVING,
        VisualPhase.NEEDLE_LOWERING,
        VisualPhase.PLAYING,
        VisualPhase.PAUSED,
        VisualPhase.SEEKING,
        -> TonearmGeometry.angleForProgress(state.progress)
    }

private fun DrawScope.drawPlinth(centre: Offset, width: Float, height: Float, scale: Float) {
    val topLeft = Offset(centre.x - width * 0.5f, centre.y - height * 0.5f)
    val corner = CornerRadius(TurntableSpec.PLINTH_CORNER_RADIUS * scale * 1.4f)

    for (signX in intArrayOf(-1, 1)) {
        for (signZ in intArrayOf(-1, 1)) {
            drawCircle(
                color = Color(0xFF0A0908),
                radius = TurntableSpec.FOOT_RADIUS * scale,
                center = Offset(
                    centre.x + signX * TurntableSpec.FOOT_INSET_X * scale,
                    centre.y + (signZ * TurntableSpec.FOOT_INSET_Z + 0.006f) * scale,
                ),
            )
        }
    }

    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF43352A),
            0.45f to Color(0xFF2A211C),
            1f to Color(0xFF14100E),
        ),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = VynylColors.Brass.copy(alpha = 0.55f),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = corner,
        style = Stroke(width = 1.6f),
    )
    // Wood grain: long, very low-contrast strokes.
    for (i in 0 until 7) {
        val y = topLeft.y + height * (0.13f + i * 0.12f)
        drawLine(
            color = Color(0x12FFFFFF),
            start = Offset(topLeft.x + width * 0.06f, y),
            end = Offset(topLeft.x + width * 0.94f, y + height * 0.01f),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawPlatter(
    centre: Offset,
    scale: Float,
    turn: Float,
    spinning: Boolean,
) {
    val radius = TurntableSpec.PLATTER_RADIUS * scale
    drawCircle(
        brush = Brush.linearGradient(
            0f to Color(0xFF3E3E43),
            0.5f to Color(0xFF8E8E95),
            1f to Color(0xFF33333A),
        ),
        radius = radius,
        center = centre,
    )
    drawCircle(color = Color(0x33FFFFFF), radius = radius, center = centre, style = Stroke(width = 2f))

    // Strobe marks on the bare ring between the mat and the rim. They turn with the platter, which
    // is the whole point of a strobe ring; nothing else about the fallback moves.
    val strobeRadius = (TurntableSpec.MAT_RADIUS + TurntableSpec.PLATTER_RADIUS) * 0.5f * scale
    val rotation = if (spinning) turn else 0f
    for (i in 0 until STROBE_MARKS) {
        val degrees = i * 360.0 / STROBE_MARKS + rotation
        val radians = Math.toRadians(degrees)
        val inner = strobeRadius - 0.0022f * scale
        val outer = strobeRadius + 0.0022f * scale
        drawLine(
            color = Color(0x66FFFFFF),
            start = Offset(
                centre.x + (cos(radians) * inner).toFloat(),
                centre.y + (sin(radians) * inner).toFloat(),
            ),
            end = Offset(
                centre.x + (cos(radians) * outer).toFloat(),
                centre.y + (sin(radians) * outer).toFloat(),
            ),
            strokeWidth = 1.4f,
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF2B2827),
            0.8f to Color(0xFF1E1C1B),
            1f to Color(0xFF121110),
        ),
        radius = TurntableSpec.MAT_RADIUS * scale,
        center = centre,
    )
}

private fun DrawScope.drawRecord(
    centre: Offset,
    scale: Float,
    angle: Float,
    palette: VinylPalette,
    title: String,
    dedication: String,
    titlePaint: Paint,
    bodyPaint: Paint,
) {
    val recordRadius = TurntableSpec.RECORD_RADIUS * scale
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF1B1A1C),
            0.86f to Color(0xFF0B0A0C),
            1f to Color(0xFF060506),
        ),
        radius = recordRadius,
        center = centre,
    )

    var grooveRadius = TurntableSpec.AUDIO_BAND_OUTER * scale
    while (grooveRadius > TurntableSpec.AUDIO_BAND_INNER * scale) {
        drawCircle(
            color = Color(0x14FFFFFF),
            radius = grooveRadius,
            center = centre,
            style = Stroke(width = 1f),
        )
        grooveRadius -= 0.0016f * scale
    }

    // Specular sheen band, drawn as an arc so it reads as a highlight rather than a ring.
    drawArc(
        color = Color(0x22FFFFFF),
        startAngle = 202f,
        sweepAngle = 62f,
        useCenter = false,
        topLeft = Offset(centre.x - recordRadius * 0.93f, centre.y - recordRadius * 0.93f),
        size = Size(recordRadius * 1.86f, recordRadius * 1.86f),
        style = Stroke(width = recordRadius * 0.42f),
    )

    rotate(degrees = angle, pivot = centre) {
        val labelRadius = TurntableSpec.LABEL_RADIUS * scale
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(palette.labelSecondary),
                1f to Color(palette.labelPrimary),
            ),
            radius = labelRadius,
            center = centre,
        )
        drawCircle(
            color = Color(palette.labelRing),
            radius = labelRadius,
            center = centre,
            style = Stroke(width = 2f),
        )
        drawCircle(
            color = Color(palette.labelInk).copy(alpha = 0.35f),
            radius = labelRadius * 0.84f,
            center = centre,
            style = Stroke(width = 1f),
        )

        titlePaint.color = palette.labelInk
        titlePaint.textSize = labelRadius * 0.30f
        bodyPaint.color = palette.labelInk
        bodyPaint.textSize = labelRadius * 0.17f

        val canvas = drawContext.canvas.nativeCanvas
        canvas.drawText(title.take(24), centre.x, centre.y - labelRadius * 0.10f, titlePaint)
        canvas.drawText(dedication.take(28), centre.x, centre.y + labelRadius * 0.26f, bodyPaint)

        drawLine(
            color = Color(palette.labelRing).copy(alpha = 0.6f),
            start = Offset(centre.x - labelRadius * 0.42f, centre.y + labelRadius * 0.40f),
            end = Offset(centre.x + labelRadius * 0.42f, centre.y + labelRadius * 0.40f),
            strokeWidth = 1.5f,
        )

        bodyPaint.color = palette.labelRing
        bodyPaint.textSize = labelRadius * 0.13f
        canvas.drawText("V Y N Y L   R E C O R D", centre.x, centre.y + labelRadius * 0.68f, bodyPaint)
    }

    // Spindle hole rim, outside the rotation so it never wobbles.
    drawCircle(
        color = Color(0xFF050505),
        radius = TurntableSpec.LABEL_HOLE_RADIUS * scale * 1.4f,
        center = centre,
    )
}

private fun DrawScope.drawSpindle(centre: Offset, scale: Float) {
    drawCircle(
        brush = Brush.linearGradient(
            0f to Color(0xFFEFEFEF),
            1f to Color(0xFF8A8A8A),
        ),
        radius = TurntableSpec.SPINDLE_RADIUS * scale,
        center = centre,
    )
}

private fun DrawScope.drawTonearm(
    pivot: Offset,
    scale: Float,
    angleDegrees: Float,
    lowered: Boolean,
) {
    val length = TonearmGeometry.EFFECTIVE_LENGTH * scale
    val radians = Math.toRadians(angleDegrees.toDouble())
    // Screen space: +X right, +Z down, and the arm's azimuth turns from +X towards -Z (up-screen).
    val direction = Offset(cos(radians).toFloat(), -sin(radians).toFloat())
    val tip = Offset(pivot.x + direction.x * length, pivot.y + direction.y * length)
    val perpendicular = Offset(-direction.y, direction.x)

    // Pivot housing and brass collar.
    drawCircle(color = Color(0xFF1F1B19), radius = 0.022f * scale, center = pivot)
    drawCircle(color = VynylColors.Brass, radius = 0.016f * scale, center = pivot, style = Stroke(width = 2f))
    drawCircle(color = Color(0xFF2E2A27), radius = 0.008f * scale, center = pivot)

    // Counterweight behind the pivot.
    val counterweight = Offset(
        pivot.x - direction.x * 0.048f * scale,
        pivot.y - direction.y * 0.048f * scale,
    )
    drawLine(color = Color(0xFF6B6B70), start = pivot, end = counterweight, strokeWidth = 0.010f * scale)
    drawCircle(color = Color(0xFF8A8A90), radius = 0.011f * scale, center = counterweight)

    // Tube.
    val tubeColor = if (lowered) Color(0xFF9A9AA2) else Color(0xFFAFAFB6)
    drawLine(color = tubeColor, start = pivot, end = tip, strokeWidth = 0.007f * scale)
    drawLine(color = Color(0x30FFFFFF), start = pivot, end = tip, strokeWidth = 0.002f * scale)

    // Headshell, cantilever and stylus.
    val headStart = Offset(
        pivot.x + direction.x * length * 0.85f,
        pivot.y + direction.y * length * 0.85f,
    )
    drawLine(color = Color(0xFFD9D9DD), start = headStart, end = tip, strokeWidth = 0.013f * scale)
    drawCircle(color = Color(0xFFECECEF), radius = 0.0038f * scale, center = tip)
    drawLine(
        color = Color(0xFFC6C6CC),
        start = Offset(tip.x - perpendicular.x * 0.004f * scale, tip.y - perpendicular.y * 0.004f * scale),
        end = Offset(tip.x + perpendicular.x * 0.004f * scale, tip.y + perpendicular.y * 0.004f * scale),
        strokeWidth = 1.2f,
    )

    // Arm rest: the ring the arm sits in when it is parked.
    val restRadians = Math.toRadians(TurntableSpec.TONEARM_REST_ANGLE_DEG.toDouble())
    val restTip = Offset(
        pivot.x + (cos(restRadians) * length).toFloat(),
        pivot.y - (sin(restRadians) * length).toFloat(),
    )
    drawCircle(
        color = Color(0xFF2A2724),
        radius = 0.008f * scale,
        center = restTip,
        style = Stroke(width = 2f),
    )
}

private fun DrawScope.drawControls(plinthCentre: Offset, scale: Float, state: TurntableUiState) {
    fun local(xMetres: Float, zMetres: Float) =
        Offset(plinthCentre.x + xMetres * scale, plinthCentre.y + zMetres * scale)

    // Power button.
    val powerCentre = local(TurntableSpec.POWER_BUTTON_X, TurntableSpec.POWER_BUTTON_Z)
    drawCircle(color = Color(0xFF1B1917), radius = TurntableSpec.POWER_BUTTON_RADIUS * scale, center = powerCentre)
    drawCircle(
        color = VynylColors.Brass,
        radius = TurntableSpec.POWER_BUTTON_RADIUS * scale,
        center = powerCentre,
        style = Stroke(width = 2f),
    )
    drawCircle(
        color = if (state.showAsPlaying) VynylColors.AmberBright else Color(0xFF6B6B6B),
        radius = TurntableSpec.POWER_BUTTON_RADIUS * scale * 0.38f,
        center = powerCentre,
    )

    // Speed selector: a pointer that flips between 33 and 45.
    val selector = local(TurntableSpec.SPEED_SELECTOR_X, TurntableSpec.SPEED_SELECTOR_Z)
    val selectorRadius = TurntableSpec.SPEED_SELECTOR_RADIUS * scale
    drawCircle(color = Color(0xFF232120), radius = selectorRadius, center = selector, style = Stroke(width = 2f))
    val markerDegrees = if (state.speed == PlatterSpeed.THIRTY_THREE) -74.0 else 74.0
    val markerRadians = Math.toRadians(markerDegrees)
    drawLine(
        color = VynylColors.AmberBright,
        start = selector,
        end = Offset(
            selector.x + (cos(markerRadians) * selectorRadius).toFloat(),
            selector.y + (sin(markerRadians) * selectorRadius).toFloat(),
        ),
        strokeWidth = 3f,
    )
    drawCircle(color = Color(0xFF6B6B6B), radius = selectorRadius * 0.22f, center = selector)

    // Volume knob.
    val knob = local(TurntableSpec.KNOB_X, TurntableSpec.KNOB_Z)
    val knobRadius = TurntableSpec.KNOB_RADIUS * scale
    drawCircle(
        brush = Brush.linearGradient(0f to Color(0xFFC68A3A), 1f to Color(0xFF7A4A16)),
        radius = knobRadius,
        center = knob,
    )
    for (i in 0 until 10) {
        val radians = Math.toRadians(i * 36.0 + 12)
        drawLine(
            color = Color(0x33000000),
            start = Offset(
                knob.x + (cos(radians) * knobRadius * 0.45f).toFloat(),
                knob.y + (sin(radians) * knobRadius * 0.45f).toFloat(),
            ),
            end = Offset(
                knob.x + (cos(radians) * knobRadius * 0.95f).toFloat(),
                knob.y + (sin(radians) * knobRadius * 0.95f).toFloat(),
            ),
            strokeWidth = 1.2f,
        )
    }

    // Indicator lamp: the vinyl style's accent colour, brighter while the deck is running.
    val lamp = local(TurntableSpec.INDICATOR_X, TurntableSpec.INDICATOR_Z)
    val lampColor = Color(state.vinylStyle.palette.accent)
    val lampRadius = TurntableSpec.INDICATOR_RADIUS * scale
    if (state.showAsPlaying) {
        drawCircle(color = lampColor.copy(alpha = 0.25f), radius = lampRadius * 3.6f, center = lamp)
    }
    drawCircle(
        color = if (state.showAsPlaying) lampColor else lampColor.copy(alpha = 0.4f),
        radius = lampRadius,
        center = lamp,
    )
}

private const val STROBE_MARKS = 60
