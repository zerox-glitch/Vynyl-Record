package com.vynylrecord.app.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.RenderState
import com.vynylrecord.app.core.model.VinylStyleId
import kotlin.math.max

/**
 * The pieces the screens are built from.
 *
 * Everything here exists because it appears on more than one screen. A record card appears in the Vault,
 * in search results and in the export sheet's preview; the waveform appears on a card and in the player;
 * the brass panel is every settings section and every Studio step. Keeping them here is what stops the
 * app from looking like six screens built by six people.
 */

/**
 * A panel: the app's card.
 *
 * A near-black surface with a brass seam and a barely-there amber glow at the top edge, which is what the
 * app's own chrome looks like under a lamp. The glow is drawn rather than shadowed so it costs nothing on
 * a device where shadows are switched off for performance.
 */
@Composable
fun BrassPanel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(VynylSpacing.large),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val base = Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(VynylColors.DeepStone)
        .border(BorderStroke(VynylSpacing.hairline, VynylColors.Border), shape)
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base

    Box(modifier = interactive) {
        Column(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * The app's primary action.
 *
 * Amber on obsidian with cream text, 48 dp tall. There is exactly one on a screen at a time: the Studio's
 * "Press the record", the export sheet's "Export", the lock screen's "Unlock". A second one would make
 * neither of them the obvious next step.
 */
@Composable
fun VynylPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = VynylSpacing.minimumTouchTarget),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VynylColors.Amber,
            contentColor = VynylColors.Obsidian,
            disabledContainerColor = VynylColors.Panel,
            disabledContentColor = VynylColors.Muted,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VynylSpacing.small))
        }
        Text(text, style = VynylType.label)
    }
}

/** A secondary action: brass outline, cream text, never louder than the primary one. */
@Composable
fun VynylSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = VynylSpacing.minimumTouchTarget),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(VynylSpacing.hairline, VynylColors.BorderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = VynylColors.Cream,
            disabledContentColor = VynylColors.Muted,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(VynylSpacing.small))
        }
        Text(text, style = VynylType.label)
    }
}

/** A quiet action: no border, muted text, used for "Skip", "Not now" and row trailing actions. */
@Composable
fun VynylTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = VynylColors.Cream,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = VynylSpacing.minimumTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = VynylColors.Muted),
    ) {
        Text(text, style = VynylType.label)
    }
}

/** A section heading with the brass rule under it that the Settings and Studio screens share. */
@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = VynylType.headline, color = VynylColors.Cream)
        if (subtitle != null) {
            Spacer(Modifier.height(VynylSpacing.tiny))
            Text(subtitle, style = VynylType.caption, color = VynylColors.Muted)
        }
        Spacer(Modifier.height(VynylSpacing.small))
        Box(
            Modifier
                .fillMaxWidth()
                .height(VynylSpacing.hairline)
                .background(Brush.horizontalGradient(listOf(VynylColors.Brass, Color.Transparent))),
        )
    }
}

/** A row in a settings list: label, optional detail, trailing control. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = VynylSpacing.minimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = VynylType.body, color = VynylColors.Cream)
            if (detail != null) {
                Text(detail, style = VynylType.caption, color = VynylColors.Muted)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(VynylSpacing.medium))
            trailing()
        }
    }
}

/**
 * A record's waveform.
 *
 * The peaks come from the row, not from the file, so drawing one costs nothing and a list of them scrolls
 * at full speed. The drawn shape is the real shape of the audio: peaks, not an average, which is why a
 * crackle is visible on it as a spike. That matters more than it sounds — the waveform is the one place a
 * user can *see* that the vinyl character was applied.
 */
