package com.vynylrecord.turntable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import com.vynylrecord.turntable.studio.StudioStage
import com.vynylrecord.turntable.studio.StudioUiState
import com.vynylrecord.turntable.vault.PressRecipe

/** Stable handles for the Studio's instrumentation tests. */
object StudioTestTags {
    const val SCENE = "studio_scene"
    const val STEP_STRIP = "studio_step_strip"
    const val RECORD_BUTTON = "studio_record_button"
    const val IMPORT_BUTTON = "studio_import_button"
    const val TITLE_FIELD = "studio_title"
    const val RECIPIENT_FIELD = "studio_recipient"
    const val SENDER_FIELD = "studio_sender"
    const val CONTINUE = "studio_continue"
    const val BACK = "studio_back"
    const val PRESS = "studio_press"
    const val PRESS_PROGRESS = "studio_press_progress"
    const val WAVEFORM = "studio_waveform"
    const val SOURCE_SUMMARY = "studio_source_summary"

    fun stageTag(stage: StudioStage): String = "studio_stage_${stage.name}"

    fun styleTag(style: VinylStyle): String = "studio_style_${style.name}"

    fun recipeTag(preset: PressRecipe.Preset): String = "studio_recipe_${preset.name}"
}

/**
 * The Studio: capture a voice, dedicate it, choose how it sounds and how it looks, then press it.
 *
 * The step order is the product's own: a voice note is not finished until it has been dedicated to
 * someone, so the label comes before the sound and the look. Every step is reachable from the strip,
 * and the press button is only live once there is a master and a dedication — a side with no label
 * on it would be a blank record.
 */
@Composable
fun StudioScreen(
    state: StudioUiState,
    onStageSelected: (StudioStage) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onImport: () -> Unit,
    onClearSource: () -> Unit,
    onTitleChange: (String) -> Unit,
    onRecipientChange: (String) -> Unit,
    onSenderChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onCatalogueChange: (String) -> Unit,
    onSideChange: (RecordSide) -> Unit,
    onStyleChange: (VinylStyle) -> Unit,
    onSpeedChange: (PlatterSpeed) -> Unit,
    onRecipeChange: (PressRecipe) -> Unit,
    onPress: () -> Unit,
    onDismissOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VynylColors.Background)
            .testTag(StudioTestTags.SCENE),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Engrave Your Voice",
                subtitle = state.stage.promise,
            )

            StepStrip(
                steps = StudioStage.ordered.map { it.label },
                currentIndex = state.stage.ordinal,
                onStepClick = { index -> onStageSelected(StudioStage.entries[index]) },
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .testTag(StudioTestTags.STEP_STRIP),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusBar(
                    notice = state.notice,
                    error = state.error,
                    onDismiss = onDismissOverlay,
                )

                when (state.stage) {
                    StudioStage.CAPTURE -> CaptureStep(
                        state = state,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onImport = onImport,
                        onClearSource = onClearSource,
                    )

                    StudioStage.DEDICATE -> DedicateStep(
                        state = state,
                        onTitleChange = onTitleChange,
                        onRecipientChange = onRecipientChange,
                        onSenderChange = onSenderChange,
                        onDateChange = onDateChange,
                        onCatalogueChange = onCatalogueChange,
                        onSideChange = onSideChange,
                    )

                    StudioStage.RECIPE -> RecipeStep(
                        state = state,
                        onRecipeChange = onRecipeChange,
                    )

                    StudioStage.ATMOSPHERE -> AtmosphereStep(
                        state = state,
                        onStyleChange = onStyleChange,
                        onSpeedChange = onSpeedChange,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            StudioFooter(
                state = state,
                onNext = onNext,
                onBack = onBack,
                onPress = onPress,
            )
        }

        if (state.press.active) {
            PressingOverlay(state = state)
        }
    }
}

// ---------------------------------------------------------------------------- step 1: capture

