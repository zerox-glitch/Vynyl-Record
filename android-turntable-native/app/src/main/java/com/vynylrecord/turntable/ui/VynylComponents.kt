package com.vynylrecord.turntable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The transport glyphs, drawn rather than imported.
 *
 * `material-icons-extended` would add several thousand vectors to the APK for fewer than ten used
 * shapes, so the handful the turntable needs are drawn with the canvas instead. They scale cleanly,
 * inherit the tint, and cost nothing.
 */
enum class VynylIcon {
    PLAY,
    PAUSE,
    SKIP_BACK,
    SKIP_FORWARD,
    RESET_CAMERA,
    EXPAND,
    COLLAPSE,
    GAUGE,
    DISC,
    SUN,
    // ---- the shell
    MIC,
    STOP,
    IMPORT,
    FLASK,
    VAULT,
    GEAR,
    CLOSE,
    CHEVRON_RIGHT,
    CHEVRON_LEFT,
    CHECK,
    TRASH,
    PENCIL,
    NOTE,
}

/** Draws [icon] centred in the current layout bounds. */
@Composable
fun VynylIconGlyph(
    icon: VynylIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    strokeScale: Float = 1f,
) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.085f * strokeScale
        when (icon) {
            VynylIcon.PLAY -> drawTrianglePlayed(tint)
            VynylIcon.PAUSE -> drawPause(tint)
            VynylIcon.SKIP_BACK -> drawSkip(tint, backwards = true)
            VynylIcon.SKIP_FORWARD -> drawSkip(tint, backwards = false)
            VynylIcon.RESET_CAMERA -> drawResetCamera(tint, stroke)
            VynylIcon.EXPAND -> drawExpand(tint, stroke, outwards = true)
            VynylIcon.COLLAPSE -> drawExpand(tint, stroke, outwards = false)
            VynylIcon.GAUGE -> drawGauge(tint, stroke)
            VynylIcon.DISC -> drawDisc(tint, stroke)
            VynylIcon.SUN -> drawSun(tint, stroke)
            VynylIcon.MIC -> drawMic(tint, stroke)
            VynylIcon.STOP -> drawStop(tint)
            VynylIcon.IMPORT -> drawImport(tint, stroke)
            VynylIcon.FLASK -> drawFlask(tint, stroke)
            VynylIcon.VAULT -> drawVault(tint, stroke)
            VynylIcon.GEAR -> drawGear(tint, stroke)
            VynylIcon.CLOSE -> drawClose(tint, stroke)
            VynylIcon.CHEVRON_RIGHT -> drawChevron(tint, stroke, right = true)
            VynylIcon.CHEVRON_LEFT -> drawChevron(tint, stroke, right = false)
            VynylIcon.CHECK -> drawCheck(tint, stroke)
            VynylIcon.TRASH -> drawTrash(tint, stroke)
            VynylIcon.PENCIL -> drawPencil(tint, stroke)
            VynylIcon.NOTE -> drawNote(tint, stroke)
        }
    }
}