@Composable
fun WaveformStrip(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    color: Color = VynylColors.Amber,
    playedColor: Color = VynylColors.AmberBright,
    height: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val empty = peaks.isEmpty()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = if (empty) "No waveform yet" else "Waveform" },
    ) {
        val barCount = if (empty) 32 else peaks.size
        val slot = size.width / barCount
        val barWidth = max(1.5f, slot * 0.45f)
        val centre = size.height / 2f
        for (index in 0 until barCount) {
            // A flat line until there is audio: an empty waveform should look like silence, not like a
            // loading skeleton pretending to be sound.
            val magnitude = if (empty) 0.06f else peaks[index].coerceIn(0f, 1f)
            val barHeight = max(2f, magnitude * size.height * 0.92f)
            val x = index * slot + (slot - barWidth) / 2f
            val played = progress != null && index.toFloat() / barCount <= progress
            drawRoundRect(
                color = if (played) playedColor else color.copy(alpha = if (empty) 0.25f else 0.75f),
                topLeft = Offset(x, centre - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
fun StateBadge(
    state: RenderState,
    progress: Float,
    stageLabel: String,
    modifier: Modifier = Modifier,
) {
    val (label, colour) = when (state) {
        RenderState.DRAFT -> "Draft" to VynylColors.Muted
        RenderState.QUEUED -> "Waiting to press" to VynylColors.Brass
        RenderState.RENDERING -> (stageLabel.ifBlank { "Pressing" } + " · ${(progress * 100).toInt()}%") to VynylColors.Amber
        RenderState.COMPLETED -> "Ready" to VynylColors.Success
        RenderState.FAILED -> "Press failed" to VynylColors.Error
        RenderState.CANCELED -> "Cancelled" to VynylColors.Muted
    }
    Row(
        modifier = modifier.semantics { contentDescription = "State: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(VynylSpacing.small))
        Text(label, style = VynylType.monoSmall, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A record drawn with the canvas, for a card thumbnail and for the appearance picker.
 *
 * This is the one place the app draws a record in 2D. It is deliberately the same drawing as the label
 * texture the 3D renderer uploads: a library card and the object in the player must be recognisably the
 * same record, or the library looks like a catalogue of something else.
 */
@Composable
fun VinylDiscPreview(
    style: VinylStyleId,
    title: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
) {
    val base = style.baseColor
    val label = style.labelColor
    val groove = style.grooveColor
    val accent = style.brassAccent

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Record in the ${style.displayName} finish" },
    ) {
        val radius = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(brush = Brush.radialGradient(listOf(base.lighten(0.18f), base, base.darken(0.3f)), centre, radius), radius = radius, center = centre)
        var ring = radius * 0.36f
        while (ring < radius * 0.98f) {
            drawCircle(color = groove, radius = ring, center = centre, style = Stroke(width = 1f))
            ring += radius * 0.055f
        }
        drawCircle(color = label, radius = radius * 0.34f, center = centre)
        drawCircle(color = accent, radius = radius * 0.34f, center = centre, style = Stroke(width = 1.5f))
        drawCircle(color = accent.copy(alpha = 0.6f), radius = radius * 0.06f, center = centre)
        drawCircle(color = VynylColors.Obsidian, radius = radius * 0.03f, center = centre)
        if (title.isNotBlank()) {
            val initial = title.trim().first().uppercaseChar().toString()
            drawContext.canvas.nativeCanvas.drawText(
                initial,
                centre.x,
                centre.y + radius * 0.12f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(230, 254, 243, 199)
                    textSize = radius * 0.42f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
                },
            )
        }
        if (spinning) {
            // The turntable's own sheen, drawn as a soft wedge. Static here: the 3D player is the one that
            // actually moves, and a spinning 2D thumbnail would be a lie about where the audio is coming from.
            drawPath(
                Path().apply {
                    moveTo(centre.x, centre.y)
                    lineTo(centre.x + radius, centre.y - radius * 0.5f)
                    lineTo(centre.x + radius, centre.y + radius * 0.2f)
                    close()
                },
                color = Color.White.copy(alpha = 0.05f),
            )
        }
    }
}

/** A one-line empty state: a heading and a sentence, centred, with room to breathe. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(VynylSpacing.xlarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = VynylType.title, color = VynylColors.Cream, textAlign = TextAlign.Center)
        Spacer(Modifier.height(VynylSpacing.small))
        Text(body, style = VynylType.body, color = VynylColors.Muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(VynylSpacing.large))
            action()
        }
    }
}

/** A hairline rule in brass, for separating rows inside a panel. */
@Composable
fun BrassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(VynylSpacing.hairline)
            .background(VynylColors.Border),
    )
}

/** A monospaced figure with a caption under it: used by the storage panel and the render summary. */
@Composable
fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(value, style = VynylType.monoLarge, color = VynylColors.Amber)
        Text(label, style = VynylType.caption, color = VynylColors.Muted)
    }
}

/** A chip: the filter pills in the Vault and the preset badges in the Studio. */
@Composable
fun VynylChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        modifier = modifier
            .heightIn(min = VynylSpacing.minimumTouchTarget)
            .clip(shape)
            .clickable(onClick = onClick),
        color = if (selected) VynylColors.Brass else VynylColors.Panel,
        contentColor = if (selected) VynylColors.WarmPaper else VynylColors.Muted,
        shape = shape,
    ) {
        Box(
            Modifier.padding(horizontal = VynylSpacing.medium, vertical = VynylSpacing.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = VynylType.label, maxLines = 1)
        }
    }
}

/** Local helpers so the design system's maths stays here rather than in every screen. */
internal fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

internal fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