@Composable
private fun CaptureStep(
    state: StudioUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onImport: () -> Unit,
    onClearSource: () -> Unit,
) {
    val hasSource = state.source != null

    Text(
        text = if (hasSource) "That side is on the lathe" else "Tap below to begin speaking into the wax",
        style = MaterialTheme.typography.bodyMedium,
        color = VynylColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    // The record button: a brass-ringed amber disc that grows a live level ring while it listens.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.isRecording) {
                Canvas(modifier = Modifier.size(132.dp)) {
                    drawArc(
                        color = VynylColors.AmberBright.copy(alpha = 0.75f),
                        startAngle = -90f,
                        sweepAngle = 360f * state.inputLevel.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                    )
                }
            }
            Surface(
                onClick = { if (state.isRecording) onStopRecording() else onStartRecording() },
                enabled = !state.press.active,
                modifier = Modifier
                    .size(104.dp)
                    .semantics {
                        contentDescription = if (state.isRecording) "Stop recording" else "Start recording"
                        role = Role.Button
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                    }
                    .testTag(StudioTestTags.RECORD_BUTTON),
                shape = CircleShape,
                color = if (state.isRecording) VynylColors.Ruby else VynylColors.Amber,
                border = BorderStroke(2.dp, VynylColors.AmberBright),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    VynylIconGlyph(
                        icon = if (state.isRecording) VynylIcon.STOP else VynylIcon.MIC,
                        tint = if (state.isRecording) VynylColors.Cream else VynylColors.Background,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }

    Text(
        text = if (state.isRecording) state.recordingLabel else "--:--",
        style = TimecodeStyle,
        color = if (state.isRecording) VynylColors.AmberBright else VynylColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                contentDescription = if (state.isRecording) {
                    "Recording, ${state.recordingLabel}"
                } else {
                    "Ready to record"
                }
            },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        VynylSecondaryButton(
            label = "Or import local voice recording",
            onClick = onImport,
            icon = VynylIcon.IMPORT,
            enabled = !state.press.active && !state.isRecording,
            modifier = Modifier.testTag(StudioTestTags.IMPORT_BUTTON),
        )
    }

    if (hasSource) {
        SourceSummary(state = state, onClearSource = onClearSource)
    }
}

@Composable
private fun SourceSummary(state: StudioUiState, onClearSource: () -> Unit) {
    val source = state.source ?: return
    LabelBlock(
        title = "Master on the lathe",
        onReset = onClearSource,
    ) {
        Text(
            text = source.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = VynylColors.Cream,
            modifier = Modifier.testTag(StudioTestTags.SOURCE_SUMMARY),
        )
        Waveform(
            values = source.waveform,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(StudioTestTags.WAVEFORM),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Metric(label = "Length", value = source.durationLabel)
            Metric(label = "Format", value = "${source.sampleRate / 1000} kHz ${source.channelsLabel}")
            Metric(label = "Peak", value = "%+.1f dB".format(source.peakDecibels))
        }
        if (source.spansMultipleSides) {
            Text(
                text = "This is longer than one side — it will be cut across more than one record.",
                style = MaterialTheme.typography.labelSmall,
                color = VynylColors.AmberBright,
            )
        }
    }
}

// ---------------------------------------------------------------------------- step 2: dedicate

@Composable
private fun DedicateStep(
    state: StudioUiState,
    onTitleChange: (String) -> Unit,
    onRecipientChange: (String) -> Unit,
    onSenderChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onCatalogueChange: (String) -> Unit,
    onSideChange: (RecordSide) -> Unit,
) {
    Text(
        text = "This is what gets printed on the label in the middle of the record.",
        style = MaterialTheme.typography.bodyMedium,
        color = VynylColors.Muted,
    )

    VynylTextField(
        value = state.title,
        onValueChange = onTitleChange,
        label = "Title",
        placeholder = "Late Night Letter",
        supporting = "Printed large across the label",
        modifier = Modifier.testTag(StudioTestTags.TITLE_FIELD),
    )
    VynylTextField(
        value = state.recipient,
        onValueChange = onRecipientChange,
        label = "For",
        placeholder = "Who is this for?",
        supporting = "Printed as \"for …\" under the title",
        modifier = Modifier.testTag(StudioTestTags.RECIPIENT_FIELD),
    )
    VynylTextField(
        value = state.sender,
        onValueChange = onSenderChange,
        label = "From",
        placeholder = "Your name",
        modifier = Modifier.testTag(StudioTestTags.SENDER_FIELD),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        VynylTextField(
            value = state.date,
            onValueChange = onDateChange,
            label = "Date",
            modifier = Modifier.weight(1f),
        )
        VynylTextField(
            value = state.catalogue,
            onValueChange = onCatalogueChange,
            label = "Catalogue",
            modifier = Modifier.weight(1f),
        )
    }

    LabelBlock(title = "Side") {
        // A direct choice, not a flip: the Studio decides which side of the record this dedication is
        // engraved on, and a master long enough for two sides presses both of them in one job.
        ChipRow {
            RecordSide.entries.forEach { side ->
                VynylChoiceChip(
                    label = side.displayName,
                    supporting = if (side == RecordSide.A) "The first side cut" else "The other side",
                    selected = state.side == side,
                    onClick = { onSideChange(side) },
                )
            }
        }
        Text(
            text = "The label carries the side you choose here. A side outlives its first pressing, so " +
                "if the audio is longer than one side, both are cut and this one is pressed first.",
            style = MaterialTheme.typography.labelSmall,
            color = VynylColors.Muted,
        )
    }

    if (!state.dedicationComplete) {
        Text(
            text = "A side needs a title and someone to dedicate it to before it can be pressed.",
            style = MaterialTheme.typography.labelSmall,
            color = VynylColors.AmberBright,
        )
    }
}