/** A microphone capsule on a stand: the Studio tab and the record button. */
private fun DrawScope.drawMic(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.20f
    val c = center
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - r * 0.62f, c.y - r * 1.55f),
        size = Size(r * 1.24f, r * 2.1f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.62f, r * 0.62f),
        style = Stroke(width = stroke),
    )
    drawArc(
        color = tint,
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(c.x - r * 1.15f, c.y - r * 0.35f),
        size = Size(r * 2.3f, r * 1.7f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color = tint,
        start = Offset(c.x, c.y + r * 1.35f),
        end = Offset(c.x, c.y + r * 2.1f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawStop(tint: Color) {
    val r = size.minDimension * 0.28f
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.3f, r * 0.3f),
    )
}

private fun DrawScope.drawImport(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.34f
    val c = center
    drawLine(
        color = tint,
        start = Offset(c.x, c.y - r),
        end = Offset(c.x, c.y + r * 0.45f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.5f, c.y + r * 0.02f),
        end = Offset(c.x, c.y + r * 0.5f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x + r * 0.5f, c.y + r * 0.02f),
        end = Offset(c.x, c.y + r * 0.5f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r, c.y + r),
        end = Offset(c.x + r, c.y + r),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/** An Erlenmeyer flask: the Sound Lab tab. */
private fun DrawScope.drawFlask(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.32f
    val c = center
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.30f, c.y - r),
        end = Offset(c.x - r * 0.30f, c.y - r * 0.25f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x + r * 0.30f, c.y - r),
        end = Offset(c.x + r * 0.30f, c.y - r * 0.25f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.30f, c.y - r * 0.25f),
        end = Offset(c.x - r * 0.95f, c.y + r * 0.85f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x + r * 0.30f, c.y - r * 0.25f),
        end = Offset(c.x + r * 0.95f, c.y + r * 0.85f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.95f, c.y + r * 0.85f),
        end = Offset(c.x + r * 0.95f, c.y + r * 0.85f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/** A lidded archive box: the Vault tab. */
private fun DrawScope.drawVault(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.33f
    val c = center
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - r, c.y - r * 0.45f),
        size = Size(r * 2f, r * 1.5f),
        style = Stroke(width = stroke),
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r, c.y - r * 0.45f),
        end = Offset(c.x + r, c.y - r * 0.45f),
        strokeWidth = stroke * 1.4f,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.25f, c.y + r * 0.25f),
        end = Offset(c.x + r * 0.25f, c.y + r * 0.25f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawGear(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.30f
    drawCircle(color = tint, radius = r, center = center, style = Stroke(width = stroke))
    drawCircle(color = tint, radius = r * 0.42f, center = center, style = Stroke(width = stroke))
    for (index in 0 until 8) {
        val angle = Math.toRadians(index * 45.0)
        drawLine(
            color = tint,
            start = Offset(
                center.x + (kotlin.math.cos(angle) * r * 1.05f).toFloat(),
                center.y + (kotlin.math.sin(angle) * r * 1.05f).toFloat(),
            ),
            end = Offset(
                center.x + (kotlin.math.cos(angle) * r * 1.48f).toFloat(),
                center.y + (kotlin.math.sin(angle) * r * 1.48f).toFloat(),
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawClose(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.30f
    drawLine(
        color = tint,
        start = Offset(center.x - r, center.y - r),
        end = Offset(center.x + r, center.y + r),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(center.x + r, center.y - r),
        end = Offset(center.x - r, center.y + r),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawChevron(tint: Color, stroke: Float, right: Boolean) {
    val r = size.minDimension * 0.26f
    val sign = if (right) 1f else -1f
    drawLine(
        color = tint,
        start = Offset(center.x - sign * r * 0.35f, center.y - r),
        end = Offset(center.x + sign * r * 0.45f, center.y),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(center.x + sign * r * 0.45f, center.y),
        end = Offset(center.x - sign * r * 0.35f, center.y + r),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawCheck(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.32f
    drawLine(
        color = tint,
        start = Offset(center.x - r * 0.85f, center.y + r * 0.05f),
        end = Offset(center.x - r * 0.18f, center.y + r * 0.72f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(center.x - r * 0.18f, center.y + r * 0.72f),
        end = Offset(center.x + r * 0.9f, center.y - r * 0.7f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawTrash(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.30f
    val c = center
    drawLine(
        color = tint,
        start = Offset(c.x - r, c.y - r * 0.6f),
        end = Offset(c.x + r, c.y - r * 0.6f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.45f, c.y - r * 0.6f),
        end = Offset(c.x - r * 0.45f, c.y - r * 1.05f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x + r * 0.45f, c.y - r * 0.6f),
        end = Offset(c.x + r * 0.45f, c.y - r * 1.05f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.45f, c.y - r * 1.05f),
        end = Offset(c.x + r * 0.45f, c.y - r * 1.05f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - r * 0.75f, c.y - r * 0.6f),
        size = Size(r * 1.5f, r * 1.7f),
        style = Stroke(width = stroke),
    )
}

private fun DrawScope.drawPencil(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.32f
    drawLine(
        color = tint,
        start = Offset(center.x - r * 0.7f, center.y + r * 0.9f),
        end = Offset(center.x + r * 0.85f, center.y - r * 0.65f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(center.x + r * 0.45f, center.y - r * 1.0f),
        end = Offset(center.x + r * 1.0f, center.y - r * 0.45f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(center.x - r * 0.7f, center.y + r * 0.9f),
        end = Offset(center.x - r * 1.05f, center.y + r * 1.05f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

/** A slanted note: the label on a pressing with no artwork of its own. */
private fun DrawScope.drawNote(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.28f
    val c = center
    drawLine(
        color = tint,
        start = Offset(c.x + r * 0.75f, c.y - r * 1.1f),
        end = Offset(c.x + r * 0.75f, c.y + r * 0.55f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.85f, c.y - r * 0.6f),
        end = Offset(c.x - r * 0.85f, c.y + r * 1.0f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(c.x - r * 0.85f, c.y - r * 0.6f),
        end = Offset(c.x + r * 0.75f, c.y - r * 1.1f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawCircle(color = tint, radius = r * 0.44f, center = Offset(c.x - r * 1.05f, c.y + r * 1.05f))
    drawCircle(color = tint, radius = r * 0.44f, center = Offset(c.x + r * 0.55f, c.y + r * 0.62f))
}

private fun DrawScope.drawTrianglePlayed(tint: Color) {
    val r = size.minDimension * 0.40f
    val c = center
    val path = Path().apply {
        moveTo(c.x - r * 0.62f, c.y - r)
        lineTo(c.x + r * 0.92f, c.y)
        lineTo(c.x - r * 0.62f, c.y + r)
        close()
    }
    drawPath(path, tint)
}

private fun DrawScope.drawPause(tint: Color) {
    val r = size.minDimension * 0.30f
    val c = center
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x - r * 0.78f, c.y - r),
        size = Size(r * 0.6f, r * 2f),
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(c.x + r * 0.18f, c.y - r),
        size = Size(r * 0.6f, r * 2f),
    )
}

private fun DrawScope.drawSkip(tint: Color, backwards: Boolean) {
    val r = size.minDimension * 0.30f
    val c = center
    val sign = if (backwards) -1f else 1f
    val path = Path().apply {
        moveTo(c.x + sign * r * 0.75f, c.y - r)
        lineTo(c.x - sign * r * 0.35f, c.y)
        lineTo(c.x + sign * r * 0.75f, c.y + r)
        close()
    }
    drawPath(path, tint)
    drawRoundRect(
        color = tint,
        topLeft = Offset(if (backwards) c.x + r * 0.05f else c.x - r * 1.05f, c.y - r * 0.9f),
        size = Size(r * 0.34f, r * 1.8f),
    )
}

private fun DrawScope.drawResetCamera(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.32f
    drawArc(
        color = tint,
        startAngle = 130f,
        sweepAngle = 260f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke),
    )
    drawCircle(color = tint, radius = r * 0.28f, center = center)
}

private fun DrawScope.drawExpand(tint: Color, stroke: Float, outwards: Boolean) {
    val r = size.minDimension * 0.32f
    val c = center
    val from = if (outwards) 1f else 0.15f
    val to = if (outwards) 0.30f else 0.62f
    for (signX in intArrayOf(-1, 1)) {
        for (signY in intArrayOf(-1, 1)) {
            drawLine(
                color = tint,
                start = Offset(c.x + signX * r * from, c.y + signY * r * from),
                end = Offset(c.x + signX * r * to, c.y + signY * r * to),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawGauge(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.34f
    drawArc(
        color = tint,
        startAngle = 150f,
        sweepAngle = 240f,
        useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2f, r * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
    val angle = Math.toRadians(-40.0)
    drawLine(
        color = tint,
        start = center,
        end = Offset(
            center.x + (kotlin.math.cos(angle) * r * 0.72f).toFloat(),
            center.y + (kotlin.math.sin(angle) * r * 0.72f).toFloat(),
        ),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawDisc(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.38f
    drawCircle(color = tint, radius = r, center = center, style = Stroke(width = stroke))
    drawCircle(color = tint, radius = r * 0.62f, center = center, style = Stroke(width = stroke * 0.75f))
    drawCircle(color = tint, radius = r * 0.16f, center = center)
}

private fun DrawScope.drawSun(tint: Color, stroke: Float) {
    val r = size.minDimension * 0.24f
    drawCircle(color = tint, radius = r, center = center, style = Stroke(width = stroke))
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0)
        val inner = r * 1.5f
        val outer = r * 2.1f
        drawLine(
            color = tint,
            start = Offset(
                center.x + (kotlin.math.cos(angle) * inner).toFloat(),
                center.y + (kotlin.math.sin(angle) * inner).toFloat(),
            ),
            end = Offset(
                center.x + (kotlin.math.cos(angle) * outer).toFloat(),
                center.y + (kotlin.math.sin(angle) * outer).toFloat(),
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * An icon button sized for a thumb.
 *
 * The 52dp touch target is deliberate: the deployment target includes phones held one-handed, and
 * the transport row sits at the bottom of the screen where reachability matters most.
 */
@Composable
fun VynylIconButton(
    icon: VynylIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 52.dp,
    selected: Boolean = false,
    tint: Color = if (selected) VynylColors.Background else VynylColors.Cream,
    container: Color = if (selected) VynylColors.Amber else VynylColors.PanelRaised,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, if (selected) VynylColors.AmberBright else VynylColors.Outline),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            VynylIconGlyph(
                icon = icon,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** A framed panel with a brass hairline, used for every block of controls. */
@Composable
fun BrassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(VynylColors.Panel)
            .border(1.dp, VynylColors.Brass.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
    ) {
        content()
    }
}

/** Section heading: small, uppercase, letter-spaced, sans. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = VynylColors.Muted,
        modifier = modifier,
    )
}

/** A selectable chip used by the style, preset and quality rows. */
@Composable
fun VynylChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = VynylColors.Amber,
    supporting: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.RadioButton },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) accent else VynylColors.Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) VynylColors.Cream else VynylColors.Muted,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = VynylColors.Muted.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** Horizontal row of chips that scrolls rather than truncating on narrow screens. */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
