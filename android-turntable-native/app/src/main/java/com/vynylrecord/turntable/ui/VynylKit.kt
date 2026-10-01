package com.vynylrecord.turntable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared furniture of the app shell: the way every screen announces itself, takes text, and
 * offers a slider or a button.
 *
 * Keeping these in one file is what makes the five screens look like one product rather than five
 * screens: the overline, the serif title, the brass hairline and the amber controls are defined once
 * and used everywhere.
 */

/** The screen header: a tracked-out overline, a serif title, and an optional trailing action. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String = "Digital Wax Studio",
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(text = overline)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = VynylColors.Cream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = VynylColors.Muted,
            )
        }
    }
}

/** The step strip: four badges, the current one in amber, completed ones ticked. */
@Composable
fun StepStrip(
    steps: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    onStepClick: ((Int) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val completed = index < currentIndex
            val current = index == currentIndex
            Surface(
                onClick = { onStepClick?.invoke(index) },
                enabled = onStepClick != null,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        current -> "Step ${index + 1}, $label, current step"
                        completed -> "Step ${index + 1}, $label, done"
                        else -> "Step ${index + 1}, $label"
                    }
                    role = Role.Tab
                },
                shape = RoundedCornerShape(12.dp),
                color = if (current) VynylColors.Amber else VynylColors.PanelRaised,
                border = BorderStroke(1.dp, if (current) VynylColors.AmberBright else VynylColors.Outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (completed) {
                        VynylIconGlyph(
                            icon = VynylIcon.CHECK,
                            tint = VynylColors.AmberBright,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = "${index + 1}. $label",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) VynylColors.Background else VynylColors.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A labelled text field in the app's own colours.
 *
 * Material3's defaults are a Material-toned outline; the studio look needs brass hairlines and cream
 * text, so the colours are set explicitly rather than inherited.
 */
@Composable
fun VynylTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { hint ->
            { Text(hint, color = VynylColors.Muted.copy(alpha = 0.7f)) }
        },
        supportingText = supporting?.let { text ->
            { Text(text, style = MaterialTheme.typography.labelSmall, color = VynylColors.Muted) }
        },
        singleLine = singleLine,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = VynylColors.Cream,
            unfocusedTextColor = VynylColors.Cream,
            focusedBorderColor = VynylColors.Amber,
            unfocusedBorderColor = VynylColors.Outline,
            focusedLabelColor = VynylColors.AmberBright,
            unfocusedLabelColor = VynylColors.Muted,
            cursorColor = VynylColors.AmberBright,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

/** A slider with its label on the left and its value readout on the right. */
@Composable
fun LabelledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueLabel: String = "${(value * 100).toInt()}%",
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = VynylColors.Cream,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = VynylColors.AmberBright,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = 0f..1f,
            modifier = Modifier.semantics {
                contentDescription = "$label, $valueLabel"
            },
            colors = SliderDefaults.colors(
                thumbColor = VynylColors.AmberBright,
                activeTrackColor = VynylColors.Amber,
                inactiveTrackColor = VynylColors.Outline,
                disabledThumbColor = VynylColors.Muted,
                disabledActiveTrackColor = VynylColors.Outline,
            ),
        )
    }
}

/** A primary action: the amber pill used for Continue and Press. */
@Composable
fun VynylPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: VynylIcon? = null,
    contentDescription: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription ?: label
            role = Role.Button
        },
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) VynylColors.Amber else VynylColors.PanelRaised,
        border = BorderStroke(1.dp, if (enabled) VynylColors.AmberBright else VynylColors.Outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) VynylColors.Background else VynylColors.Muted,
            )
            if (icon != null) {
                VynylIconGlyph(
                    icon = icon,
                    tint = if (enabled) VynylColors.Background else VynylColors.Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A quieter action: brass outline, cream text. */
@Composable
fun VynylSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: VynylIcon? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = label
            role = Role.Button
        },
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (enabled) VynylColors.Brass else VynylColors.Outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                VynylIconGlyph(
                    icon = icon,
                    tint = if (enabled) VynylColors.Cream else VynylColors.Muted,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) VynylColors.Cream else VynylColors.Muted,
            )
        }
    }
}

/** A labelled block of controls with a brass hairline. */
@Composable
fun LabelBlock(
    title: String,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BrassPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(text = title, modifier = Modifier.weight(1f))
                if (onReset != null) {
                    Surface(
                        onClick = onReset,
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, VynylColors.Outline),
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = VynylColors.Muted,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            content()
        }
    }
}

/** A status strip: notice in brass, error in ruby. Both are live regions for screen readers. */
@Composable
fun StatusBar(
    notice: String?,
    error: String?,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val message = error ?: notice ?: return
    val container = if (error != null) VynylColors.Ruby.copy(alpha = 0.25f) else VynylColors.PanelRaised
    val border = if (error != null) VynylColors.Ruby else VynylColors.Brass
    val textColor = if (error != null) VynylColors.Cream else VynylColors.Muted

    Surface(
        onClick = onDismiss,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** Shown when a list has nothing in it, with a short explanation rather than a blank panel. */
@Composable
fun EmptyState(
    icon: VynylIcon,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(VynylColors.PanelRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            VynylIconGlyph(
                icon = icon,
                tint = VynylColors.Brass,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = VynylColors.Cream,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = VynylColors.Muted,
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(2.dp))
            action()
        }
    }
}

/** A key/value row, used by the Settings and Vault detail panels. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = VynylColors.Cream,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = VynylColors.Muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}