// ---------------------------------------------------------------------------- step 3: recipe

@Composable
private fun RecipeStep(state: StudioUiState, onRecipeChange: (PressRecipe) -> Unit) {
    Text(
        text = "The recipe is cut into the record, not played back over it — the file keeps its character.",
        style = MaterialTheme.typography.bodyMedium,
        color = VynylColors.Muted,
    )

    ChipRow {
        PressRecipe.Preset.entries.forEach { preset ->
            VynylChoiceChip(
                label = preset.displayName,
                supporting = preset.blurb,
                selected = state.recipePreset == preset,
                onClick = { onRecipeChange(preset.recipe) },
                modifier = Modifier.testTag(StudioTestTags.recipeTag(preset)),
            )
        }
    }

    LabelBlock(title = "Character") {
        RecipeSlider("Surface crackle", state.recipe.crackle) { onRecipeChange(state.recipe.copy(crackle = it)) }
        RecipeSlider("Groove noise", state.recipe.surfaceNoise) { onRecipeChange(state.recipe.copy(surfaceNoise = it)) }
        RecipeSlider("Wow & flutter", state.recipe.wowFlutter) { onRecipeChange(state.recipe.copy(wowFlutter = it)) }
        RecipeSlider("Warmth", state.recipe.warmth) { onRecipeChange(state.recipe.copy(warmth = it)) }
        RecipeSlider("Room tone", state.recipe.roomTone) { onRecipeChange(state.recipe.copy(roomTone = it)) }
        RecipeSlider("Tape hiss", state.recipe.hiss) { onRecipeChange(state.recipe.copy(hiss = it)) }
        RecipeSlider("Cutter drive", state.recipe.saturation) { onRecipeChange(state.recipe.copy(saturation = it)) }
    }

    LabelBlock(title = "Mastering") {
        SwitchRow(
            label = "Trim silence and cut lead-in",
            checked = state.recipe.trimSilence,
            onCheckedChange = { onRecipeChange(state.recipe.copy(trimSilence = it)) },
        )
        SwitchRow(
            label = "Normalise to full level",
            checked = state.recipe.normalize,
            onCheckedChange = { onRecipeChange(state.recipe.copy(normalize = it)) },
        )
        Text(
            text = "Sound: ${state.recipe.describe()}",
            style = MaterialTheme.typography.labelSmall,
            color = VynylColors.AmberBright,
        )
    }
}

@Composable
private fun RecipeSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    LabelledSlider(label = label, value = value, onValueChange = onChange)
}

// ---------------------------------------------------------------------------- step 4: atmosphere

@Composable
private fun AtmosphereStep(
    state: StudioUiState,
    onStyleChange: (VinylStyle) -> Unit,
    onSpeedChange: (PlatterSpeed) -> Unit,
) {
    Text(
        text = "How the pressing looks on the deck: the vinyl itself, and how fast it turns.",
        style = MaterialTheme.typography.bodyMedium,
        color = VynylColors.Muted,
    )

    ChipRow {
        VinylStyle.entries.forEach { style ->
            VynylChoiceChip(
                label = style.displayName,
                supporting = if (style.isTranslucent) "translucent" else "solid",
                selected = state.style == style,
                onClick = { onStyleChange(style) },
                modifier = Modifier.testTag(StudioTestTags.styleTag(style)),
            )
        }
    }

    LabelBlock(title = "Platter speed") {
        ChipRow {
            PlatterSpeed.entries.forEach { speed ->
                VynylChoiceChip(
                    label = if (speed == PlatterSpeed.FORTY_FIVE) "45 rpm" else "33⅓ rpm",
                    supporting = if (speed == PlatterSpeed.FORTY_FIVE) "single" else "long play",
                    selected = state.speed == speed,
                    onClick = { onSpeedChange(speed) },
                )
            }
        }
    }

    LabelBlock(title = "The side you are about to press") {
        DetailRow(label = "Title", value = state.title.ifBlank { "—" })
        DetailRow(label = "For", value = state.recipient.ifBlank { "—" })
        DetailRow(label = "From", value = state.sender.ifBlank { "—" })
        DetailRow(label = "Label", value = state.style.displayName)
        DetailRow(label = "Sound", value = state.recipe.describe())
        DetailRow(label = "Master", value = state.source?.let { "${it.displayName} · ${it.durationLabel}" } ?: "none")
    }
}

// ---------------------------------------------------------------------------- footer and overlay

@Composable
private fun StudioFooter(
    state: StudioUiState,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VynylColors.Panel.copy(alpha = 0.9f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.stage.isFirst) {
                VynylSecondaryButton(
                    label = "Back",
                    onClick = onBack,
                    icon = VynylIcon.CHEVRON_LEFT,
                    modifier = Modifier.testTag(StudioTestTags.BACK),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.stage.isLast) {
                VynylPrimaryButton(
                    label = if (state.canPress) "Press the record" else "Press",
                    onClick = onPress,
                    enabled = state.canPress,
                    icon = VynylIcon.DISC,
                    modifier = Modifier.testTag(StudioTestTags.PRESS),
                )
            } else {
                VynylPrimaryButton(
                    label = "Continue",
                    onClick = onNext,
                    icon = VynylIcon.CHEVRON_RIGHT,
                    modifier = Modifier.testTag(StudioTestTags.CONTINUE),
                )
            }
        }
    }
}

/** The pressing sheet: shown over the Studio while a side is being cut. */
@Composable
private fun PressingOverlay(state: StudioUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VynylColors.Background.copy(alpha = 0.92f))
            .semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                contentDescription = "Pressing ${state.press.sideLabel}, ${state.press.percent} percent, ${state.press.stage}"
            }
            .testTag(StudioTestTags.PRESS_PROGRESS),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                progress = { state.press.fraction },
                color = VynylColors.AmberBright,
                trackColor = VynylColors.Outline,
                strokeWidth = 6.dp,
                modifier = Modifier.size(96.dp),
            )
            Text(
                text = "Pressing ${state.press.sideLabel}",
                style = MaterialTheme.typography.titleLarge,
                color = VynylColors.Cream,
            )
            Text(
                text = state.press.stage,
                style = MaterialTheme.typography.bodyMedium,
                color = VynylColors.Muted,
            )
            LinearProgressIndicator(
                progress = { state.press.fraction },
                color = VynylColors.Amber,
                trackColor = VynylColors.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${state.press.percent}% · side ${state.press.sideIndex} of ${state.press.sideCount}",
                style = MaterialTheme.typography.labelMedium,
                color = VynylColors.AmberBright,
            )
        }
    }
}

// ---------------------------------------------------------------------------- shared bits

/** The waveform envelope of the master, normalised, as brass bars. */
@Composable
fun Waveform(values: List<Float>, modifier: Modifier = Modifier, playedFraction: Float? = null) {
    Canvas(modifier = modifier.clip(RoundedCornerShape(8.dp))) {
        if (values.isEmpty()) return@Canvas
        val barCount = values.size
        val barWidth = size.width / barCount
        val mid = size.height / 2f
        values.forEachIndexed { index, value ->
            val height = (value.coerceIn(0f, 1f) * size.height * 0.92f).coerceAtLeast(1.5f)
            val played = playedFraction != null && index.toFloat() / barCount <= playedFraction
            drawRect(
                color = if (played) VynylColors.AmberBright else VynylColors.Brass,
                topLeft = Offset(index * barWidth, mid - height / 2f),
                size = Size(barWidth * 0.72f, height),
            )
        }
    }
}

/** A label and a switch on one row. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = VynylColors.Cream,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VynylColors.Background,
                checkedTrackColor = VynylColors.Amber,
                uncheckedThumbColor = VynylColors.Muted,
                uncheckedTrackColor = VynylColors.PanelRaised,
                uncheckedBorderColor = VynylColors.Outline,
            ),
        )
    }
}


/** A small pill used for the "1 of 6" and duration badges on a vault row. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, accent: Color = VynylColors.Outline) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = VynylColors.Muted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
